(ns agiladmin.dev-auth-test
  (:require [agiladmin.auth.dev :as dev-auth]
            [failjure.core :as f]
            [midje.sweet :refer :all]))

(fact "Development auth backend accepts admin admin"
      (let [backend (dev-auth/backend)]
        ((:sign-in backend) "admin" "admin" {}) => dev-auth/default-user
        (f/failed? ((:sign-in backend) "admin" "wrong" {})) => truthy))
