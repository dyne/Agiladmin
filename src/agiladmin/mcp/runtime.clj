(ns agiladmin.mcp.runtime
  "One composition root. L5 installs trusted baseline/status readers, never client input."
  (:require [agiladmin.mcp.credentials :as credentials] [agiladmin.auth.core :as auth]
            [agiladmin.mcp.http :as http] [agiladmin.mcp.tools :as tools]
            [agiladmin.work-ledger :as ledger] [agiladmin.work-policy :as policy]
            [agiladmin.work-preview :as preview] [agiladmin.work-workbook :as workbook]
            [agiladmin.webpage :as web] [agiladmin.utils :as util]
            [agiladmin.core :as core] [failjure.core :as f])
  (:import [java.net URI URLEncoder] [java.nio.file Files Paths Path LinkOption]
           [java.io ByteArrayInputStream]))

(defn read-workbook
  "Only server account mapping and configured root determine the annual file."
  [config owner year]
  (try
    (let [root (.normalize (.toAbsolutePath (Paths/get (get-in config [:agiladmin :budgets :path]) (make-array String 0))))
          target (.normalize (.resolve root (util/name-year-to-timesheet (:person owner) year)))]
      (when-not (.startsWith target root) (throw (ex-info "Unsafe workbook" {})))
      (loop [ancestor target]
        (when ancestor
          (when (Files/isSymbolicLink ancestor) (throw (ex-info "Unsafe workbook" {})))
          (recur (.getParent ancestor))))
      (when (Files/exists target (make-array LinkOption 0))
        (when (> (Files/size target) preview/max-file-bytes) (throw (ex-info "Workbook too large" {})))
        (Files/readAllBytes target)))
    (catch Exception _ (policy/error :workbook-unavailable [] "The server-mapped workbook cannot be read safely."
                                    "Ask the operator to repair workbook storage before creating a preview."))))
(defn public-preview [config settings value]
  (let [snapshot (:snapshot value)]
    (merge (select-keys (tools/public-data snapshot) [:month :revision :allocation :capacity :totals :possible_duplicate_ids])
           {:preview_id (:preview-id value) :digest (:preview-digest value)
            :record_count (count (:records snapshot))
            :created_at (:created-at value) :expires_at (:expires-at value)
            :policy (merge (select-keys (tools/public-data (:allocation snapshot)) [:policy_version :policy_hash])
                           {:paid_cap_minutes (policy/paid-cap (merge policy/defaults settings) (:owner-id value))
                            :timezone (:timezone (merge policy/defaults settings))})
            :source_fingerprint (get-in value [:source-inspection :fingerprint])
            :target_month_baseline (get-in value [:source-inspection :month-baseline])
            :artifact_fingerprint (:artifact-fingerprint value) :filename (:filename value)
            :owner_review_url (web/public-url config (str "/work/review/" (:preview-id value)))
            :artifact_url (web/public-url config (str "/mcp/artifacts/" (:preview-id value)))
            :exact_changes (tools/public-data (:changes value))
            :next_action "The person follows owner_review_url and explicitly confirms in their authenticated browser. Bearer credentials cannot approve. Then poll get_publication_status."})))
(defn download-handler [server store]
  (fn [request id]
    (let [owner ((:authorize-request server) request)]
      (cond
        (:status owner) owner
        (not= :get (:request-method request))
        (assoc-in (http/response 405 {:error "Use GET"}) [:headers "Allow"] "GET")
        :else
        (let [artifact (preview/artifact store owner id)]
          (if (f/failed? artifact) (http/response 404 {:error "Preview unavailable; create a fresh own preview"})
              {:status 200 :headers {"Content-Type" "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                                    "Cache-Control" "private, no-store" "X-Content-Type-Options" "nosniff"
                                    "Content-Disposition" (str "attachment; filename=\"timesheet.xlsx\"; filename*=UTF-8''"
                                                               (clojure.string/replace (URLEncoder/encode (:filename artifact) "UTF-8") "+" "%20"))}
               :body (ByteArrayInputStream. (:bytes artifact))}))))))
