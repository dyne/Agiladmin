(ns agiladmin.mcp.credential-cli
  "Run locally: clojure -M -m agiladmin.mcp.credential-cli CONFIG provision ACCOUNT EXPIRY
  Also supports CONFIG list and CONFIG revoke CREDENTIAL_ID. No browser session or MCP tool."
  (:require [agiladmin.config :as config]
            [agiladmin.auth.core :as auth]
            [agiladmin.auth.pocketbase :as pb]
            [agiladmin.mcp.credentials :as credentials]
            [agiladmin.work-policy :as policy]
            [failjure.core :as f]))

(defn -main [filename action & args]
  (try
    (let [conf (config/load-config filename config/default-settings)
          settings (policy/validate-config (get-in conf [:agiladmin :mcp]) (get-in conf [:agiladmin :budgets :path]))]
      (when (or (f/failed? conf) (f/failed? settings) (not (:data-path settings))
                (nil? (get-in conf [:agiladmin :pocketbase])))
        (throw (ex-info "Valid MCP storage and production auth configuration required" {})))
      (auth/init! (pb/backend (get-in conf [:agiladmin :pocketbase])))
      (let [store (credentials/store (:data-path settings))
            result (case [action (count args)]
                     ["provision" 2] (credentials/provision! store (first args) (second args))
                     ["revoke" 1] (credentials/revoke! store (first args))
                     ["list" 0] (credentials/list-credentials store)
                     (throw (ex-info "Expected CONFIG provision ACCOUNT ISO_EXPIRY, list, or revoke ID" {})))]
        (when (f/failed? result) (throw (ex-info "Active unique production owner required" {})))
        ;; Provision prints plaintext exactly once to the invoking local operator.
        (prn result)))
    (catch Exception _
      (binding [*out* *err*] (println "Credential command refused; check arguments, account mapping and local storage."))
      (System/exit 1)))
  (shutdown-agents))
