(ns agiladmin.ring-test
  (:require [agiladmin.ring :as ring]
            [failjure.core]
            [midje.sweet :refer :all]))

(fact "Ring init wires the PocketBase backend when configured"
      (let [calls (atom [])
            backend-instance {:healthy? (fn [] true)}]
        (with-redefs [agiladmin.config/load-config
                      (fn [_ _]
                        {:agiladmin {:budgets {:ssh-key "test/assets/id_rsa"}
                                     :auth {:backend "pocketbase"
                                            :pocketbase {:base-url "http://127.0.0.1:8090"
                                                         :users-collection "users"
                                                         :superuser-email "admin@example.org"
                                                         :superuser-password "secret"}}}})
                      clojure.java.io/as-file
                      (fn [_] (proxy [java.io.File] ["test/assets/id_rsa"]
                                (exists [] true)))
                      auxiliary.translation/init
                      (fn [& _] true)
                      agiladmin.pocketbase-process/start!
                      (fn [_]
                        (swap! calls conj [:managed-start])
                        true)
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

(fact "Ring init starts a managed PocketBase process when configured"
      (let [calls (atom [])
            backend-instance {:healthy? (fn [] true)}]
        (with-redefs [agiladmin.config/load-config
                      (fn [_ _]
                        {:agiladmin {:budgets {:ssh-key "test/assets/id_rsa"}
                                     :auth {:backend "pocketbase"
                                            :pocketbase {:base-url "http://127.0.0.1:8090"
                                                         :users-collection "users"
                                                         :superuser-email "admin@example.org"
                                                         :superuser-password "secret"
                                                         :manage-process true
                                                         :binary "pocketbase"
                                                         :dir "/tmp/pb"}}}})
                      clojure.java.io/as-file
                      (fn [_] (proxy [java.io.File] ["test/assets/id_rsa"]
                                (exists [] true)))
                      auxiliary.translation/init
                      (fn [& _] true)
                      agiladmin.pocketbase-process/start!
                      (fn [config]
                        (swap! calls conj [:managed-start config])
                        true)
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
          @calls => [[:managed-start {:base-url "http://127.0.0.1:8090"
                                      :users-collection "users"
                                      :superuser-email "admin@example.org"
                                      :superuser-password "secret"
                                      :manage-process true
                                      :binary "pocketbase"
                                      :dir "/tmp/pb"}]
                     [:backend {:base-url "http://127.0.0.1:8090"
                                :users-collection "users"
                                :superuser-email "admin@example.org"
                                :superuser-password "secret"
                                :manage-process true
                                :binary "pocketbase"
                                :dir "/tmp/pb"}]
                     [:init backend-instance]
                     [:healthy]])))

(fact "Ring init can fall back to development auth"
      (let [calls (atom [])
            backend-instance {:healthy? (fn [] true)}]
        (with-redefs [agiladmin.config/load-config
                      (fn [_ _]
                        {:agiladmin {:budgets {:ssh-key "test/assets/id_rsa"}}})
                      clojure.java.io/as-file
                      (fn [_] (proxy [java.io.File] ["test/assets/id_rsa"]
                                (exists [] true)))
                      auxiliary.translation/init
                      (fn [& _] true)
                      agiladmin.ring/dev-auth-enabled?
                      (fn [] true)
                      agiladmin.auth.dev/backend
                      (fn []
                        (swap! calls conj [:dev-backend])
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
          @calls => [[:dev-backend]
                     [:init backend-instance]
                     [:healthy]])))

(fact "Ring init wires the Pocket ID backend when configured"
      (let [calls (atom [])
            backend-instance {:healthy? (fn [] true)}]
        (with-redefs [agiladmin.config/load-config
                      (fn [_ _]
                        {:agiladmin {:budgets {:ssh-key "test/assets/id_rsa"}
                                     :auth {:backend "pocket-id"
                                            :pocket-id {:issuer-url "https://pocket-id.example.org"
                                                        :client-id "agiladmin"
                                                        :client-secret "secret"
                                                        :redirect-uri "https://agiladmin.example.org/auth/pocket-id/callback"
                                                        :admin-group "agiladmin-admin"
                                                        :manager-group "agiladmin-manager"}}}})
                      clojure.java.io/as-file
                      (fn [_] (proxy [java.io.File] ["test/assets/id_rsa"]
                                (exists [] true)))
                      auxiliary.translation/init
                      (fn [& _] true)
                      agiladmin.auth.pocket-id/backend
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
          @calls => [[:backend {:issuer-url "https://pocket-id.example.org"
                                :client-id "agiladmin"
                                :client-secret "secret"
                                :redirect-uri "https://agiladmin.example.org/auth/pocket-id/callback"
                                :admin-group "agiladmin-admin"
                                :manager-group "agiladmin-manager"}]
                     [:init backend-instance]
                     [:healthy]])))

(fact "Ring init fails with the config validation message when config loading fails"
      (with-redefs [agiladmin.config/load-config
                    (fn [_ _]
                      (failjure.core/fail "Invalid configuration at doc/agiladmin.pocketbase.yaml: :budgets"))]
        (try
          (ring/init)
          false => true
          (catch clojure.lang.ExceptionInfo ex
            (.getMessage ex) => "Invalid configuration at doc/agiladmin.pocketbase.yaml: :budgets"))))

(fact "Ring init fails with a clear message when the auth backend health check throws"
      (with-redefs [agiladmin.config/load-config
                    (fn [_ _]
                      {:agiladmin {:budgets {:ssh-key "test/assets/id_rsa"}
                                   :auth {:backend "pocket-id"
                                          :pocket-id {:issuer-url "https://pocket-id.example.org"
                                                      :client-id "agiladmin"
                                                      :client-secret "secret"
                                                      :redirect-uri "https://agiladmin.example.org/auth/pocket-id/callback"
                                                      :admin-group "agiladmin-admin"
                                                      :manager-group "agiladmin-manager"}}}})
                    clojure.java.io/as-file
                    (fn [_] (proxy [java.io.File] ["test/assets/id_rsa"]
                              (exists [] true)))
                    auxiliary.translation/init
                    (fn [& _] true)
                    agiladmin.auth.pocket-id/backend
                    (fn [_] {:healthy? (fn [] true)})
                    agiladmin.auth.core/init!
                    (fn [backend]
                      backend)
                    agiladmin.auth.core/healthy?
                    (fn []
                      (throw (java.net.SocketException. "Connection refused")))]
        (try
          (ring/init)
          false => true
          (catch clojure.lang.ExceptionInfo ex
            (.getMessage ex) => (contains "Authentication backend health check failed")))))

(fact "Ring init skips auth when no backend is configured and dev auth is disabled"
      (let [calls (atom [])]
        (with-redefs [agiladmin.config/load-config
                      (fn [_ _]
                        {:agiladmin {:budgets {:ssh-key "test/assets/id_rsa"}}})
                      clojure.java.io/as-file
                      (fn [_] (proxy [java.io.File] ["test/assets/id_rsa"]
                                (exists [] true)))
                      auxiliary.translation/init
                      (fn [& _] true)
                      agiladmin.ring/dev-auth-enabled?
                      (fn [] false)
                      agiladmin.auth.core/init!
                      (fn [backend]
                        (swap! calls conj [:init backend])
                        backend)]
          (ring/init) => truthy
          @calls => [[:init nil]])))
