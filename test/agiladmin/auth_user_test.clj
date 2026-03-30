(ns agiladmin.auth-user-test
  (:require [agiladmin.auth.user :as auth-user]
            [midje.sweet :refer :all]))

(fact "Admin group wins when both admin and manager groups are present"
      (auth-user/role-from-groups
       ["agiladmin-manager" "agiladmin-admin"]
       {:admin-group "agiladmin-admin"
        :manager-group "agiladmin-manager"})
      => "admin")

(fact "Manager group maps to the manager role"
      (auth-user/role-from-groups
       ["agiladmin-manager"]
       {:admin-group "agiladmin-admin"
        :manager-group "agiladmin-manager"})
      => "manager")

(fact "Unknown groups yield no role"
      (auth-user/role-from-groups
       ["everyone"]
       {:admin-group "agiladmin-admin"
        :manager-group "agiladmin-manager"})
      => nil)

(fact "Blank groups are ignored"
      (auth-user/role-from-groups
       ["" " " "agiladmin-admin"]
       {:admin-group "agiladmin-admin"
        :manager-group "agiladmin-manager"})
      => "admin")
