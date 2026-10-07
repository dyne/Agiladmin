(ns agiladmin.mcp-http-test
  (:use midje.sweet)
  (:require [agiladmin.auth.core :as auth]
            [agiladmin.auth.dev :as dev]
            [agiladmin.auth.pocketbase :as pb]
            [clj-http.client :as client-http]
            [agiladmin.mcp.credentials :as credentials]
            [agiladmin.mcp.http :as http]
            [agiladmin.mcp.tools :as tools]
            [agiladmin.mcp.runtime :as runtime]
            [agiladmin.work-ledger :as ledger]
            [agiladmin.work-ports :as ports]
            [ring.adapter.jetty :as jetty]
            [cheshire.core :as json]
            [failjure.core :as f])
  (:import [java.nio.file Files]
           [java.time Instant Duration]
           [java.io ByteArrayInputStream]
           [java.util.function Consumer]
           [java.net.http HttpRequest$Builder]
           [com.fasterxml.jackson.databind ObjectMapper]
           [io.modelcontextprotocol.json.jackson2 JacksonMcpJsonMapper]
           [io.modelcontextprotocol.client McpClient]
           [io.modelcontextprotocol.client.transport HttpClientStreamableHttpTransport]
           [io.modelcontextprotocol.spec McpSchema$CallToolRequest]))

(defn temp-dir [] (str (Files/createTempDirectory "mcp-http-" (make-array java.nio.file.attribute.FileAttribute 0))))
(def accounts [{:id "admin-id" :name "Alice Admin" :email "alice@example.test" :role "admin" :verified true}
               {:id "manager-id" :name "Mark Manager" :email "mark@example.test" :role "manager" :verified true}])
