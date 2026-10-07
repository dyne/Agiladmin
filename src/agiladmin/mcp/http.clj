(ns agiladmin.mcp.http
  "Stateless SDK transport on the existing Ring server. No session or browser auth."
  (:require [agiladmin.mcp.credentials :as credentials]
            [clojure.string :as str]
            [cheshire.core :as json])
  (:import [com.fasterxml.jackson.databind ObjectMapper]
           [com.fasterxml.jackson.core JsonParser$Feature]
           [io.modelcontextprotocol.json.jackson2 JacksonMcpJsonMapper]
           [io.modelcontextprotocol.spec McpStatelessServerTransport McpSchema McpError
            McpSchema$JSONRPCRequest McpSchema$JSONRPCNotification McpSchema$ServerCapabilities]
           [io.modelcontextprotocol.common McpTransportContext]
           [io.modelcontextprotocol.server McpServer]
           [reactor.core.publisher Mono]
           [java.time Duration]
           [java.io InputStream ByteArrayOutputStream]
           [java.util.function BiFunction]))

(def protocol-version "2025-11-25")
(def max-body-bytes 262144)
(def requests-per-minute 60)
(defn response [status body]
  {:status status :headers {"Content-Type" "application/json" "Cache-Control" "no-store"}
   :body (if (string? body) body (json/generate-string body))})
(defn- rpc-error [status id code message]
  (response status {:jsonrpc "2.0" :id id :error {:code code :message message}}))
(defn- read-body [body]
  (when-not (instance? InputStream body) (throw (ex-info "Invalid body" {:status 400})))
  (let [buf (byte-array 4096) out (ByteArrayOutputStream.)]
    (loop [total 0]
      (let [n (.read ^InputStream body buf)]
        (if (neg? n) (.toString out "UTF-8")
          (do (when (> (+ total n) max-body-bytes) (throw (ex-info "Request too large" {:status 413})))
              (.write out buf 0 n) (recur (+ total n))))))))
(defn- allow-rate! [buckets key]
  (locking buckets
    (let [now (System/currentTimeMillis)
          current (into {} (filter (fn [[_ v]] (< (- now (:start v)) 60000)) @buckets))
          entry (get current key {:start now :count 0})
          allowed (and (< (:count entry) requests-per-minute)
                       (or (contains? current key) (< (count current) 1024)))]
      (reset! buckets (if allowed (assoc current key (update entry :count inc)) current))
      allowed)))
