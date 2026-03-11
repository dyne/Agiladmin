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
            backend {:healthy? (fn [] true)
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
                                           [{:email "pending@example.org"}])}]
        (auth/init! backend)
        (auth/healthy?) => true
        (auth/sign-in "user@example.org" "secret" {:ip-address "127.0.0.1"})
        => {:email "user@example.org" :verified true}
        (auth/sign-up "User" "user@example.org" "secret" {:activation-uri "example.org"} [])
        => {:name "User" :email "user@example.org" :other-names []}
        (auth/confirm-verification "user@example.org" "token")
        => {:email "user@example.org" :token "token"}
        (auth/request-verification "user@example.org") => true
        (auth/list-pending-users) => [{:email "pending@example.org"}]
        @state => [[:sign-in "user@example.org" "secret" {:ip-address "127.0.0.1"}]
                   [:sign-up "User" "user@example.org" "secret" {:activation-uri "example.org"} []]
                   [:confirm "user@example.org" "token"]
                   [:request "user@example.org"]
                   [:pending]]))

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
