(ns agiladmin.session
  (:refer-clojure :exclude [get])
  (:require
   [agiladmin.config :as conf]
   [taoensso.timbre :as log]
   [failjure.core :as f]
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

(defn check-account [config request]
  ;; check if login is present in session
  (let [login (get-in request [:session :auth])]
    (cond
      (nil? login) (f/fail (str "Unauthorized access."))
      :else
      (assoc login :admin (true? (:admin login))))))

(defn- render-check-failure
  [request error]
  (if-let [account (get-in request [:session :auth])]
    (web/render account
                [:div
                 (web/render-error error)])
    (web/render
     [:div
      (web/render-error error)
      web/login-form])))

(defn check [request fun]
  (f/attempt-all
   [config (check-config request)
    account (check-account config request)]
   (fun request config account)
   (f/when-failed [e]
     (render-check-failure request (f/message e)))))
