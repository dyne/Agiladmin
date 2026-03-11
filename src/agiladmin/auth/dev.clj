(ns agiladmin.auth.dev
  (:require [failjure.core :as f]))

(def default-user
  {:id "dev-admin"
   :email "admin"
   :name "Admin"
   :admin true
   :other-names []
   :verified true})

(def guest-user
  {:id "dev-guest"
   :email "guest"
   :name "Guest"
   :admin false
   :other-names []
   :verified true})

(defn backend
  []
  {:healthy? (fn [] true)
   :sign-in (fn [username password _options]
              (cond
                (and (= username "admin")
                     (= password "admin"))
                default-user

                (and (= username "guest")
                     (= password "guest"))
                guest-user

                :else
                (f/fail "Invalid development credentials.")))
   :sign-up (fn [_name _email _password _options _other-names]
              (f/fail "Development auth backend does not support signup."))
   :confirm-verification (fn [_email _token]
                           true)
   :request-verification (fn [_email]
                           true)
   :list-pending-users (fn []
                         [])})
