(ns agiladmin.ring
  (:require
   [clojure.java.io :as io]
   [agiladmin.auth.core :as auth-core]
   [agiladmin.auth.just-auth :as just-auth]
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

  (trans/init "lang/auth-en.yml" "lang/agiladmin-en.yml")

  (let [justauth-conf (get-in @config [:agiladmin :just-auth])]
    (if-let [mongo-url (:mongo-url justauth-conf)]
      (do
        ;; connect database (TODO: take parameters from configuration)
        (reset! db (get-mongo-db mongo-url))

        ;; create authentication stores in db
        (f/attempt-all
         [auth-conf   justauth-conf
          auth-stores (auth-db/create-auth-stores @db)]

         [(reset! accts auth-stores)
          (reset! auth (auth/email-based-authentication
                        auth-stores
                        ;; TODO: replace with email taken from config
                        (dissoc auth-conf
                                :mongo-url :mongo-user :mongo-pass)
                        {:criteria #{:email :ip-address}
                         :type :block
                         :time-window-secs 10
                         :threshold 5}))
          (auth-core/init! (just-auth/backend @auth auth-stores))]
         ;; (select-keys auth-stores [:account-store
         ;;                           :password-recovery-store])
         (f/when-failed [e]
           (log/error (str (trans/locale [:init :failure])
                           " - " (f/message e))))))
      (log/warn "Skipping auth initialization: missing :agiladmin :just-auth :mongo-url")))
  (log/info (str (trans/locale [:init :success])))
  (log/debug @auth)
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