(defn server
  "Compose once; tools receive only server-resolved owner context. expected-origin
  and authority come from validated server configuration, never forwarded headers.
  Tests may supply an isolated owner resolver and local authority."
  [{:keys [credential-store expected-origin expected-authority tools resources prompts instructions
           authenticate] :or {authenticate credentials/authenticate}}]
  (let [mapper (JacksonMcpJsonMapper. (doto (ObjectMapper.)
                                      (.configure JsonParser$Feature/STRICT_DUPLICATE_DETECTION true)))
        sdk-handler (atom nil)
        buckets (atom {}) owner-buckets (atom {})
        authorize (fn [request]
                    (let [headers (:headers request)
                          trusted? (and (= expected-authority (str/lower-case (get headers "host" "")))
                                        (or (nil? (get headers "origin")) (= expected-origin (get headers "origin"))))
                          ip-allowed? (and trusted? (allow-rate! buckets (or (:remote-addr request) "unknown")))
                          owner (when ip-allowed?
                                  (when-let [[_ token] (re-matches #"Bearer (agm_[A-Za-z0-9_-]{43})" (get headers "authorization" ""))]
                                    (authenticate credential-store token)))]
                      (cond
                        (not trusted?) (response 403 {:error "Untrusted Host or Origin"})
                        (not ip-allowed?) (assoc-in (response 429 {:error "Request limit exceeded"}) [:headers "Retry-After"] "60")
                        (nil? owner) (assoc-in (response 401 {:error "Owner credential required"}) [:headers "WWW-Authenticate"] "Bearer")
                        (not (allow-rate! owner-buckets (:owner-id owner)))
                        (assoc-in (response 429 {:error "Request limit exceeded"}) [:headers "Retry-After"] "60")
                        :else owner)))
        transport (reify McpStatelessServerTransport
                    (setMcpHandler [_ handler] (reset! sdk-handler handler))
                    (protocolVersions [_] [protocol-version])
                    (closeGracefully [_] (Mono/empty)))
        builder (-> (McpServer/sync ^McpStatelessServerTransport transport)
                    (.serverInfo "agiladmin-work" "1") (.jsonMapper mapper)
                    (.instructions (or instructions "Discover get_work_context before recording work. Only your own drafts are accessible. Browser owner confirmation is required."))
                    (.capabilities (cond-> (-> (McpSchema$ServerCapabilities/builder) (.tools false))
                                     (seq resources) (.resources false false)
                                     (seq prompts) (.prompts false)
                                     true (.build))))]
    (doseq [{:keys [tool call]} tools]
      (.toolCall builder tool
                 (reify BiFunction
                   (apply [_ context request] (call (.get ^McpTransportContext context "owner") request)))))
    (when (seq resources) (.resources builder ^java.util.List resources))
    (when (seq prompts) (.prompts builder ^java.util.List prompts))
    (let [sdk (.build builder)]
      {:sdk sdk :authorize-request authorize
       :handler
       (fn [request]
         (let [headers (:headers request) owner (authorize request)]
           (cond
             (:status owner) owner
             (not= :post (:request-method request))
             (assoc-in (response 405 {:error "Use JSON POST"}) [:headers "Allow"] "POST")
             (not= "application/json" (-> (get headers "content-type" "") str/lower-case (str/split #";") first str/trim))
             (response 415 {:error "Content-Type must be application/json"})
             (not (and (str/includes? (get headers "accept" "") "application/json")
                       (str/includes? (get headers "accept" "") "text/event-stream")))
             (response 406 {:error "Accept must include application/json and text/event-stream"})
             (and (get headers "mcp-protocol-version") (not= protocol-version (get headers "mcp-protocol-version")))
             (rpc-error 400 nil -32600 "Unsupported protocol version")
             :else
             (try
               (let [raw (read-body (:body request))
                     parsed (try (json/parse-string raw true) (catch Exception _ ::malformed))]
                 (cond
                   (= ::malformed parsed) (rpc-error 400 nil -32700 "Parse error")
                   (not (and (map? parsed) (= "2.0" (:jsonrpc parsed)) (string? (:method parsed))
                             (or (not (contains? parsed :id)) (string? (:id parsed)) (number? (:id parsed)))))
                   (rpc-error 400 nil -32600 "Invalid Request")
                   (or (and (contains? parsed :params) (not (map? (:params parsed))))
                       (and (= "tools/call" (:method parsed))
                            (or (not (string? (get-in parsed [:params :name])))
                                (and (contains? (:params parsed) :arguments) (not (map? (get-in parsed [:params :arguments])))))))
                   (rpc-error 200 (:id parsed) -32602 "Invalid params")
                   (and (not= "initialize" (:method parsed))
                        (nil? (get headers "mcp-protocol-version")))
                   (rpc-error 400 (:id parsed) -32600 "MCP-Protocol-Version required after initialize")
                   :else
                   (let [message (try (McpSchema/deserializeJsonRpcMessage mapper raw)
                                      (catch Exception _ ::invalid))
                         context (McpTransportContext/create {"owner" owner})]
                     (cond
                       (= ::invalid message) (rpc-error 400 (:id parsed) -32600 "Invalid Request")
                       (instance? McpSchema$JSONRPCRequest message)
                       (try
                         (response 200 (.writeValueAsString mapper
                                        (.block (.handleRequest @sdk-handler context message) (Duration/ofSeconds 15))))
                         (catch Exception ex
                           (let [cause (McpError/findRootCause ex)
                                 code (if (instance? McpError cause) (.code (.getJsonRpcError ^McpError cause)) -32603)]
                             (rpc-error 200 (:id parsed) code
                                        (get {-32601 "Method not found" -32602 "Invalid params" -32603 "Internal error"}
                                             code "Protocol request refused")))))
                       (instance? McpSchema$JSONRPCNotification message)
                       (do (.block (.handleNotification @sdk-handler context message) (Duration/ofSeconds 15))
                           (response 202 ""))
                       :else (rpc-error 400 nil -32600 "Invalid Request")))))
               (catch Exception ex
                 ;; Never log request bodies, credentials, auth errors or SDK exceptions.
                 (if (= 413 (:status (ex-data ex))) (response 413 {:error "Request too large"})
                     (rpc-error 400 nil -32600 "Request could not be processed")))))))})))
