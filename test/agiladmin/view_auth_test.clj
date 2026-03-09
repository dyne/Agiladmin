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
          (view-auth/activate {:headers {"host" "localhost:3000"}}
                              "user@example.org"
                              "token")
          @calls => [["user@example.org"
                      "http://localhost:3000/activate/user@example.org/token"]])))
