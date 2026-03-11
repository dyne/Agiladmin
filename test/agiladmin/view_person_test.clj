(ns agiladmin.view-person-test
  (:require [agiladmin.view-person :as view-person]
            [clojure.data.json :as json]
            [failjure.core]
            [midje.sweet :refer :all]))

(fact "Admin personnel view renders a compact filterable persons list"
      (with-redefs [agiladmin.utils/now (fn [] {:year 2026})
                    agiladmin.utils/list-files-matching
                    (fn [_ _]
                      [(java.io.File. "2026_timesheet_Ada-Lovelace.xlsx")
                       (java.io.File. "2026_timesheet_Grace-Hopper.xlsx")])]
        (let [response (view-person/list-all
                        {}
                        {:agiladmin {:budgets {:path "ignored/"}}}
                        {:email "admin@example.org"
                         :role "admin"})]
          (:body response) => (contains "data-text-filter=\"persons-list\"")
          (:body response) => (contains "Filter persons")
          (:body response) => (contains "Clear Persons filter")
          (:body response) => (contains "data-text-filter-value=\"Ada-Lovelace\"")
          (:body response) => (contains "inline-flex max-w-full w-full")
          (:body response) => (contains "/static/img/dyne-icon-black.svg")
          (:body response) => (contains "data-theme-toggle=\"true\"")
          (:body response) => (contains "/static/img/dyne-logotype-black.svg")
          (:body response) =not=> (contains "For enquiries please contact")
          (:body response) =not=> (contains "Newcomers"))))

(fact "Personnel list rejects non-admin access"
      (let [response (view-person/list-all
                      {}
                      {:agiladmin {:budgets {:path "test/assets/"}}}
                      {:email "user@example.org"
                       :role nil})]
        (:body response) => (contains "Unauthorized access")))

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
                         :role nil})]
          (:status response) => 200
          (:body response) => "User Name:2026:user@example.org")))

(fact "Personnel start falls back to the session user and current year when params are missing"
      (with-redefs [agiladmin.utils/now (fn [] {:year 2026})
                    agiladmin.view-person/list-person
                    (fn [config account person year]
                      {:status 200
                       :body (str person ":" year ":" (:email account))})]
        (let [response (view-person/start
                        {:params {}}
                        {}
                        {:email "user@example.org"
                         :name "User Name"
                         :role nil})]
          (:status response) => 200
          (:body response) => "User Name:2026:user@example.org")))

(fact "Personnel start blocks users from viewing someone else"
      (let [response (view-person/start
                      {:params {:person "Other User"
                                :year "2026"}}
                      {}
                      {:email "user@example.org"
                       :name "User Name"
                       :role nil})]
        (:body response) => (contains "Unauthorized access")))

(fact "Admin personnel view ignores xlsx files that do not match the timesheet naming pattern"
      (with-redefs [agiladmin.utils/now (fn [] {:year 2026})
                    agiladmin.utils/list-files-matching
                    (fn [_ _]
                      [(java.io.File. "2026_timesheet_User-Name.xlsx")
                       (java.io.File. "notes_timesheet_backup.xlsx")])
                    agiladmin.auth.core/list-pending-users
                    (fn [] [])]
        (let [response (view-person/list-all
                        {}
                        {:agiladmin {:budgets {:path "ignored/"}}}
                        {:email "admin@example.org"
                         :role "admin"})]
          (:body response) => (contains "User-Name")
          (:body response) =not=> (contains "notes_timesheet_backup"))))
