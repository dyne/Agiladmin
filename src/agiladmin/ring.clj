(ns agiladmin.ring
  (:require
   [clojure.java.io :as io]
   [agiladmin.auth.core :as auth-core]
   [agiladmin.auth.pocketbase :as pocketbase]
   [agiladmin.config :as conf]
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

(defn init []
  (log/merge-config! {:level :debug
                      ;; #{:trace :debug :info :warn :error :fatal :report}

                      ;; Control log filtering by
                      ;; namespaces/patterns. Useful for turning off
                      ;; logging in noisy libraries, etc.:
;;                      :ns-whitelist  ["agiladmin.*"]
                      :ns-blacklist  ["org.eclipse.jetty.*"]})

  ;; load configuration
  (reset! config (conf/load-config
                  (or (System/getenv "AGILADMIN_CONF") "agiladmin")
                  conf/default-settings))
  (let [keypath (conf/q @config [:agiladmin :budgets :ssh-key])]
    (if-not (.exists (io/as-file keypath))
      (let [kp (generate-key-pair)]
        (log/info "Generating SSH keypair...")
        (write-key-pair kp keypath))))

  (trans/init "resources/lang/agiladmin-en.yml")

  (if-let [pocketbase-conf (get-in @config [:agiladmin :pocketbase])]
    (auth-core/init! (pocketbase/backend pocketbase-conf))
    (do
      (auth-core/init! nil)
      (log/warn "Skipping auth initialization: missing :agiladmin :pocketbase")))
  (log/info (str (trans/locale [:init :success])))
  (log/debug (auth-core/healthy?))
  true)

(defn app-defaults []
  (let [webserver (get-in @config [:agiladmin :webserver] {})]
    (-> site-defaults
        (assoc-in [:cookies] true)
        (assoc-in [:security :anti-forgery]
                  (get webserver :anti-forgery false))
        (assoc-in [:security :ssl-redirect]
                  (get webserver :ssl-redirect false))
        (assoc-in [:security :hsts] true))))