(defn expiry [] (str (.plusSeconds (Instant/now) 3600)))
(defn fixture [run]
  (let [directory (temp-dir) active (atom accounts) resolutions (atom 0)]
    (with-redefs [auth/backend (atom {:active-accounts #(do (swap! resolutions inc) @active)})]
      (with-open [l (ledger/open-ledger! {:data-path directory} (str directory "-budgets"))]
        (let [store (credentials/store directory)
              admin (credentials/provision! store "admin-id" (expiry))
              manager (credentials/provision! store "manager-id" (expiry))
              deps {:ledger l :projects (constantly {:A {:tasks []}}) :settings {}}
              adapter (http/server {:credential-store store :expected-origin "https://app.test"
                                    :expected-authority "app.test" :tools (tools/specifications deps)})]
          (try (run {:directory directory :store store :admin admin :manager manager :active active
                     :resolutions resolutions :ledger l :deps deps :adapter adapter})
               (finally (.close (:sdk adapter)))))))))
(defn request
  ([token message] (request token message {}))
  ([token message changes]
   (merge {:uri "/mcp" :request-method :post :remote-addr "127.0.0.1"
           :headers {"host" "app.test" "authorization" (str "Bearer " token)
                     "content-type" "application/json" "accept" "application/json, text/event-stream"
                     "mcp-protocol-version" http/protocol-version}
           :body (ByteArrayInputStream. (.getBytes (if (string? message) message (json/generate-string message)) "UTF-8"))}
          changes)))
(defn rpc [method params] {:jsonrpc "2.0" :id 1 :method method :params params})
(defn call [handler token name args]
  (-> (handler (request token (rpc "tools/call" {:name name :arguments args}))) :body (json/parse-string true)))

(fact "Hash-only credentials expire, revoke, rotate and re-resolve active unique production owners"
  (fixture
   (fn [{:keys [store admin active directory]}]
     (count (:token admin)) => 47
     (:owner-id (credentials/authenticate store (:token admin))) => "admin-id"
     (.contains (slurp (str directory "/credentials/tokens.edn")) (:token admin)) => false
     (get (first (credentials/list-credentials store)) :hash) => nil
     (:code (credentials/revoke! store "missing-credential-id")) => :credential-not-found
     (:owner-id (credentials/authenticate store (:token admin))) => "admin-id"
     (let [rotated (credentials/provision! store "admin-id" (expiry))]
       (:owner-id (credentials/authenticate store (:token rotated))) => "admin-id"
       (credentials/revoke! store (:id admin))
       (credentials/authenticate store (:token admin)) => nil
       (credentials/authenticate store (:token rotated)) => (contains {:owner-id "admin-id"})
       (reset! active [(second accounts)])
       (credentials/authenticate store (:token rotated)) => nil)
     (reset! active (conj accounts {:id "collision" :name "Another Admin" :verified true}))
     (:code (credentials/provision! store "admin-id" (expiry))) => :identity-collision
     (reset! active accounts)
     (let [expired (credentials/provision! store "manager-id" (str (.plusMillis (Instant/now) 50)))]
       (Thread/sleep 80)
       (credentials/authenticate store (:token expired)) => nil)
     (with-redefs [auth/backend (atom (dev/backend))]
       (f/failed? (credentials/provision! store "dev-admin" (expiry))) => true))))

(fact "Transport rejects absent/revoked owners, unsafe origins/hosts, malformed requests and limits"
  (fixture
   (fn [{:keys [adapter admin store active]}]
     (let [handler (:handler adapter) token (:token admin) ping (rpc "ping" {})]
       (:status (handler (request nil ping))) => 401
       (:status (handler (request token ping {:headers {"host" "evil.test"}}))) => 403
       (:status (handler (update (request token ping) :headers assoc "origin" "https://evil.test"))) => 403
       (:status (handler (update (request token ping) :headers assoc "origin" "https://app.test"))) => 200
       (get-in (json/parse-string (:body (handler (request token "{"))) true) [:error :code]) => -32700
       (:status (handler (request token "[]"))) => 400
       (:status (handler (request token "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\",\"method\":\"other\"}"))) => 400
       (:status (handler (update (request token ping) :headers assoc "mcp-protocol-version" "1900-01-01"))) => 400
       (:status (handler (update (request token ping) :headers dissoc "mcp-protocol-version"))) => 400
       (get-in (json/parse-string
                (:body (handler (update (request token (rpc "initialize" {:protocolVersion "1900-01-01" :capabilities {}
                                                                           :clientInfo {:name "probe" :version "1"}}))
                                        :headers dissoc "mcp-protocol-version"))) true)
               [:result :protocolVersion]) => http/protocol-version
       (get-in (json/parse-string (:body (handler (request token (rpc "not_a_method" {})))) true) [:error :code]) => -32601
       (:status (handler (request token ping {:request-method :get}))) => 405
       (:status (handler (request token ping {:request-method :delete}))) => 405
       (:status (handler (update (request token ping) :headers assoc "content-type" "text/plain"))) => 415
       (:status (handler (update (request token ping) :headers assoc "accept" "application/json"))) => 406
       (:status (handler (request token (apply str (repeat (inc http/max-body-bytes) "x"))))) => 413
       (credentials/revoke! store (:id admin))
       (:status (handler (request token ping))) => 401
       (reset! active [])
       (:status (handler (request token ping))) => 401))))

(fact "Source and owner request buckets are bounded and report 429"
  (fixture (fn [{:keys [adapter admin]}]
             (let [statuses (mapv (fn [_] (:status ((:handler adapter) (request (:token admin) (rpc "ping" {})))))
                                  (range (inc http/requests-per-minute)))]
               (last statuses) => 429))))

(defn client-exchange [fixture]
  (let [{:keys [store admin manager deps]} fixture
        delegate (atom (fn [_] {:status 503 :body ""}))
        web (jetty/run-jetty #(@delegate %) {:host "127.0.0.1" :port 0 :join? false})
        port (.getLocalPort (first (.getConnectors web)))
        origin (str "http://127.0.0.1:" port)
        server (http/server {:credential-store store :expected-origin origin
                             :expected-authority (str "127.0.0.1:" port) :tools (tools/specifications deps)})
        ;; A reverse proxy strips /payroll; the Ring endpoint remains /mcp.
        _ (reset! delegate (fn [req] (if (= "/payroll/mcp" (:uri req))
                                     ((:handler server) (assoc req :uri "/mcp")) {:status 404 :body ""})))
        client-for (fn [token]
                     (-> (McpClient/sync
                          (-> (HttpClientStreamableHttpTransport/builder origin)
                              (.endpoint "/payroll/mcp")
                              (.supportedProtocolVersions [http/protocol-version])
                              (.openConnectionOnStartup false)
                              (.customizeRequest (reify Consumer
                                                   (accept [_ builder] (.header ^HttpRequest$Builder builder "Authorization" (str "Bearer " token)))))
                              (.build)))
                         (.requestTimeout (Duration/ofSeconds 5)) (.build)))
        a (client-for (:token admin)) m (client-for (:token manager))]
    (try
      (let [init (.initialize a) _ (.initialize m)
            listed (mapv #(.name %) (.tools (.listTools a)))
            context (.structuredContent (.callTool a (McpSchema$CallToolRequest. "get_work_context" {})))
            saved (.callTool a (McpSchema$CallToolRequest. "upsert_work_entries"
                                 {"month" "2024-02" "expected_revision" 0 "request_id" "client-1"
                                  "entries" [{"external_id" "own" "date" "2024-02-29" "minutes" 60 "project_id" "A"}]}))
            other (.structuredContent (.callTool m (McpSchema$CallToolRequest. "get_month" {"month" "2024-02"})))
            spoof (.callTool a (McpSchema$CallToolRequest. "upsert_work_entries"
                                {"month" "2024-02" "expected_revision" 1 "request_id" "spoof"
                                 "person" "Mark Manager" "entries" []}))
            manager-saved (.callTool m (McpSchema$CallToolRequest. "upsert_work_entries"
                                         {"month" "2024-02" "expected_revision" 0 "request_id" "client-1"
                                          "entries" [{"external_id" "own" "date" "2024-02-29" "minutes" 30 "project_id" "A"}]}))]
        {:version (.protocolVersion init) :tools listed :context context :saved-error (.isError saved)
         :other other :spoof-error (.isError spoof) :manager-error (.isError manager-saved) :ping (.ping a)})
      (finally (.close a) (.close m) (.close (:sdk server)) (.stop web)))))

(fact "Pinned SDK client completes lifecycle and owner-only calls beneath a public base path"
  (fixture (fn [env]
             (let [observed (client-exchange env)]
               (:version observed) => http/protocol-version
               (:tools observed) => tools/tool-names
               (get-in observed [:context "owner" "name"]) => "Alice Admin"
               (:saved-error observed) => false
               (get (:other observed) "records") => []
               (:spoof-error observed) => true
               (:manager-error observed) => false
               (> @(:resolutions env) 5) => true
               (count (:records (ports/read-year (:ledger env) "admin-id" "2024"))) => 1
               (get-in (ports/read-year (:ledger env) "manager-id" "2024") [:records "own" :minutes]) => 30))))

(fact "Production account resolution pages verified PocketBase accounts without reusing browser sessions"
  (let [requests (atom []) config {:base-url "http://unused.test" :users-collection "users"
                                  :superuser-email "test" :superuser-password "not-a-real-secret"}]
    (with-redefs [client-http/request
                  (fn [r]
                    (swap! requests conj r)
                    {:status 200 :body (if (= :post (:method r)) {:token "test-only"}
                                         {:totalPages 2 :items [(nth accounts (dec (get-in r [:query-params "page"]))) ]})})]
      (pb/active-accounts config) => (mapv #(assoc % :other-names []) accounts)
      (mapv #(get-in % [:query-params "filter"]) (filter #(= :get (:method %)) @requests))
      => ["verified = true" "verified = true"])))

(fact "MCP stays disabled by default and rejects dev auth or insecure public configuration"
  (.isInfoEnabled (org.slf4j.LoggerFactory/getLogger "io.modelcontextprotocol.server.McpStatelessAsyncServer")) => false
  (runtime/start! {}) => nil
  (with-redefs [auth/backend (atom (dev/backend))]
    (runtime/start! {:agiladmin {:mcp {:enabled true :data-path (temp-dir)}
                                 :webserver {:base-host "https://app.test"}}})) => (throws Exception)
  (with-redefs [auth/backend (atom {:active-accounts (constantly accounts)})]
    (runtime/start! {:agiladmin {:mcp {:enabled true :data-path (temp-dir)}
                                 :webserver {:base-host "http://app.test"}}})) => (throws Exception))
