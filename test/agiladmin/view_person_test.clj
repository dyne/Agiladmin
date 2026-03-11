(ns agiladmin.view-person-test
  (:require [agiladmin.view-person :as view-person]
            [clojure.data.json :as json]
            [failjure.core]
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

(fact "Personnel list rejects non-admin access"
      (let [response (view-person/list-all
                      {}
                      {:agiladmin {:admins ["admin@example.org"]
                                   :budgets {:path "test/assets/"}}}
                      {:email "user@example.org"})]
        (:body response) => (contains "Unauthorized access")))

(fact "Personnel list shows pending-user backend failures"
      (with-redefs [agiladmin.auth.core/list-pending-users
                    (fn []
                      (failjure.core/fail "PocketBase unavailable."))]
        (let [response (view-person/list-all
                        {}
                        {:agiladmin {:admins ["admin@example.org"]
                                     :budgets {:path "test/assets/"}}}
                        {:email "admin@example.org"})]
          (:body response) => (contains "Unable to load pending users: PocketBase unavailable."))))

(fact "Personnel download returns raw json when requested"
      (let [payload (json/write-str [["Date" "Hours"] ["2026-01" 10]])
            response (view-person/download
                      {:params {:costs payload
                                :format2 "json"}}
                      {}
                      {:email "admin@example.org"})]
        (get-in response [:headers "Content-Type"]) => "text/json; charset=utf-8"
        (:body response) => payload))

(fact "Personnel download returns csv when requested"
        (let [payload (json/write-str [["Date" "Hours"] ["2026-01" 10]])
            response (view-person/download
                      {:params {:costs payload
                                :format3 "csv"}}
                      {}
                      {:email "admin@example.org"})]
        (get-in response [:headers "Content-Type"]) => "text/plain; charset=utf-8"
        (:body response) => (contains "Date,Hours")))

(fact "Personnel start lets users access their own page"
      (with-redefs [agiladmin.view-person/list-person
                    (fn [config account person year]
                      {:status 200
                       :body (str person ":" year ":" (:email account))})]
        (let [response (view-person/start
                        {:params {:person "User Name"
                                  :year "2026"}}
                        {}
                        {:email "user@example.org"
                         :name "User Name"
                         :admin false})]
          (:status response) => 200
          (:body response) => "User Name:2026:user@example.org")))

(fact "Personnel start blocks users from viewing someone else"
      (let [response (view-person/start
                      {:params {:person "Other User"
                                :year "2026"}}
                      {}
                      {:email "user@example.org"
                       :name "User Name"
                       :admin false})]
        (:body response) => (contains "Unauthorized access")))
