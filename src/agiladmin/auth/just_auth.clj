(ns agiladmin.auth.just-auth
  (:require [clj-storage.core :as store]
            [failjure.core :as f]
            [just-auth.core :as auth]))

(defn backend
  [auth-service auth-stores]
  {:healthy? (fn [] (boolean auth-service))
   :sign-in (fn [username password options]
              (auth/sign-in auth-service username password options))
   :sign-up (fn [name email password options other-names]
              (auth/sign-up auth-service
                            name
                            email
                            password
                            options
                            other-names))
   :confirm-verification (fn [email token]
                           (auth/activate-account
                            auth-service
                            email
                            {:activation-link token}))
   :request-verification (fn [_email]
                           (f/fail "Legacy just-auth backend cannot resend verification emails."))
   :list-pending-users (fn []
                         (store/query (:account-store auth-stores)
                                      {:activated false}))})
