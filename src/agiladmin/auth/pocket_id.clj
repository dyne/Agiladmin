(ns agiladmin.auth.pocket-id
  (:require [failjure.core :as f]))

(defn healthy?
  [_config]
  true)

(defn backend
  [config]
  {:kind :pocket-id
   :healthy? (fn []
               (healthy? config))
   :login-entry-response (fn [_request]
                           nil)
   :begin-login (fn [_request]
                  (f/fail "Pocket ID login is not implemented yet."))
   :complete-login (fn [_request]
                     (f/fail "Pocket ID login is not implemented yet."))})
