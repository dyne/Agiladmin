(ns agiladmin.session-test
  (:require [agiladmin.session :as session]
            [failjure.core :as f]
            [midje.sweet :refer :all]))

(def config {:agiladmin {:admins ["admin@example.org"]}})

(fact "Session check marks configured admins"
      (session/check-account config {:session {:auth {:email "admin@example.org"
                                                      :name "Admin"}}})
      => {:email "admin@example.org"
          :name "Admin"
          :admin true})

(fact "Session check does not require a live auth backend"
      (session/check {:session {:config config
                                :auth {:email "user@example.org"
                                       :name "User"}}}
                     (fn [_ _ account] account))
      => {:email "user@example.org"
          :name "User"
          :admin false})

(fact "Session check fails without a login in the session"
      (f/failed? (session/check-account config {:session {:config config}})) => truthy)
