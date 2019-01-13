(ns agiladmin.session
  (:refer-clojure :exclude [get])
  (:require
   [agiladmin.config :as conf]
   [taoensso.timbre :as log]
   [failjure.core :as f]
   [just-auth.core :as auth]
   [agiladmin.ring :as ring]
   [agiladmin.webpage :as web]))

(defn param [request param]
  (let [value
        (get-in request
                (conj [:params] param))]
    (if (nil? value)
      (f/fail (str "Parameter not found: " param))
      value)))

;; TODO: not working?
(defn get [req arrk]
  {:pre (coll? arrk)}
  (if-let [value (get-in req (conj [:session] arrk))]
    value
    (f/fail (str "Value not found in session: " (str arrk)))))

(defn check-config [request]
  ;; reload configuration from file all the time if in debug mode
  (if-let [session (:session request)]
    (if (contains? session :config)
      (:config session)
      (conf/load-config "agiladmin" conf/default-settings))
    (f/fail "Session not found. ")))

(defn check-account [request]
  ;; check if login is present in session
  (if-let [login (get-in request [:session :auth :email])]
    login ;; success
    (f/fail (str "Unauthorized access."))))

(defn check-database []
  (if-let [db @ring/db]
    db
    (f/fail "No connection to database. ")))

(defn check [request fun]
  (f/attempt-all
   [db (check-database)
    config (check-config request)
    account (check-account request)]
    (fun request config account)
    (f/when-failed [e]
      (web/render
       [:div
        (web/render-error (f/message e))
        web/login-form]))))
