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
      (session/check {:session {:auth {:email "user@example.org"
                                       :name "User"}}}
                     (fn [_ _ account] account))
      => {:email "user@example.org"
          :name "User"
          :role nil})

(fact "Session check fails without a login in the session"
      (f/failed? (session/check-account config {:session {}})) => truthy)

(fact "Project access is granted to admins and managers only"
      (session/can-access-projects? {:role "admin"}) => true
      (session/can-access-projects? {:role "manager"}) => true
      (session/can-access-projects? {:role nil}) => false)

(fact "Managers cannot view costs"
      (session/can-view-costs? {:role "admin"}) => true
      (session/can-view-costs? {:role "manager"}) => false
      (session/can-view-costs? {:role nil}) => true)

(fact "Non-admin personnel requests are scoped to the authenticated person"
      (session/effective-person {:name "User Name" :role "manager"}
                                {:params {:person "Other User"}})
      => "User Name")

(fact "Admin personnel requests keep the requested person"
      (session/effective-person {:name "Admin" :role "admin"}
                                {:params {:person "Other User"}})
      => "Other User")

(fact "Session check keeps the authenticated shell for downstream failures"
      (let [response (session/check
                      {:session {:auth {:email "user@example.org"
                                        :name "User"}}}
                      (fn [_ _ _]
                        (f/fail "Project configuration file is missing, empty, or invalid YAML: budgets/CODE.yaml")))]
        (:body response) => (contains "Project configuration file is missing, empty, or invalid YAML: budgets/CODE.yaml")
        (:body response) => (contains "Logout")
        (:body response) => (contains "href=\"/persons/list\"")
        (:body response) =not=> (contains "Login into Agiladmin")))
