(ns agiladmin.auth.core
  (:require [failjure.core :as f]))

(defonce backend (atom nil))

(defn init!
  [auth-backend]
  (reset! backend auth-backend))

(defn backend!
  []
  (if-let [auth-backend @backend]
    auth-backend
    (f/fail "Authentication backend not initialized.")))

(defn healthy?
  []
  (f/attempt-all
   [auth-backend (backend!)]
   (if-let [healthy-fn (:healthy? auth-backend)]
     (healthy-fn)
     true)))

(defn sign-in
  [username password options]
  (f/attempt-all
   [auth-backend (backend!)]
   ((:sign-in auth-backend) username password options)))

(defn sign-up
  [name email password options other-names]
  (f/attempt-all
   [auth-backend (backend!)]
   ((:sign-up auth-backend) name email password options other-names)))

(defn confirm-verification
  [email token]
  (f/attempt-all
   [auth-backend (backend!)]
   ((:confirm-verification auth-backend) email token)))

(defn request-verification
  [email]
  (f/attempt-all
   [auth-backend (backend!)]
   ((:request-verification auth-backend) email)))

(defn list-pending-users
  []
  (f/attempt-all
   [auth-backend (backend!)]
   ((:list-pending-users auth-backend))))
