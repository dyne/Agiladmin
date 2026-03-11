(ns agiladmin.session
  (:refer-clojure :exclude [get])
  (:require
   [agiladmin.config :as conf]
   [taoensso.timbre :as log]
   [failjure.core :as f]
   [agiladmin.webpage :as web]))

(defn param [request param]
  (let [params (:params request)
        keyname (name param)
        value (or (clojure.core/get params param)
                  (clojure.core/get params keyname))]
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

(defn normalize-role
  [login]
  (let [role (or (:role login)
                 (when (true? (:admin login))
                   "admin"))]
    (assoc login :role role)))

(defn admin?
  [account]
  (= "admin" (:role account)))

(defn manager?
  [account]
  (= "manager" (:role account)))

(defn can-view-costs?
  [account]
  (not (manager? account)))

(defn can-access-projects?
  [account]
  (or (admin? account)
      (manager? account)))

(defn require-admin
  [account]
  (if (admin? account)
    account
    (f/fail "Unauthorized access.")))

(defn require-project-access
  [account]
  (if (can-access-projects? account)
    account
    (f/fail "Unauthorized access.")))

(defn effective-person
  [account request]
  (let [params (:params request)
        requested-person (or (clojure.core/get params :person)
                             (clojure.core/get params "person"))]
    (if (admin? account)
      (or requested-person
          (:name account))
      (:name account))))

(defn scoped-person-request
  [request account]
  (assoc-in request [:params :person] (effective-person account request)))

(defn check-account [config request]
  ;; check if login is present in session
  (let [login (get-in request [:session :auth])]
    (cond
      (nil? login) (f/fail (str "Unauthorized access."))
      :else
      (normalize-role login))))

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
