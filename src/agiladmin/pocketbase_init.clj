(ns agiladmin.pocketbase-init
  (:require [agiladmin.auth.pocketbase :as pocketbase]
            [agiladmin.config :as conf]
            [failjure.core :as f]))

(defn -main [& _]
  (let [config (conf/load-config
                (or (System/getenv "AGILADMIN_CONF") "agiladmin")
                conf/default-settings)]
    (when (f/failed? config)
      (throw (ex-info (f/message config)
                      {:type ::config-load-failed})))
    (if-let [pocketbase-conf (or (get-in config [:agiladmin :auth :pocketbase])
                                 (get-in config [:agiladmin :pocketbase]))]
      (do
        (pocketbase/ensure-role-field! pocketbase-conf)
        (println "PocketBase users collection initialized with role select."))
      (throw (ex-info "Missing :agiladmin :auth :pocketbase configuration."
                      {:type ::missing-pocketbase-config})))))
