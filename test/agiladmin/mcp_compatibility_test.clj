(ns agiladmin.mcp-compatibility-test
  "Executable transport selection probe, never installed as an application route."
  (:use midje.sweet)
  (:require [ring.adapter.jetty :as jetty]
            [cheshire.core :as json])
  (:import [com.fasterxml.jackson.databind ObjectMapper]
           [io.modelcontextprotocol.json.jackson2 JacksonMcpJsonMapper]
           [io.modelcontextprotocol.json.schema.jackson2 DefaultJsonSchemaValidator]
           [io.modelcontextprotocol.spec McpStatelessServerTransport McpSchema
            McpSchema$JSONRPCRequest McpSchema$JSONRPCNotification
            McpSchema$Tool McpSchema$CallToolRequest McpSchema$CallToolResult McpSchema$ServerCapabilities]
           [io.modelcontextprotocol.common McpTransportContext]
           [io.modelcontextprotocol.server McpServer]
           [io.modelcontextprotocol.client McpClient]
           [io.modelcontextprotocol.client.transport HttpClientStreamableHttpTransport]
           [reactor.core.publisher Mono]
           [java.time Duration]
           [java.util.function BiFunction]
           [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers]))

(def protocol "2025-11-25")

(defn exchange
  "Direct SDK stateless handler behind the existing Ring/Jetty adapter; client uses
  real loopback Streamable HTTP. No app initialization, credentials or domain tools."
  []
  (let [mapper (JacksonMcpJsonMapper. (ObjectMapper.))
        sdk-handler (atom nil)
        transport (reify McpStatelessServerTransport
                    (setMcpHandler [_ h] (reset! sdk-handler h))
                    (protocolVersions [_] [protocol])
                    (closeGracefully [_] (Mono/empty)))
        server (-> (McpServer/sync ^McpStatelessServerTransport transport)
                   (.serverInfo "agiladmin-compatibility-probe" "1")
                   (.jsonMapper mapper)
                   (.capabilities (-> (McpSchema$ServerCapabilities/builder) (.tools false) (.build)))
                   (.toolCall (-> (McpSchema$Tool/builder)
                                  (.name "compatibility_echo")
                                  (.description "Test-only echo; never exposed by Agiladmin.")
                                  (.inputSchema mapper "{\"type\":\"object\",\"properties\":{}}")
                                  (.outputSchema mapper "{\"type\":\"object\",\"properties\":{\"ok\":{\"type\":\"boolean\"}},\"required\":[\"ok\"]}")
                                  (.build))
                              (reify BiFunction
                                (apply [_ _ _]
                                  (-> (McpSchema$CallToolResult/builder)
                                      (.structuredContent {"ok" true}) (.build)))))
                   (.build))
        handler (fn [request]
                  (if (and (= "/mcp" (:uri request)) (= :post (:request-method request)))
                    (let [message (McpSchema/deserializeJsonRpcMessage mapper (slurp (:body request)))]
                      (cond
                        (instance? McpSchema$JSONRPCRequest message)
                        {:status 200 :headers {"Content-Type" "application/json"}
                         :body (.writeValueAsString mapper
                                                    (.block (.handleRequest @sdk-handler McpTransportContext/EMPTY message)))}
                        (instance? McpSchema$JSONRPCNotification message)
                        (do (.block (.handleNotification @sdk-handler McpTransportContext/EMPTY message))
                            {:status 202 :body ""})
                        :else {:status 400 :body ""}))
                    {:status 405 :body ""}))
        http-server (jetty/run-jetty handler {:host "127.0.0.1" :port 0 :join? false})
        port (.getLocalPort (first (.getConnectors http-server)))
        base (str "http://127.0.0.1:" port)
        client-transport (-> (HttpClientStreamableHttpTransport/builder base)
                             (.endpoint "/mcp")
                             (.jsonMapper mapper)
                             (.supportedProtocolVersions [protocol])
                             (.openConnectionOnStartup false)
                             (.build))
        client (-> (McpClient/sync client-transport)
                   (.requestTimeout (Duration/ofSeconds 5)) (.build))]
    (try
      (let [initialized (.initialize client)
            ;; Use the actual SDK client lifecycle and a second raw remote client
            ;; to verify an unsupported requested version negotiates our sole version.
            request (-> (HttpRequest/newBuilder (URI/create (str base "/mcp")))
                        (.header "Content-Type" "application/json")
                        (.header "Accept" "application/json, text/event-stream")
                        (.POST (HttpRequest$BodyPublishers/ofString
                                (json/generate-string
                                 {:jsonrpc "2.0" :id 99 :method "initialize"
                                  :params {:protocolVersion "1900-01-01" :capabilities {}
                                           :clientInfo {:name "negotiation-probe" :version "1"}}})))
                        (.build))
            response (.send (HttpClient/newHttpClient) request (HttpResponse$BodyHandlers/ofString))]
        {:protocol (.protocolVersion initialized)
         :server (.name (.serverInfo initialized))
         :ping (.ping client)
         :tools (mapv #(.name %) (.tools (.listTools client)))
         :echo (.structuredContent (.callTool client (McpSchema$CallToolRequest. "compatibility_echo" {})))
         :negotiated (get-in (json/parse-string (.body response) true) [:result :protocolVersion])
         :http-status (.statusCode response)})
      (finally (.close client) (.close server) (.stop http-server)))))

(fact "Pinned official SDK negotiates 2025-11-25 over existing Ring/Jetty HTTP"
  (let [result (exchange)]
    (:protocol result) => protocol
    (:server result) => "agiladmin-compatibility-probe"
    (:tools result) => ["compatibility_echo"]
    (get (:echo result) "ok") => true
    (:negotiated result) => protocol
    (:http-status result) => 200))

(fact "JSON schema validation works without upgrading the legacy YAML parser"
  (let [validator (DefaultJsonSchemaValidator.)
        schema {"type" "object" "properties" {"minutes" {"type" "integer"}}
                "required" ["minutes"]}]
    (.valid (.validate validator schema {"minutes" 60})) => true
    (.valid (.validate validator schema {"minutes" "60"})) => false))
