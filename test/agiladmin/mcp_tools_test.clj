(ns agiladmin.mcp-tools-test
  (:use midje.sweet)
  (:require [agiladmin.auth.core :as auth] [agiladmin.core :as core]
            [agiladmin.mcp.credentials :as credentials] [agiladmin.mcp.http :as http]
            [agiladmin.mcp.runtime :as runtime] [agiladmin.mcp.tools :as tools]
            [agiladmin.mcp.schemas :as schemas] [agiladmin.mcp-http-test :as transport]
            [agiladmin.work-preview :as preview] [agiladmin.work-service :as service]
            [agiladmin.work-ports :as ports] [agiladmin.work-workbook :as workbook]
            [cheshire.core :as json] [clojure.java.io :as io] [clojure.string :as str]
            [ring.adapter.jetty :as jetty] [failjure.core :as f])
  (:import [java.nio.file Files Path] [java.time Duration]
           [java.util.function Consumer] [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$Builder HttpResponse$BodyHandlers]
           [io.modelcontextprotocol.client McpClient]
           [io.modelcontextprotocol.client.transport HttpClientStreamableHttpTransport]
           [io.modelcontextprotocol.json.schema.jackson2 DefaultJsonSchemaValidator]
           [io.modelcontextprotocol.spec McpSchema$CallToolRequest McpSchema$ReadResourceRequest McpSchema$GetPromptRequest]
           [org.apache.poi.xssf.usermodel XSSFWorkbook]
           [java.io ByteArrayInputStream]))

