(ns agiladmin.ring
  (:require
   [clojure.java.io :as io]
   [agiladmin.auth.core :as auth-core]
   [agiladmin.auth.dev :as dev-auth]
   [agiladmin.auth.pocketbase :as pocketbase]
   [agiladmin.pocketbase-process :as pocketbase-process]
   [agiladmin.config :as conf]
   [failjure.core :as f]
   [taoensso.timbre :as log]

   [auxiliary.translation :as trans]
   [compojure.core :refer :all]
   [compojure.handler :refer :all]
   ;; ssh crypto
   [clj-openssh-keygen.core :refer [generate-key-pair
                                    write-key-pair]]
   [ring.middleware.session :refer :all]
   [ring.middleware.accept :refer [wrap-accept]]
   [ring.middleware.defaults :refer [wrap-defaults site-defaults]]))

(def config (atom {}))

(defn- dev-auth-enabled? []
  (#{"1" "true" "TRUE" "yes" "YES"} (System/getenv "AGILADMIN_DEV_AUTH")))

(defn- auth-health-status
  []
  (try
    (auth-core/healthy?)
    (catch Exception ex
      (f/fail (str "Authentication backend health check failed: "
                   (.getMessage ex))))))

(defn init []
  (log/merge-config! {:level :debug
                      ;; #{:trace :debug :info :warn :error :fatal :report}

                      ;; Control log filtering by
                      ;; namespaces/patterns. Useful for turning off
                      ;; logging in noisy libraries, etc.:
;;                      :ns-whitelist  ["agiladmin.*"]
                      :ns-blacklist  ["org.eclipse.jetty.*"
                                      "org.apache.http.wire"
                                      "org.apache.http.headers"]})

  ;; load configuration
  (reset! config (conf/load-config
                  (or (System/getenv "AGILADMIN_CONF") "agiladmin")
                  conf/default-settings))
  (when (f/failed? @config)
    (throw (ex-info (f/message @config)
                    {:type ::config-load-failed})))
  (let [keypath (conf/q @config [:agiladmin :budgets :ssh-key])]
    (if-not (.exists (io/as-file keypath))
      (let [kp (generate-key-pair)]
        (log/info "Generating SSH keypair...")
        (write-key-pair kp keypath))))

  (trans/init "resources/lang/agiladmin-en.yml")

  (let [auth-enabled?
        (if-let [pocketbase-conf (get-in @config [:agiladmin :pocketbase])]
          (do
            (when (:manage-process pocketbase-conf)
              (pocketbase-process/start! pocketbase-conf))
            (auth-core/init! (pocketbase/backend pocketbase-conf))
            true)
          (if (dev-auth-enabled?)
            (do
              (auth-core/init! (dev-auth/backend))
              (log/warn "Starting with development auth backend enabled.")
              true)
            (do
              (auth-core/init! nil)
              (log/warn "Skipping auth initialization: missing :agiladmin :pocketbase")
              false)))
        healthy? (when auth-enabled?
                   (auth-health-status))]
    (when (and auth-enabled?
               (or (f/failed? healthy?)
                   (false? healthy?)))
      (throw (ex-info (if (f/failed? healthy?)
                        (f/message healthy?)
                        "Authentication backend health check failed.")
                      {:type ::auth-health-failed})))
    (log/info (str (trans/locale [:init :success])))
    (log/debug (or healthy? (auth-core/healthy?)))
    true))

(defn app-defaults []
  (let [webserver (get-in @config [:agiladmin :webserver] {})]
    (-> site-defaults
        (assoc-in [:cookies] true)
        (assoc-in [:security :anti-forgery]
                  (get webserver :anti-forgery false))
        (assoc-in [:security :ssl-redirect]
                  (get webserver :ssl-redirect false))
        (assoc-in [:security :hsts] true))))
