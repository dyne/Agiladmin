(ns agiladmin.view-timesheet-test
  (:require [agiladmin.view-timesheet :as view-timesheet]
            [hiccup.core :as hiccup]
            [failjure.core]
            [midje.sweet :refer :all]))

(fact "Timesheet upload form uses HTMX for progressive enhancement"
      (let [html (hiccup/html view-timesheet/upload-form)]
        html => (contains "hx-post=\"/timesheets/upload\"")
        html => (contains "id=\"timesheet-workspace\"")))

(fact "Timesheet upload rejects files above the configured size limit"
      (let [response (view-timesheet/upload
                      {:params {:file {:size 500001
                                       :filename "upload.xlsx"
                                       :tempfile "/tmp/upload.xlsx"}}}
                      {}
                      {:email "admin"})]
        (:body response) => (contains "File too big in upload.")))

(fact "Timesheet upload surfaces spreadsheet parse failures"
      (with-redefs [clojure.java.io/copy (fn [& _] nil)
                    clojure.java.io/delete-file (fn [& _] nil)
                    clojure.java.io/file
                    (fn
                      ([path]
                       (proxy [java.io.File] [path]
                         (exists [] (= path "/tmp/upload.xlsx"))))
                      ([parent child]
                       (proxy [java.io.File] [(str parent "/" child)]
                         (exists [] false))))
                    agiladmin.core/load-timesheet (fn [_]
                                                    (failjure.core/fail "Spreadsheet is invalid."))]
        (let [response (view-timesheet/upload
                        {:params {:file {:size 1024
                                         :filename "upload.xlsx"
                                         :tempfile "/tmp/upload.xlsx"}}}
                        {:agiladmin {:budgets {:path "budgets/"}}}
                        {:email "admin"})]
          (:body response) => (contains "Error parsing timesheet")
          (:body response) => (contains "Spreadsheet is invalid."))))

(fact "Timesheet upload explains when there is no historical file to diff against"
      (with-redefs [clojure.java.io/copy (fn [& _] nil)
                    clojure.java.io/delete-file (fn [& _] nil)
                    clojure.java.io/file
                    (fn
                      ([path]
                       (proxy [java.io.File] [path]
                         (exists [] (= path "/tmp/upload.xlsx"))))
                      ([parent child]
                       (proxy [java.io.File] [(str parent "/" child)]
                         (exists [] false))))
                    agiladmin.core/load-timesheet (fn [_] {:sheets []})
                    agiladmin.core/load-all-projects (fn [_] {})
                    agiladmin.core/map-timesheets (fn [& _] {:rows []})
                    agiladmin.graphics/to-table (fn [_] [:table "hours"])]
        (let [response (view-timesheet/upload
                        {:params {:file {:size 1024
                                         :filename "upload.xlsx"
                                         :tempfile "/tmp/upload.xlsx"}}}
                        {:agiladmin {:budgets {:path "budgets/"}}}
                        {:email "admin"})]
          (:body response) => (contains "This is a new timesheet, no historical information available to compare")
          (:body response) => (contains "Uploaded: upload.xlsx"))))

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

(fact "Timesheet submit reports a missing uploaded file"
      (with-redefs [clojure.java.io/file
                    (fn [path]
                      (proxy [java.io.File] [path]
                        (exists [] false)
                        (isDirectory [] false)))]
        (let [response (view-timesheet/commit
                        {:params {:path "/tmp/upload.xlsx"}}
                        {:agiladmin {:budgets {:path "budgets/"}}}
                        {:email "admin"})]
          (:body response) => (contains "Where is this file gone?! /tmp/upload.xlsx"))))

(fact "Timesheet submit archives the upload and renders the success page"
      (let [calls (atom [])]
        (with-redefs [clojure.java.io/file
                      (fn [path]
                        (proxy [java.io.File] [path]
                          (exists [] true)
                          (isDirectory [] (= path "budgets/"))))
                      agiladmin.view-timesheet/safe-load-repo
                      (fn [_] :gitrepo)
                      agiladmin.view-timesheet/archive-timesheet!
                      (fn [gitrepo path dst keypath req]
                        (swap! calls conj [gitrepo path dst keypath
                                           (get-in req [:session :auth])])
                        "upload.xlsx")
                      agiladmin.webpage/render-git-log
                      (fn [_] [:div "git log"])
                      agiladmin.utils/timesheet-to-name
                      (fn [_] "Upload User")
                      agiladmin.utils/now
                      (fn [] {:year 2026})]
          (let [response (view-timesheet/commit
                          {:params {:path "/tmp/upload.xlsx"}
                           :session {:auth {:name "Admin User"
                                            :email "admin@example.org"}}}
                          {:agiladmin {:budgets {:path "budgets/"
                                                 :ssh-key "id_rsa"}}}
                          {:email "admin@example.org"})]
            @calls => [[:gitrepo
                        "/tmp/upload.xlsx"
                        "budgets/upload.xlsx"
                        "id_rsa"
                        {:name "Admin User"
                         :email "admin@example.org"}]]
            (:body response) => (contains "Timesheet archived: upload.xlsx")
            (:body response) => (contains "Go back to Upload User")
            (:body response) => (contains "git log")))))
