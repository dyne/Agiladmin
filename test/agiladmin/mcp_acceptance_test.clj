(ns agiladmin.mcp-acceptance-test
  "Pinned SDK -> actual HTTP session/CSRF review -> local Git -> production readers.
  The test-only login seam never enters the application. All storage is temporary."
  (:use midje.sweet)
  (:require [agiladmin.work-publication-test :as publication]
            [agiladmin.mcp-http-test :as transport]
            [agiladmin.mcp-tools-test :as fixtures]
            [agiladmin.mcp.http :as http]
            [agiladmin.mcp.tools :as tools]
            [agiladmin.mcp.credentials :as credentials]
            [agiladmin.handlers :as handlers]
            [agiladmin.ring :as ring]
            [agiladmin.core :as core]
            [agiladmin.tabular :as tab]
            [agiladmin.work-workbook :as workbook]
            [agiladmin.work-archive :as archive]
            [clj-http.client :as browser]
            [clj-http.cookies :as cookies]
            [ring.middleware.defaults :refer [wrap-defaults site-defaults]]
            [ring.adapter.jetty :as jetty]
            [cheshire.core :as json]
            [clojure.java.io :as io])
  (:import [java.time Duration]
           [java.util.function Consumer]
           [java.net URI]
           [java.net.http HttpRequest$Builder]
           [io.modelcontextprotocol.client McpClient]
           [io.modelcontextprotocol.client.transport HttpClientStreamableHttpTransport]
           [io.modelcontextprotocol.spec McpSchema$CallToolRequest]))

(defn call [client tool args]
  (fixtures/content (.callTool client (McpSchema$CallToolRequest. tool (json/parse-string (json/generate-string args))))))
(defn client-for [origin token]
  (-> (McpClient/sync
       (-> (HttpClientStreamableHttpTransport/builder origin)
           (.endpoint "/payroll/mcp")
           (.supportedProtocolVersions [http/protocol-version])
           (.openConnectionOnStartup false)
           (.customizeRequest (reify Consumer
                                (accept [_ builder]
                                  (.header ^HttpRequest$Builder builder "Authorization" (str "Bearer " token)))))
           (.build)))
      (.requestTimeout (Duration/ofSeconds 10)) (.build)))
