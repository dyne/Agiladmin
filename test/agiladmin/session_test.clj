(ns agiladmin.session-test
  (:require [agiladmin.session :as session]
            [failjure.core :as f]
            [midje.sweet :refer :all]))

(def config {:agiladmin {}})

(fact "Session param accepts keyword form keys"
      (session/param {:params {:person "Denis Roio"}} :person)
      => "Denis Roio")

(fact "Session param accepts string form keys"
      (session/param {:params {"person" "Denis Roio"}} :person)
      => "Denis Roio")

(fact "Session check keeps the role from the authenticated session"
      (session/check-account config {:session {:auth {:email "admin@example.org"
                                                      :name "Admin"
                                                      :role "admin"}}})
      => {:email "admin@example.org"
          :name "Admin"
          :role "admin"})

(fact "Session check maps a legacy admin flag to the admin role"
      (session/check-account config {:session {:auth {:email "admin@example.org"
                                                      :name "Admin"
                                                      :admin true}}})
      => {:email "admin@example.org"
          :name "Admin"
          :admin true
          :role "admin"})

(fact "Session check does not require a live auth backend"
      (session/check {:session {:config config
                                :auth {:email "user@example.org"
                                       :name "User"}}}
                     (fn [_ _ account] account))
      => {:email "user@example.org"
          :name "User"
          :role nil})

(fact "Session check fails without a login in the session"
      (f/failed? (session/check-account config {:session {:config config}})) => truthy)

(fact "Session check keeps the authenticated shell for downstream failures"
      (let [response (session/check
                      {:session {:config config
                                 :auth {:email "user@example.org"
                                        :name "User"}}}
                      (fn [_ _ _]
                        (f/fail "Project configuration file is missing, empty, or invalid YAML: budgets/CODE.yaml")))]
        (:body response) => (contains "Project configuration file is missing, empty, or invalid YAML: budgets/CODE.yaml")
        (:body response) => (contains "Personnel")
        (:body response) =not=> (contains "Login into Agiladmin")))