(defn install-publication!
  "L5 composition hook. Readers accept trusted owner/month and return baseline or
  publication state. This does not add an MCP approval capability."
  [runtime {:keys [read-baseline publication-status] :as readers}]
  (if (and (fn? read-baseline) (fn? publication-status) (= #{:read-baseline :publication-status} (set (keys readers))))
    (do (reset! (:publication-readers runtime) readers) true)
    (policy/error :invalid-publication-adapter [] "Trusted publication readers are required." "Configure the browser publication boundary.")))
(defn start!
  ([config] (start! config (constantly config)))
  ([config config-provider]
   (let [settings (policy/validate-config (get-in config [:agiladmin :mcp]) (get-in config [:agiladmin :budgets :path]))]
     (when (and (not (f/failed? settings)) (:enabled settings))
       (when (or (:development? @auth/backend) (not (fn? (:active-accounts @auth/backend))))
         (throw (ex-info "MCP requires production account resolution" {})))
       (let [origin (URI. (get-in config [:agiladmin :webserver :base-host] ""))]
         (when-not (and (= "https" (.getScheme origin)) (.getHost origin)
                        (nil? (.getUserInfo origin)) (nil? (.getQuery origin)) (nil? (.getFragment origin))
                        (contains? #{"" "/"} (.getPath origin)))
           (throw (ex-info "MCP requires an HTTPS public base-host origin" {})))
         (let [l (ledger/open-ledger! settings (get-in config [:agiladmin :budgets :path]))]
           (when (f/failed? l) (throw (ex-info "MCP storage unavailable" {})))
           (try
             (let [credential-store (credentials/store (:data-path settings))
                   preview-store (preview/store (:data-path settings))
                   readers (atom {})
                   current-settings (fn [] (let [conf (config-provider)
                                                 current (policy/validate-config (get-in conf [:agiladmin :mcp])
                                                                                 (get-in conf [:agiladmin :budgets :path]))]
                                             (if (or (f/failed? current) (not= (:data-path settings) (:data-path current)))
                                               (policy/error :invalid-config [] "MCP storage configuration changed or became invalid."
                                                             "Restore valid settings or restart with the new storage configuration.") current)))
                   workbook (workbook/workbook-adapter
                             {:resolve-owner credentials/resolve-owner
                              :read-workbook (fn [owner year] (read-workbook (config-provider) owner year))
                              :read-baseline (fn [owner month] (when-let [read (:read-baseline @readers)] (read owner month)))})
                   deps {:ledger l :settings current-settings :workbook workbook :preview-store preview-store
                         :projects (fn [_] (let [catalog (core/load-all-projects (config-provider))]
                                             (if (f/failed? catalog) (throw (ex-info "Catalog unavailable" {})) catalog)))
                         :preview (fn [owner month]
                                    (f/attempt-all [settings (current-settings)
                                                    value (preview/create! preview-store l workbook owner settings month)
                                                    live-settings (current-settings)
                                                    _valid (preview/verify-preview l workbook owner live-settings value)]
                                      (public-preview (config-provider) settings value)))
                         :publication-status (fn [owner month]
                                               (if-let [read (:publication-status @readers)] (read owner month) {:state "draft"}))}
                   server (http/server {:credential-store credential-store
                                        :expected-origin (str "https://" (.toLowerCase (.getRawAuthority origin)))
                                        :expected-authority (.toLowerCase (.getRawAuthority origin))
                                        :tools (tools/specifications deps) :resources (tools/resources)
                                        :prompts (tools/prompts) :instructions tools/workflow})]
               (assoc server :ledger l :deps deps :workbook workbook :preview-store preview-store
                      :publication-readers readers :artifact-handler (download-handler server preview-store)))
             (catch Exception ex (.close l) (throw ex)))))))))
(defn stop! [runtime]
  (when runtime (try (.close (:sdk runtime)) (finally (.close (:ledger runtime))))))
