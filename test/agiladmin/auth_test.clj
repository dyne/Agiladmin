(ns agiladmin.auth-test
  (:require [agiladmin.auth.core :as auth]
            [failjure.core :as f]
            [midje.sweet :refer :all]))

(fact "Auth core fails before initialization"
      (let [previous @auth/backend]
        (auth/init! nil)
        (try
          (f/failed? (auth/sign-in "user@example.org" "secret" {})) => truthy
          (finally
            (auth/init! previous)))))

(fact "Auth core delegates to the configured backend"
      (let [state (atom [])
            backend {:kind :test
                     :healthy? (fn [] true)
                     :sign-in (fn [username password options]
                                (swap! state conj [:sign-in username password options])
                                {:email username :verified true})
                     :sign-up (fn [name email password options other-names]
                                (swap! state conj [:sign-up name email password options other-names])
                                {:name name :email email :other-names other-names})
                     :confirm-verification (fn [email token]
                                             (swap! state conj [:confirm email token])
                                             {:email email :token token})
                     :request-verification (fn [email]
                                             (swap! state conj [:request email])
                                             true)
                     :list-pending-users (fn []
                                           (swap! state conj [:pending])
                                           [{:email "pending@example.org"}])
                     :begin-login (fn [request]
                                    (swap! state conj [:begin-login request])
                                    {:status 302})
                     :complete-login (fn [request]
                                       (swap! state conj [:complete-login request])
                                       {:email "callback@example.org"})
                     :logout-response (fn [request]
                                        (swap! state conj [:logout request])
                                        {:status 302 :headers {"Location" "/signed-out"}})}]
        (auth/init! backend)
        (auth/backend-kind) => :test
        (auth/healthy?) => true
        (auth/sign-in "user@example.org" "secret" {:ip-address "127.0.0.1"})
        => {:email "user@example.org" :verified true}
        (auth/sign-up "User" "user@example.org" "secret" {:activation-uri "example.org"} [])
        => {:name "User" :email "user@example.org" :other-names []}
        (auth/confirm-verification "user@example.org" "token")
        => {:email "user@example.org" :token "token"}
        (auth/request-verification "user@example.org") => true
        (auth/list-pending-users) => [{:email "pending@example.org"}]
        (auth/begin-login {:uri "/login/start"}) => {:status 302}
        (auth/complete-login {:params {:code "abc"}}) => {:email "callback@example.org"}
        (auth/logout-response {:uri "/logout"}) => {:status 302 :headers {"Location" "/signed-out"}}
        @state => [[:sign-in "user@example.org" "secret" {:ip-address "127.0.0.1"}]
                   [:sign-up "User" "user@example.org" "secret" {:activation-uri "example.org"} []]
                   [:confirm "user@example.org" "token"]
                   [:request "user@example.org"]
                   [:pending]
                   [:begin-login {:uri "/login/start"}]
                   [:complete-login {:params {:code "abc"}}]
                   [:logout {:uri "/logout"}]]))

(fact "Auth core turns backend exceptions into failjure failures"
      (let [previous @auth/backend
            backend {:list-pending-users (fn []
                                           (throw (ex-info "PocketBase unreachable." {})))}]
        (auth/init! backend)
        (try
          (let [result (auth/list-pending-users)]
            (f/failed? result) => truthy
            (f/message result) => "PocketBase unreachable.")
          (finally
            (auth/init! previous)))))

(fact "Auth core reports unsupported optional capabilities clearly"
      (let [previous @auth/backend
            backend {:kind :test
                     :healthy? (fn [] true)}]
        (auth/init! backend)
        (try
          (let [result (auth/begin-login {:uri "/login/start"})]
            (f/failed? result) => truthy
            (f/message result) => "Authentication backend does not support begin-login.")
          (finally
            (auth/init! previous)))))
