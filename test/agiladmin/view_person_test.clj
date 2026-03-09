(ns agiladmin.view-person-test
  (:require [agiladmin.view-person :as view-person]
            [midje.sweet :refer :all]))

(fact "Admin personnel view renders pending PocketBase users"
      (with-redefs [agiladmin.auth.core/list-pending-users
                    (fn []
                      [{:email "pending@example.org"
                        :name "Pending User"
                        :verified false}])]
        (let [response (view-person/list-all
                        {}
                        {:agiladmin {:admins ["admin@example.org"]
                                     :budgets {:path "test/assets/"}}}
                        {:email "admin@example.org"})]
          (:body response) => (contains "Pending User")
          (:body response) => (contains "pending@example.org"))))
