(ns agiladmin.view-timesheet-test
  (:require [agiladmin.view-timesheet :as view-timesheet]
            [midje.sweet :refer :all]))

(fact "Timesheet submit explains when the budgets directory is missing"
      (with-redefs [clojure.java.io/file
                    (fn [path]
                      (proxy [java.io.File] [path]
                        (exists [] (= path "/tmp/upload.xlsx"))
                        (isDirectory [] false)))]
        (let [response (view-timesheet/commit
                        {:params {:path "/tmp/upload.xlsx"}}
                        {:agiladmin {:budgets {:path "budgets/"}}}
                        {:email "admin"})]
          (:body response) => (contains "Timesheet submit is unavailable until the budgets directory exists: budgets/"))))

(fact "Timesheet submit explains when the budgets directory is not a git repository"
      (with-redefs [clojure.java.io/file
                    (fn [path]
                      (proxy [java.io.File] [path]
                        (exists [] true)
                        (isDirectory [] true)))
                    agiladmin.view-timesheet/safe-load-repo
                    (fn [_] nil)]
        (let [response (view-timesheet/commit
                        {:params {:path "/tmp/upload.xlsx"}}
                        {:agiladmin {:budgets {:path "budgets/"}}}
                        {:email "admin"})]
          (:body response) => (contains "Timesheet submit is unavailable until the budgets directory is a git repository: budgets/"))))
