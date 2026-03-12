(ns agiladmin.view-auth-test
  (:require [agiladmin.view-auth :as view-auth]
            [failjure.core]
            [midje.sweet :refer :all]))

(fact "Login stores the authenticated account in session using the email field"
      (with-redefs [agiladmin.auth.core/sign-in (fn [username password options]
                                                  {:email username
                                                   :name "User Name"
                                                   :options options})]
        (let [response (view-auth/login-post {:params {:email "user@example.org"
                                                       :password "secret"}
                                              :remote-addr "127.0.0.1"})]
          (:status response) => 302
          (get-in response [:headers "Location"]) => "/persons/list"
          (get-in response [:session :auth :email]) => "user@example.org"
          (get-in response [:session :auth :role]) => nil
          (get-in response [:session :auth :options]) => {:ip-address "127.0.0.1"})))

(fact "Login normalizes legacy admin responses into the admin role"
      (with-redefs [agiladmin.auth.core/sign-in (fn [username _password _options]
                                                  {:email username
                                                   :name "User Name"
                                                   :admin true})]
        (let [response (view-auth/login-post {:params {:email "user@example.org"
                                                       :password "secret"}
                                              :remote-addr "127.0.0.1"})]
          (:status response) => nil
          (get-in response [:session :auth :role]) => "admin")))

(fact "Login keeps manager users on the logged-in landing page"
      (with-redefs [agiladmin.auth.core/sign-in (fn [username _password _options]
                                                  {:email username
                                                   :name "Manager User"
                                                   :role "manager"})]
        (let [response (view-auth/login-post {:params {:email "manager"
                                                       :password "manager"}
                                              :remote-addr "127.0.0.1"})]
          (:status response) => nil
          (:body response) => (contains "Logged in: manager"))))

(fact "Login still accepts the legacy username field"
      (with-redefs [agiladmin.auth.core/sign-in (fn [username password options]
                                                  {:email username
                                                   :name "User Name"
                                                   :options options})]
        (let [response (view-auth/login-post {:params {:username "user@example.org"
                                                       :password "secret"}
                                              :remote-addr "127.0.0.1"})]
          (get-in response [:session :auth :email]) => "user@example.org")))

(fact "Login prefers the forwarded client IP when present"
      (with-redefs [agiladmin.auth.core/sign-in (fn [username password options]
                                                  {:email username
                                                   :name "User Name"
                                                   :options options})]
        (let [response (view-auth/login-post {:params {:email "user@example.org"
                                                       :password "secret"}
                                              :headers {"x-forwarded-for" "203.0.113.10, 10.0.0.4"}
                                              :remote-addr "127.0.0.1"})]
          (get-in response [:session :auth :options]) => {:ip-address "203.0.113.10"})))

(fact "Login failure renders the auth backend message"
      (with-redefs [agiladmin.auth.core/sign-in (fn [& _]
                                                  (failjure.core/fail "Invalid credentials."))]
        (let [response (view-auth/login-post {:params {:email "user@example.org"
                                                       :password "secret"}
                                              :remote-addr "127.0.0.1"})]
          (:body response) => (contains "Login failed: Invalid credentials."))))

(fact "Login get reports the active account when already authenticated"
      (let [response (view-auth/login-get {:session {:auth {:email "user@example.org"
                                                            :name "User Name"}}})]
        (:body response) => (contains "Already logged in with account: user@example.org")))

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

(fact "Signup rejects mismatched passwords"
      (let [response (view-auth/signup-post {:params {:name "User Name"
                                                      :email "user@example.org"
                                                      :password "secret"
                                                      :repeat-password "different"}})]
        (:body response) => (contains "Repeat password didnt match")))

(fact "Signup surfaces account creation failures"
      (with-redefs [agiladmin.auth.core/sign-up (fn [& _]
                                                  (failjure.core/fail "Account already exists."))]
        (let [response (view-auth/signup-post {:params {:name "User Name"
                                                        :email "user@example.org"
                                                        :password "secret"
                                                        :repeat-password "secret"}})]
          (:body response) => (contains "Failure creating account: Account already exists."))))

(fact "Signup surfaces verification request failures after account creation"
      (with-redefs [agiladmin.auth.core/sign-up (fn [& _]
                                                  {:email "user@example.org"})
                    agiladmin.auth.core/request-verification (fn [_]
                                                               (failjure.core/fail "SMTP unavailable."))]
        (let [response (view-auth/signup-post {:params {:name "User Name"
                                                        :email "user@example.org"
                                                        :password "secret"
                                                        :repeat-password "secret"}})]
          (:body response) => (contains "Failure requesting verification: SMTP unavailable."))))