(defn browser-confirm! [origin review owner-id]
  (let [store (cookies/cookie-store)
        opts {:cookie-store store :throw-exceptions false}
        path (.getPath (URI. (:owner_review_url review)))
        _ (browser/get (str origin "/payroll/test-login")
                       (assoc opts :query-params {"owner" owner-id}))
        page (browser/get (str origin path) opts)
        fields (into {} (map (fn [[_ k v]] [k v])
                            (re-seq #"name=\"([^\"]+)\"[^>]*value=\"([^\"]*)\"" (:body page))))
        denied (browser/post (str origin path "/confirm") (assoc opts :form-params {}))
        result (browser/post (str origin path "/confirm") (assoc opts :form-params fields))]
    {:review-status (:status page) :csrf-status (:status denied)
     :confirm-status (:status result) :pushed? (.contains (:body result) "Publication state: pushed")}))
(defn report [env preview]
  (let [path (str (io/file (:budgets env) (:filename preview)))
        ts (core/load-timesheet path)]
    (try
      (let [rows (core/load-monthly-hours ts "2025-2" (constantly true))
            totals (fn [tag] (reduce + 0 (map :hours (filter #(= tag (:tag %)) rows))))
            costs (:rows (core/derive-costs (tab/dataset rows) {}
                                           {:A {:rates {(keyword (:name ts)) 40}} :B {:rates {(keyword (:name ts)) 30}}}))]
        {:paid-hours (totals "") :vol-hours (totals "VOL")
         :paid-costs (mapv :cost (filter #(= "" (:tag %)) costs))
         :vol-costs (mapv :cost (filter #(= "VOL" (:tag %)) costs))})
      (finally (.close (:xls ts))))))

(fact "Two owners discover, retry, correct, review and push drafts that reconcile with official Excel reports"
  (publication/env
   (fn [env]
     (swap! (:config env) assoc-in [:agiladmin :mcp :person-cap-overrides "manager-id"] 300)
     (let [delegate (atom (constantly {:status 503 :body ""}))
           web (jetty/run-jetty #(@delegate %) {:host "127.0.0.1" :port 0 :join? false})
           port (.getLocalPort (first (.getConnectors web)))
           origin (str "http://127.0.0.1:" port)
           server (http/server {:credential-store (credentials/store (:data env))
                                :expected-origin origin :expected-authority (str "127.0.0.1:" port)
                                :tools (tools/specifications (get-in env [:runtime :deps]))})
           web-handler (wrap-defaults
                        (fn [req]
                          (if (= "/test-login" (:uri req))
                            (let [owner (credentials/resolve-owner (get-in req [:params :owner]))]
                              {:status 200 :body "Test session" :session {:auth {:id (:owner-id owner) :name (:person owner)}}})
                            (handlers/work-handler req)))
                        (assoc-in site-defaults [:security :anti-forgery] false))
           a (client-for origin (get-in env [:admin :token]))
           m (client-for origin (get-in env [:manager :token]))]
       (reset! delegate (fn [req]
                          (let [req (update req :uri #(clojure.string/replace-first % #"^/payroll" ""))]
                            (if (= "/mcp" (:uri req)) ((:handler server) req) (web-handler req)))))
       (try
         (with-redefs [ring/config (:config env) handlers/mcp-state (atom (:runtime env))]
           (.protocolVersion (.initialize a)) => http/protocol-version
           (.protocolVersion (.initialize m)) => http/protocol-version
           (doseq [[client owner cap] [[a "admin-id" 480] [m "manager-id" 300]]]
             (let [context (call client "get_work_context" {})
                   entries [(-> fixtures/entry (dissoc :project_id) (assoc :external_id "acceptance-design" :organization " Studio " :task_id "dev" :date "2025-02-28"))
                            (assoc fixtures/second-entry :external_id "acceptance-delivery" :date "2025-02-28")]
                   cmd {:month "2025-02" :request_id "guided-6h-4h" :expected_revision 0 :entries entries}
                   totals {:recorded_minutes 600 :paid_minutes cap :vol_minutes (- 600 cap)}]
               (:paid_cap_minutes context) => cap
               (some #(= "studio" (:organization %)) (:catalog context)) => truthy
               (some #(= "DEV" (:task_id %)) (:catalog context)) => truthy
               (:totals (call client "validate_work_entries" {:month "2025-02" :entries entries})) => totals
               (let [saved (call client "upsert_work_entries" cmd)]
                 (:totals saved) => totals
                 (call client "upsert_work_entries" cmd) => saved
                 (mapv :paid_minutes (get-in saved [:allocation :days 0 :assignments])) => (if (= cap 480) [288 192] [180 120]))
               ;; Same stable ID corrects, then restores the guided example. The
               ;; old receipt stays exact despite subsequent revisions.
               (:revision (call client "upsert_work_entries" (assoc cmd :request_id "correct" :expected_revision 1
                                                                    :entries [(assoc (first entries) :minutes 300)]))) => 2
               (:revision (call client "upsert_work_entries" (assoc cmd :request_id "restore" :expected_revision 2))) => 3
               (:revision (call client "upsert_work_entries" cmd)) => 1
               (let [review (call client "preview_month" {:month "2025-02"})]
                 (:totals review) => totals
                 (.exists (io/file (:budgets env) (:filename review))) => false
                 (when (= cap 480)
                   (with-redefs [archive/push-commit! (fn [& _] (throw (ex-info "Test-only rejected push" {})))]
                     (browser-confirm! origin review owner) => {:review-status 200 :csrf-status 403 :confirm-status 200 :pushed? false})
                   (let [failed (call client "get_publication_status" {:month "2025-02"})]
                     (:state failed) => "failed"
                     (:push_state failed) => "failed"
                     (:published_revision failed) => 3
                     (:commit failed) => (publication/git (:budgets env) "rev-parse" "HEAD"))
                   (publication/count-commits env) => 2)
                 ;; Retry the same approved artifact; there is still only one
                 ;; workbook commit, and the bare remote receives that commit.
                 (browser-confirm! origin review owner) => {:review-status 200 :csrf-status 403 :confirm-status 200 :pushed? true}
                 (let [status (call client "get_publication_status" {:month "2025-02"})]
                   (:state status) => "pushed"
                   (:push_state status) => "pushed"
                   (:published_revision status) => 3
                   (:commit status) => (publication/git (:remote env) "rev-parse" "master"))
                 (let [observed (report env review)]
                   (:paid-hours observed) => (roughly (/ cap 60.0) workbook/hours-tolerance)
                   (:vol-hours observed) => (roughly (/ (- 600 cap) 60.0) workbook/hours-tolerance)
                   (:paid-costs observed) => (if (= cap 480) [192.0 96.0] [120.0 60.0])
                   (:vol-costs observed) => [0 0]))))
           ;; Owner-scoped stable IDs/request IDs never mix the two ledgers.
           (count (:records (call a "get_month" {:month "2025-02"}))) => 2
           (count (:records (call m "get_month" {:month "2025-02"}))) => 2
           (let [entries (mapv (fn [i p] {:external_id (str "overflow-" i) :date (format "2025-03-%02d" (inc i))
                                         :project_id (str p) :minutes 60 :note "Retain me"}) (range 8) "ABCDEFGH")
                 saved (call a "upsert_work_entries" {:month "2025-03" :request_id "overflow" :expected_revision 0 :entries entries})]
             (get-in saved [:capacity :required_columns]) => 8
             (get-in (call a "preview_month" {:month "2025-03"}) [:error :code]) => "column_overflow"
             (count (:records (call a "get_month" {:month "2025-03"}))) => 8
             (:records (call m "get_month" {:month "2025-03"})) => [])
           (publication/count-commits env) => 3)
         (finally (.close a) (.close m) (.close (:sdk server)) (.stop web)))))))

(defn -main [& _]
  (require '[midje.repl :as midje])
  (let [facts ((resolve 'midje.repl/fetch-facts) 'agiladmin.mcp-acceptance-test)
        ok? (and (seq facts) (every? true? (map (resolve 'midje.repl/check-one-fact) facts)))]
    (shutdown-agents)
    (when-not ok? (throw (ex-info "MCP acceptance failed" {})))
    (println "MCP acceptance:" (count facts) "scenario passed (two owners, recovery, Excel reports, overflow).")))