(def entry {:external_id "design" :date "2024-02-29" :minutes 360 :project_id "A" :note "=literal 😀"})
(def second-entry {:external_id "delivery" :date "2024-02-29" :minutes 240 :project_id "B"})
(defn fixture [run]
  (let [data (transport/temp-dir) budgets (transport/temp-dir)
        catalog (atom (into (sorted-map) (for [id (map str "ABCDEFGH")]
                                          [(keyword id) {:text (str "Project " id) :rate "private-payroll-value"
                                                         :persons ["private-personnel-value"]
                                                         :tasks (if (= id "A") [{:id "DEV" :text "Development" :cost "private-task-value"}] [])}])))
        config (atom {:agiladmin {:budgets {:path budgets}
                                 :webserver {:base-host "https://app.test" :base-path "/payroll"}
                                 :mcp {:enabled true :data-path data :organization-aliases {"studio" ["A"] "shared" ["A" "B"]}
                                       :person-cap-overrides {"private-other-account" 660}}}})]
    (with-redefs [auth/backend (atom {:active-accounts (constantly transport/accounts)})
                  core/load-all-projects (fn [_] @catalog)]
      (let [r (runtime/start! @config #(deref config))
            store (credentials/store data)
            a (credentials/provision! store "admin-id" (transport/expiry))
            m (credentials/provision! store "manager-id" (transport/expiry))]
        (try (run {:runtime r :deps (:deps r) :owner (credentials/resolve-owner "admin-id")
                   :other (credentials/resolve-owner "manager-id") :admin a :manager m
                   :data data :budgets budgets :config config :catalog catalog})
             (finally (runtime/stop! r)))))))
(defn invoke
  ([env tool args] (invoke env (:owner env) tool args))
  ([env owner tool args]
   (tools/result (tools/dispatch (:deps env) owner tool args))))
(defn content [result] (json/parse-string (json/generate-string (.structuredContent result)) true))
(defn code [result] (get-in (content result) [:error :code]))
(defn valid-output? [tool result]
  (.valid (.validate (DefaultJsonSchemaValidator.) (json/parse-string (json/generate-string (schemas/output tool)))
                     (.structuredContent result))))
(defn command [request revision entries] {:month "2024-02" :request_id request :expected_revision revision :entries entries})
(defn save [env request revision entries] (invoke env "upsert_work_entries" (command request revision entries)))
(defn validation [env entries] (invoke env "validate_work_entries" {:month "2024-02" :entries entries}))
(defn draft [env] (content (invoke env "get_month" {:month "2024-02"})))
(defn preview-month [env] (invoke env "preview_month" {:month "2024-02"}))

(fact "Seven advertised contracts include valid examples, accurate annotations and compatible structured/text outputs"
  (fixture
   (fn [env]
     (let [specs (tools/specifications (:deps env))
           results {"get_work_context" (invoke env "get_work_context" {})
                    "validate_work_entries" (validation env [entry])
                    "upsert_work_entries" (save env "schema-save" 0 [entry])
                    "get_month" (invoke env "get_month" {:month "2024-02"})
                    "preview_month" (preview-month env)
                    "get_publication_status" (invoke env "get_publication_status" {:month "2024-02"})
                    "delete_work_entries" (invoke env "delete_work_entries" {:month "2024-02" :request_id "schema-delete" :expected_revision 1 :ids ["design"]})}]
       (mapv #(.name (:tool %)) specs) => tools/tool-names
       (doseq [{:keys [tool]} specs]
         (let [name (.name tool) result (get results name)
               example (second (re-find #"Example: (.+?)\. Workflow:" (.description tool)))]
           (.additionalProperties (.inputSchema tool)) => false
           (.valid (.validate (DefaultJsonSchemaValidator.)
                              (json/parse-string (json/generate-string (schemas/input name))) (json/parse-string example))) => true
           (.isError result) => false
           (valid-output? name result) => true
           (json/parse-string (.text (first (.content result)))) => (.structuredContent result)
           (str/includes? (.description tool) "browser") => true))
       (.readOnlyHint (.annotations (:tool (nth specs 1)))) => true
       (.idempotentHint (.annotations (:tool (nth specs 2)))) => true
       (.destructiveHint (.annotations (:tool (nth specs 2)))) => true
       (.destructiveHint (.annotations (:tool (nth specs 3)))) => true
       (.readOnlyHint (.annotations (:tool (nth specs 5)))) => false
       (.idempotentHint (.annotations (:tool (nth specs 5)))) => false))))

(fact "Catalog pages bind to owner/catalog and omit rates, personnel, policy overrides and filesystem paths"
  (fixture
   (fn [env]
     (let [first-page (content (invoke env "get_work_context" {:limit 2}))
           pages (loop [page first-page items []]
                   (let [all (into items (:catalog page))]
                     (if-let [cursor (:next_cursor page)]
                       (recur (content (invoke env "get_work_context" {:limit 2 :cursor cursor})) all) all)))
           payload (json/generate-string first-page)]
       (count (:catalog first-page)) => 2
       (count pages) => 11
       (set (map :kind pages)) => #{"project" "task" "organization"}
       (:paid_cap_minutes first-page) => 480
       (every? #(not (str/includes? payload %)) ["private-" (:data env) (:budgets env) "admin-id" "manager-id"]) => true
       (code (invoke env (:other env) "get_work_context" {:limit 2 :cursor (:next_cursor first-page)})) => "stale_cursor"
       (swap! (:catalog env) dissoc :H)
       (code (invoke env "get_work_context" {:limit 2 :cursor (:next_cursor first-page)})) => "stale_cursor"))))

(fact "Validation is side-effect-free and recalculates proposed complete days with canonical repair guidance"
  (fixture
   (fn [env]
     (save env "existing" 0 [entry])
     (let [ledger (:ledger (:runtime env)) before (ports/read-year ledger "admin-id" "2024")
           validated (validation env [second-entry])]
       (.isError validated) => false
       (:totals (content validated)) => {:recorded_minutes 600 :paid_minutes 480 :vol_minutes 120}
       (mapv :paid_minutes (get-in (content validated) [:allocation :days 0 :assignments])) => [288 192]
       (ports/read-year ledger "admin-id" "2024") => before
       (seq (.listFiles (io/file (:data env) "previews"))) => nil
       (let [ambiguous (validation env [(-> second-entry (dissoc :project_id) (assoc :organization " SHARED "))])]
         (code ambiguous) => "ambiguous_organization"
         (get-in (content ambiguous) [:error :field]) => ["entries" 0 "organization"]
         (get-in (content ambiguous) [:error :candidates]) => ["A" "B"]
         (valid-output? "validate_work_entries" ambiguous) => true)
       (let [date (validation env [(assoc second-entry :date "2023-02-29")])
             task (validation env [(assoc entry :task_id "MISSING")])]
         (code date) => "invalid_date"
         (get-in (content date) [:error :field]) => ["entries" 0 "date"]
         (code task) => "unknown_task"
         (get-in (content task) [:error :field]) => ["entries" 0 "task_id"])
       (code (validation env [(assoc entry :minutes 0)])) => "invalid_input"
       (code (validation env [(assoc entry :note (apply str (repeat 241 "😀")))])) => "invalid_input"
       (.isError (validation env [(assoc entry :note (apply str (repeat 240 "😀")))])) => false
       (get-in (content (validation env [(assoc entry :minutes "60")])) [:error :field]) => ["entries" 0 "minutes"]
       (get-in (content (invoke env "upsert_work_entries" (assoc (command "spoof" 1 [entry]) :person "Someone Else"))) [:error :field]) => ["person"]
       (code (validation env [(assoc entry :date "2025-01-01")])) => "invalid_batch"
       (code (invoke env "validate_work_entries" {:month "2025-01" :entries [(assoc entry :date "2025-01-01")]})) => "cross_month_id"
       (code (invoke env "get_publication_status" {:month "2024-99"})) => "invalid_month"
       (ports/read-year ledger "admin-id" "2024") => before))))

(fact "Retries remain exact after correction, changed request IDs/revisions repair safely and month pagination stays consistent"
  (fixture
   (fn [env]
     (let [first-result (save env "initial" 0 [entry second-entry])
           first-page (content (invoke env "get_month" {:month "2024-02" :limit 1}))]
       (:revision (content first-result)) => 1
       (:revision (content (save env "correction" 1 [(assoc entry :minutes 120)]))) => 2
       (content (save env "initial" 0 [entry second-entry])) => (content first-result)
       (code (save env "initial" 0 [(assoc entry :minutes 60)])) => "request_id_conflict"
       (get-in (content (save env "stale" 0 [entry])) [:error :field]) => ["expected_revision"]
       (code (invoke env "get_month" {:month "2024-02" :limit 1 :cursor (:next_cursor first-page)})) => "stale_cursor"
       (:totals (draft env)) => {:recorded_minutes 360 :paid_minutes 360 :vol_minutes 0}
       (let [deleted (invoke env "delete_work_entries" {:month "2024-02" :request_id "remove" :expected_revision 2 :ids ["delivery"]})]
         (:deleted_ids (content deleted)) => ["delivery"]
         (:revision (content deleted)) => 3
         (valid-output? "delete_work_entries" deleted) => true
         (get-in (ports/read-year (:ledger (:runtime env)) "admin-id" "2024") [:audit 2 :tombstones]) => ["delivery"])
       (code (invoke env (:other env) "delete_work_entries" {:month "2024-02" :request_id "other" :expected_revision 0 :ids ["design"]})) => "record_not_found"
       (save env "duplicate" 3 [(assoc entry :external_id "another-id" :minutes 120)])
       (:possible_duplicate_ids (draft env)) => [["another-id" "design"]]))))

(fact "Eight monthly columns retain the full draft and typed export repair; no official or artifact file is created"
  (fixture
   (fn [env]
     (let [entries (mapv #(assoc entry :external_id % :project_id % :minutes 30 :note "") (map str "ABCDEFGH"))
           validated (validation env entries) saved (save env "overflow" 0 entries)
           blocked (preview-month env)]
       (.isError validated) => false
       (get-in (content validated) [:capacity :required_columns]) => 8
       (get-in (content validated) [:warnings 0 :code]) => "column_overflow"
       (.isError saved) => false
       (count (:records (draft env))) => 8
       (.isError blocked) => true
       (code blocked) => "column_overflow"
       (valid-output? "preview_month" blocked) => true
       (seq (.listFiles (io/file (:budgets env)))) => nil
       (seq (.listFiles (io/file (:data env) "previews"))) => nil))))

(fact "Immutable previews survive restart, preserve literal notes and bind owner/revision/policy/source without official writes"
  (fixture
   (fn [env]
     (save env "preview" 0 [entry second-entry])
     (let [r (:runtime env) result (preview-month env) public (content result) id (:preview_id public)
           retained (preview/read-preview (:preview-store r) (:owner env) id)
           reopened (preview/read-preview (preview/store (:data env)) (:owner env) id)
           artifact (preview/artifact (:preview-store r) (:owner env) id)]
       (.isError result) => false
       retained => reopened
       (:digest public) => (:preview-digest retained)
       (:source_fingerprint public) => "absent"
       (:owner_review_url public) => (str "https://app.test/payroll/work/review/" id)
       (:artifact_url public) => (str "https://app.test/payroll/mcp/artifacts/" id)
       (:record_count public) => 2
       (some #(and (= "I39" (:cell %)) (str/includes? (:after %) "=literal 😀")) (get-in public [:exact_changes :cells])) => true
       (valid-output? "preview_month" result) => true
       (preview/verify-preview (:ledger r) (:workbook r) (:owner env) (tools/settings (:deps env)) retained) => retained
       (:code (preview/artifact (:preview-store r) (:other env) id)) => :preview-not-found
       (:code (preview/read-preview (:preview-store r) (:owner env) "../../outside")) => :preview-not-found
       (seq (.listFiles (io/file (:budgets env)))) => nil
       (with-open [wb (XSSFWorkbook. (ByteArrayInputStream. (:bytes artifact)))]
         (.getStringCellValue (.getCell (.getRow (.getSheet wb "2024-2") 38) 8)) => "[A] =literal 😀")
       (swap! (:config env) assoc-in [:agiladmin :mcp :paid-cap-minutes] 300)
       (:code (preview/verify-preview (:ledger r) (:workbook r) (:owner env) (tools/settings (:deps env)) retained)) => :stale-snapshot
       (save env "changed" 1 [(assoc entry :minutes 120)])
       (:code (preview/verify-preview (:ledger r) (:workbook r) (:owner env) (tools/settings (:deps env)) retained)) => :stale-snapshot
       (:artifact-fingerprint (preview/read-preview (:preview-store r) (:owner env) id)) => (:artifact-fingerprint retained)
       (let [download ((:artifact-handler r) (assoc (transport/request (:token (:admin env)) {}) :request-method :get) id)
             cross ((:artifact-handler r) (assoc (transport/request (:token (:manager env)) {}) :request-method :get) id)]
         (:status download) => 200
         (:status cross) => 404
         (get-in download [:headers "Cache-Control"]) => "private, no-store")
       (Files/write (.resolve (.resolve ^Path (:root (:preview-store r)) id) "workbook.xlsx")
                    (.getBytes "corrupt" "UTF-8") (make-array java.nio.file.OpenOption 0))
       (f/failed? (preview/artifact (:preview-store r) (:owner env) id)) => true))))

(fact "Trusted managed baselines allow exact cell diffs; unmanaged months, expired previews and symlinks fail without overwrite"
  (fixture
   (fn [env]
     (save env "initial" 0 [entry])
     (let [r (:runtime env) p1 (content (preview-month env))
           old (preview/read-preview (:preview-store r) (:owner env) (:preview_id p1))
           artifact (preview/artifact (:preview-store r) (:owner env) (:preview_id p1))
           official (.toPath (io/file (:budgets env) (:filename artifact)))]
       (Files/write official (:bytes artifact) (make-array java.nio.file.OpenOption 0))
       (code (preview-month env)) => "existing_month_unmanaged"
       (runtime/install-publication! r {:read-baseline (fn [_ _] (:baseline old)) :publication-status (fn [_ _] {:state "pushed"})}) => true
       (save env "correction" 1 [(assoc entry :minutes 120)])
       (let [p2 (preview-month env) value (preview/read-preview (:preview-store r) (:owner env) (:preview_id (content p2)))]
         (.isError p2) => false
         (some #(= "B39" (:cell %)) (get-in (content p2) [:exact_changes :cells])) => true
         (:artifact-fingerprint old) => (workbook/fingerprint (Files/readAllBytes official))
         (:code (preview/verify-preview (:ledger r) (:workbook r) (:owner env) (tools/settings (:deps env))
                                        (assoc value :expires-at "2000-01-01T00:00:00Z"))) => :preview-expired)
       (Files/delete official)
       (Files/createSymbolicLink official (.toPath (io/file (:data env) "credentials" "tokens.edn"))
                                 (make-array java.nio.file.attribute.FileAttribute 0))
       (code (preview-month env)) => "workbook_unavailable"))))

(fact "Publication discovery filters private recovery internals and binds status reads to the authenticated owner"
  (fixture
   (fn [env]
     (let [seen (atom []) r (:runtime env)]
       (runtime/install-publication! r
         {:read-baseline (fn [_ _] nil)
          :publication-status (fn [owner month]
                                (swap! seen conj [(:owner-id owner) month])
                                {:state "failed" :published-revision 1 :commit "01234567" :push-state "failed"
                                 :next-action "Operator retries the retained commit without another commit."
                                 :path "/private/path" :secret "private-secret" :audit ["private-history"]})})
       (let [status (invoke env "get_publication_status" {:month "2024-02"}) payload (json/generate-string (content status))]
         (:state (content status)) => "failed"
         (:published_revision (content status)) => 1
         (valid-output? "get_publication_status" status) => true
         (str/includes? payload "private-") => false
         @seen => [["admin-id" "2024-02"]])))))

(defn sdk-workflow [env]
  (let [delegate (atom (fn [_] {:status 503 :body ""}))
        web (jetty/run-jetty #(@delegate %) {:host "127.0.0.1" :port 0 :join? false})
        port (.getLocalPort (first (.getConnectors web))) base (str "http://127.0.0.1:" port)
        server (http/server {:credential-store (credentials/store (:data env)) :expected-origin base
                             :expected-authority (str "127.0.0.1:" port) :tools (tools/specifications (:deps env))
                             :resources (tools/resources) :prompts (tools/prompts) :instructions tools/workflow})
        downloader (runtime/download-handler server (:preview-store (:runtime env)))
        _ (reset! delegate (fn [req]
                             (let [uri (str/replace-first (:uri req) #"^/payroll" "")]
                               (if (= uri "/mcp") ((:handler server) (assoc req :uri uri))
                                   (if-let [[_ id] (re-matches #"/mcp/artifacts/([^/]+)" uri)]
                                     (downloader req id) {:status 404 :body ""})))))
        client (-> (McpClient/sync (-> (HttpClientStreamableHttpTransport/builder base)
                                       (.endpoint "/payroll/mcp") (.supportedProtocolVersions [http/protocol-version])
                                       (.openConnectionOnStartup false)
                                       (.customizeRequest (reify Consumer (accept [_ builder]
                                                                            (.header ^HttpRequest$Builder builder "Authorization" (str "Bearer " (:token (:admin env)))))))
                                       (.build))) (.requestTimeout (Duration/ofSeconds 15)) (.build))
        call (fn [name args] (.callTool client (McpSchema$CallToolRequest. name (json/parse-string (json/generate-string args)))))]
    (try
      (.initialize client)
      (let [listed (.tools (.listTools client))
            context (call "get_work_context" {:limit 200})
            validated (call "validate_work_entries" {:month "2024-02" :entries [entry second-entry]})
            saved (call "upsert_work_entries" (command "sdk-save" 0 [entry second-entry]))
            retried (call "upsert_work_entries" (command "sdk-save" 0 [entry second-entry]))
            month (call "get_month" {:month "2024-02" :limit 1})
            result (call "preview_month" {:month "2024-02"}) data (content result)
            path (.getPath (URI/create (:artifact_url data)))
            request (-> (HttpRequest/newBuilder (URI/create (str base path)))
                        (.header "Authorization" (str "Bearer " (:token (:admin env)))) (.GET) (.build))
            download (.send (HttpClient/newHttpClient) request (HttpResponse$BodyHandlers/ofByteArray))
            status (call "get_publication_status" {:month "2024-02"})
            resource (.readResource client (McpSchema$ReadResourceRequest. tools/workflow-uri))
            prompt (.getPrompt client (McpSchema$GetPromptRequest. "prepare_month" {}))]
        {:tools (mapv #(.name %) listed) :context context :validated validated :saved saved :retried retried
         :month month :preview result :download-status (.statusCode download) :download-bytes (alength ^bytes (.body download))
         :status status :resources (mapv #(.uri %) (.resources (.listResources client)))
         :resource-text (.text (first (.contents resource)))
         :prompts (mapv #(.name %) (.prompts (.listPrompts client)))
         :prompt-text (.text (.content (first (.messages prompt))))})
      (finally (.close client) (.close (:sdk server)) (.stop web)))))

(fact "A pinned SDK client discovers tools-only guidance, validates/saves/retries/previews/downloads and reads resource/prompt/status"
  (fixture
   (fn [env]
     (let [observed (sdk-workflow env)]
       (:tools observed) => tools/tool-names
       (str/includes? (:workflow (content (:context observed))) "owner_review_url") => true
       (:totals (content (:validated observed))) => {:recorded_minutes 600 :paid_minutes 480 :vol_minutes 120}
       (content (:saved observed)) => (content (:retried observed))
       (:next_cursor (content (:month observed))) => map?
       (.isError (:preview observed)) => false
       (valid-output? "preview_month" (:preview observed)) => true
       (:download-status observed) => 200
       (> (:download-bytes observed) 1000) => true
       (:state (content (:status observed))) => "draft"
       (:resources observed) => [tools/workflow-uri]
       (str/includes? (:resource-text observed) "1440") => true
       (:prompts observed) => ["prepare_month"]
       (str/includes? (:prompt-text observed) "No MCP tool can confirm") => true
       (seq (.listFiles (io/file (:budgets env)))) => nil))))
