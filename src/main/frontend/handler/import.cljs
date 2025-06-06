(ns frontend.handler.import
  "Fns related to import from external services"
  (:require [cljs.core.async.interop :refer [p->c]]
            [clojure.core.async :as async]
            [clojure.edn :as edn]
            [clojure.string :as string]
            [clojure.walk :as walk]
            [frontend.db :as db]
            [frontend.db.async :as db-async]
            [frontend.format.block :as block]
            [frontend.format.mldoc :as mldoc]
            [frontend.handler.editor :as editor]
            [frontend.handler.notification :as notification]
            [frontend.handler.page :as page-handler]
            [frontend.state :as state]
            [frontend.util :as util]
            [logseq.graph-parser.mldoc :as gp-mldoc]
            [logseq.graph-parser.whiteboard :as gp-whiteboard]
            [medley.core :as medley]
            [promesa.core :as p]))

;;; import OPML files
(defn import-from-opml!
  [data finished-ok-handler]
  #_:clj-kondo/ignore
  (when-let [repo (state/get-current-repo)]
    (let [config (gp-mldoc/default-config :markdown)
          [headers parsed-blocks] (mldoc/opml->edn config data)
          ;; add empty pos metadata
          parsed-blocks (map (fn [b] [b {}]) parsed-blocks)
          page-name (:title headers)
          parsed-blocks (->>
                         (block/extract-blocks parsed-blocks "" :markdown {:page-name page-name})
                         (mapv editor/wrap-parse-block))]
      (p/do!
       (when (not (db/page-exists? page-name))
         (page-handler/<create! page-name {:redirect? false}))
       (let [page-block (db/get-page page-name)
             children (:block/_parent page-block)
             blocks (db/sort-by-order children)
             last-block (last blocks)
             snd-last-block (last (butlast blocks))
             [target-block sibling?] (if (and last-block (seq (:block/title last-block)))
                                       [last-block true]
                                       (if snd-last-block
                                         [snd-last-block true]
                                         [page-block false]))]
         (editor/paste-blocks
          parsed-blocks
          {:target-block target-block
           :sibling? sibling?})
         (finished-ok-handler [page-name]))))))

