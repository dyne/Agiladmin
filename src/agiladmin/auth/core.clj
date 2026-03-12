(ns agiladmin.auth.core
  (:require [failjure.core :as f]))

(defonce backend (atom nil))

(defn- capability-failure
  [capability]
  (f/fail (str "Authentication backend does not support " capability ".")))

(defn- invoke-backend
  [f & args]
  (try
    (apply f args)
    (catch Throwable t
      (f/fail (.getMessage t)))))

(defn- invoke-capability
  [auth-backend capability args]
  (if-let [capability-fn (get auth-backend capability)]
    (apply invoke-backend capability-fn args)
    (capability-failure (name capability))))

(defn init!
  [auth-backend]
  (reset! backend auth-backend))

(defn backend!
  []
  (if-let [auth-backend @backend]
    auth-backend
    (f/fail "Authentication backend not initialized.")))

(defn backend-kind
  []
  (f/attempt-all
   [auth-backend (backend!)]
   (:kind auth-backend)))

(defn healthy?
  []
  (f/attempt-all
   [auth-backend (backend!)]
   (if-let [healthy-fn (:healthy? auth-backend)]
     (invoke-backend healthy-fn)
     true)))

(defn sign-in
  [username password options]
  (f/attempt-all
   [auth-backend (backend!)]
   (invoke-capability auth-backend :sign-in [username password options])))

(defn sign-up
  [name email password options other-names]
  (f/attempt-all
   [auth-backend (backend!)]
   (invoke-capability auth-backend :sign-up [name email password options other-names])))

(defn confirm-verification
  [email token]
  (f/attempt-all
   [auth-backend (backend!)]
   (invoke-capability auth-backend :confirm-verification [email token])))

(defn request-verification
  [email]
  (f/attempt-all
   [auth-backend (backend!)]
   (invoke-capability auth-backend :request-verification [email])))

(defn list-pending-users
  []
  (f/attempt-all
   [auth-backend (backend!)]
   (invoke-capability auth-backend :list-pending-users [])))

(defn login-entry-response
  [request]
  (f/attempt-all
   [auth-backend (backend!)]
   (invoke-capability auth-backend :login-entry-response [request])))

(defn begin-login
  [request]
  (f/attempt-all
   [auth-backend (backend!)]
   (invoke-capability auth-backend :begin-login [request])))

(defn complete-login
  [request]
  (f/attempt-all
   [auth-backend (backend!)]
   (invoke-capability auth-backend :complete-login [request])))

(defn logout-response
  [request]
  (f/attempt-all
   [auth-backend (backend!)]
   (if-let [logout-fn (:logout-response auth-backend)]
     (invoke-backend logout-fn request)
     {:session {}
      :status 302
      :headers {"Location" "/login"}
      :body ""})))
