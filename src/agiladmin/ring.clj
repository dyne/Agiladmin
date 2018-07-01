(ns agiladmin.ring
  (:require
   [clojure.java.io :as io]
   [agiladmin.config :as conf]
   [taoensso.timbre :as log]
   [failjure.core :as f]
   [clj-storage.db.mongo :refer [get-mongo-db create-mongo-store]]
   [just-auth.core :as auth]
   [just-auth.db.just-auth :as auth-db]

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
(def db     (atom {}))
(def accts  (atom {}))
(def auth   (atom {}))

(defn init []
  (log/merge-config! {:level :debug
                      ;; #{:trace :debug :info :warn :error :fatal :report}

                      ;; Control log filtering by
                      ;; namespaces/patterns. Useful for turning off
                      ;; logging in noisy libraries, etc.:
;;                      :ns-whitelist  ["agiladmin.*" "just-auth.*"]
                      :ns-blacklist  ["org.eclipse.jetty.*"
                                      "org.mongodb.driver.cluster"]})

  ;; load configuration
  (reset! config (conf/load-config
                  (or (System/getenv "AGILADMIN_CONF") "agiladmin")
                  conf/default-settings))
  (let [keypath (conf/q @config [:agiladmin :budgets :ssh-key])]
    (if-not (.exists (io/as-file keypath))
      (let [kp (generate-key-pair)]
        (log/info "Generating SSH keypair...")
        (write-key-pair kp keypath))))

  (let [justauth-conf (get-in @config [:agiladmin :just-auth])]
    ;; connect database (TODO: take parameters from configuration)
    (reset! db (get-mongo-db (:mongo-url justauth-conf)))

    ;; create authentication stores in db
    (f/attempt-all
     [auth-conf   (get-in @config [:agiladmin :just-auth])
      auth-stores (auth-db/create-auth-stores @db)]

     [(trans/init "lang/auth-en.yml" "lang/agiladmin-en.yml")
      (reset! accts auth-stores)
      (reset! auth (auth/new-stub-email-based-authentication
                    auth-stores (atom []) 
                    {:criteria #{:email :ip-address} 
                     :type :block
                     :time-window-secs 10
                     :threshold 5}))]
                    ;; (select-keys auth-stores [:account-store
                    ;;                           :password-recovery-store])
     (f/when-failed [e]
       (log/error (str (trans/locale [:init :failure])
                       " - " (f/message e))))))
  (log/info (str (trans/locale [:init :success])))
  (log/debug @auth))

(def app-defaults
    (-> site-defaults
        (assoc-in [:cookies] true)
        (assoc-in [:security :anti-forgery]
                  (get-in @config [:webserver :anti-forgery]))
        (assoc-in [:security :ssl-redirect]
                  (get-in @config [:webserver :ssl-redirect]))
        (assoc-in [:security :hsts] true)))