(defn create-page-with-exported-tree!
  "Create page from the per page object generated in `export-repo-as-edn-v2!`
   Return page-name (title)
   Extension to `insert-block-tree-after-target`
   :id       - page's uuid
   :title    - page's title (original name)
   :children - tree
   :properties - map
   "
  [{:keys [type uuid title children properties] :as tree}]
  (let [title (string/trim title)
        has-children? (seq children)
        page-format (or (some-> tree (:children) (first) (:format)) :markdown)
        whiteboard? (= type "whiteboard")]
    (p/do!
     (try (page-handler/<create! title {:redirect?           false
                                        :format              page-format
                                        :uuid                uuid
                                        :properties          properties
                                        :whiteboard?         whiteboard?})
          (catch :default e
            (js/console.error e)
            (prn {:tree tree})
            (notification/show! (str "Error happens when creating page " title ":\n"
                                     e
                                     "\nSkipped and continue the remaining import.") :error)))
     (when has-children?
       (let [page-name (util/page-name-sanity-lc title)
             page-block (db/get-page page-name)]
        ;; Missing support for per block format (or deprecated?)
         (try (if whiteboard?
               ;; only works for file graph :block/properties
                (let [blocks (->> children
                                  (map (partial medley/map-keys (fn [k] (keyword "block" k))))
                                  (map gp-whiteboard/migrate-shape-block)
                                  (map #(merge % (gp-whiteboard/with-whiteboard-block-props % [:block/uuid uuid]))))]
                  (db/transact! blocks))
                (editor/insert-block-tree children page-format
                                          {:target-block page-block
                                           :sibling?     false
                                           :keep-uuid?   true}))
              (catch :default e
                (js/console.error e)
                (prn {:tree tree})
                (notification/show! (str "Error happens when creating block content of page " title "\n"
                                         e
                                         "\nSkipped and continue the remaining import.") :error)))))))
  title)

(defn- pre-transact-uuids!
  "Collect all uuids from page trees and write them to the db before hand."
  [pages]
  (let [uuids (mapv (fn [block]
                      {:block/uuid (:uuid block)})
                    (mapcat #(tree-seq map? :children %)
                            pages))]
    (db/transact! uuids)))

(defn- import-from-tree!
  "Not rely on file system - backend compatible.
   tree-translator-fn: translate exported tree structure to the desired tree for import"
  [data tree-translator-fn]
  (let [imported-chan (async/promise-chan)]
    (try
      (let [blocks (->> (:blocks data)
                        (mapv tree-translator-fn)
                        (sort-by :title)
                        (medley/indexed))
            job-chan (async/to-chan! blocks)]
        (state/set-state! [:graph/importing-state :total] (count blocks))
        (pre-transact-uuids! blocks)
        (async/go-loop []
          (if-let [[i block] (async/<! job-chan)]
            (do
              (state/set-state! [:graph/importing-state :current-idx] (inc i))
              (state/set-state! [:graph/importing-state :current-page] (:title block))
              (async/<! (async/timeout 10))
              (create-page-with-exported-tree! block)
              (recur))
            (let [result (async/<! (p->c (db-async/<get-all-referenced-blocks-uuid (state/get-current-repo))))]
              (editor/set-blocks-id! result)
              (async/offer! imported-chan true)))))

      (catch :default e
        (notification/show! (str "Error happens when importing:\n" e) :error)
        (async/offer! imported-chan true)))))

(defn tree-vec-translate-edn
  "Actions to do for loading edn tree structure.
   1) Removes namespace `:block/` from all levels of the `tree-vec`
   2) Rename all :block/page-name to :title
   3) Rename all :block/id to :uuid"
  ([tree-vec]
   (let [kw-trans-fn #(-> %
                          str
                          (string/replace ":block/page-name" ":block/title")
                          (string/replace ":block/id" ":block/uuid")
                          (string/replace ":block/" "")
                          keyword)
         map-trans-fn (fn [acc k v]
                        (assoc acc (kw-trans-fn k) v))
         tree-trans-fn (fn [form]
                         (if (and (map? form)
                                  (:block/id form))
                           (reduce-kv map-trans-fn {} form)
                           form))]
     (walk/postwalk tree-trans-fn tree-vec))))

(defn import-from-edn!
  [raw finished-ok-handler]
  (try
    (let [data (edn/read-string raw)]
      (async/go
        (async/<! (import-from-tree! data tree-vec-translate-edn))
        (finished-ok-handler nil)))
    (catch :default e
      (js/console.error e)
      (notification/show!
       (str (.-message e))
       :error)))) ;; it was designed to accept a list of imported page names but now deprecated

(defn tree-vec-translate-json
  "Actions to do for loading json tree structure.
   1) Rename all :id to :uuid
   2) Rename all :page-name to :title
   3) Rename all :format \"markdown\" to :format `:markdown`"
  ([tree-vec]
   (let [kw-trans-fn #(-> %
                          str
                          (string/replace ":page-name" ":title")
                          (string/replace ":id" ":uuid")
                          (string/replace #"^:" "")
                          keyword)
         map-trans-fn (fn [acc k v]
                        (cond (= :format k)
                              (assoc acc (kw-trans-fn k) (keyword v))
                              (= :id k)
                              (assoc acc (kw-trans-fn k) (uuid v))
                              :else
                              (assoc acc (kw-trans-fn k) v)))
         tree-trans-fn (fn [form]
                         (if (and (map? form)
                                  (:id form))
                           (reduce-kv map-trans-fn {} form)
                           form))]
     (walk/postwalk tree-trans-fn tree-vec))))

(defn import-from-json!
  [raw finished-ok-handler]
  (let [json     (js/JSON.parse raw)
        clj-data (js->clj json :keywordize-keys true)]
    (async/go
      (async/<! (import-from-tree! clj-data tree-vec-translate-json))
      (finished-ok-handler nil)))) ;; it was designed to accept a list of imported page names but now deprecated
