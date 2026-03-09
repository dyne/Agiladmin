(ns agiladmin.view-auth-test
  (:require [agiladmin.view-auth :as view-auth]
            [midje.sweet :refer :all]))

(fact "Login stores the authenticated account in session"
      (with-redefs [agiladmin.auth.core/sign-in (fn [username password options]
                                                  {:email username
                                                   :name "User Name"
                                                   :options options})]
        (let [response (view-auth/login-post {:params {:username "user@example.org"
                                                       :password "secret"}
                                              :remote-addr "127.0.0.1"})]
          (get-in response [:session :auth :email]) => "user@example.org"
          (get-in response [:session :auth :options]) => {:ip-address "127.0.0.1"})))

(fact "Activation delegates through the auth boundary"
      (let [calls (atom [])]
        (with-redefs [agiladmin.auth.core/confirm-verification
                      (fn [email token]
                        (swap! calls conj [email token])
                        true)]
          (view-auth/activate {} "user@example.org" "token")
          (view-auth/activate {} "token-2")
          @calls => [["user@example.org" "token"]
                     [nil "token-2"]])))

(fact "Signup requests verification after creating the account"
      (let [calls (atom [])]
        (with-redefs [agiladmin.auth.core/sign-up
                      (fn [name email password options other-names]
                        (swap! calls conj [:sign-up name email password options other-names])
                        {:email email})
                      agiladmin.auth.core/request-verification
                      (fn [email]
                        (swap! calls conj [:request-verification email])
                        true)]
          (view-auth/signup-post {:params {:name "User Name"
                                           :email "user@example.org"
                                           :password "secret"
                                           :repeat-password "secret"}})
          @calls => [[:sign-up "User Name" "user@example.org" "secret" {} []]
                     [:request-verification "user@example.org"]])))
