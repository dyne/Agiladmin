(ns agiladmin.ring-test
  (:require [agiladmin.ring :as ring]
            [midje.sweet :refer :all]))

(fact "Ring init wires the PocketBase backend when configured"
      (let [calls (atom [])
            backend-instance {:healthy? (fn [] true)}]
        (with-redefs [agiladmin.config/load-config
                      (fn [_ _]
                        {:agiladmin {:budgets {:ssh-key "test/assets/id_rsa"}
                                     :pocketbase {:base-url "http://127.0.0.1:8090"
                                                  :users-collection "users"
                                                  :superuser-email "admin@example.org"
                                                  :superuser-password "secret"}}})
                      clojure.java.io/as-file
                      (fn [_] (proxy [java.io.File] ["test/assets/id_rsa"]
                                (exists [] true)))
                      auxiliary.translation/init
                      (fn [& _] true)
                      agiladmin.auth.pocketbase/backend
                      (fn [config]
                        (swap! calls conj [:backend config])
                        backend-instance)
                      agiladmin.auth.core/init!
                      (fn [backend]
                        (swap! calls conj [:init backend])
                        backend)
                      agiladmin.auth.core/healthy?
                      (fn []
                        (swap! calls conj [:healthy])
                        true)]
          (ring/init) => truthy
          @calls => [[:backend {:base-url "http://127.0.0.1:8090"
                                :users-collection "users"
                                :superuser-email "admin@example.org"
                                :superuser-password "secret"}]
                     [:init backend-instance]
                     [:healthy]])))
