(ns agiladmin.view-timesheet-test
  (:require [agiladmin.view-timesheet :as view-timesheet]
            [hiccup.core :as hiccup]
            [failjure.core]
            [midje.sweet :refer :all]))

(fact "Timesheet upload form uses HTMX for progressive enhancement"
      (let [html (hiccup/html view-timesheet/upload-form)]
        html => (contains "hx-post=\"/timesheets/upload\"")
        html => (contains "id=\"timesheet-workspace\"")
        html => (contains "class=\"flex items-end gap-3\"")
        html => (contains "Uploading and validating timesheet...")
        html => (contains "shrink-0")))

(fact "Timesheet upload rejects files above the configured size limit"
      (let [response (view-timesheet/upload
                      {:params {:file {:size 500001
                                       :filename "upload.xlsx"
                                       :tempfile "/tmp/upload.xlsx"}}}
                      {}
                      {:email "admin@example.org"
                       :name "Admin User"
                       :role "admin"})]
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
                        {:email "admin@example.org"
                         :name "Admin User"
                         :role "admin"})]
          (:body response) => (contains "Error parsing timesheet")
          (:body response) => (contains "Spreadsheet is invalid."))))

(fact "Timesheet upload returns a workspace fragment for HTMX parse failures"
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
                        {:headers {"hx-request" "true"}
                         :params {:file {:size 1024
                                         :filename "upload.xlsx"
                                         :tempfile "/tmp/upload.xlsx"}}}
                        {:agiladmin {:budgets {:path "budgets/"}}}
                        {:email "admin@example.org"
                         :name "Admin User"
                         :role "admin"})]
          (:body response) => (contains "id=\"timesheet-workspace\"")
          (:body response) => (contains "Error parsing timesheet")
          (:body response) =not=> (contains "<!DOCTYPE html>"))))

(fact "Member upload rejects a timesheet filename for another person"
      (with-redefs [clojure.java.io/copy (fn [& _] nil)
                    clojure.java.io/delete-file (fn [& _] nil)
                    clojure.java.io/file
                    (fn
                      ([path]
                       (proxy [java.io.File] [path]
                         (exists [] (= path "/tmp/2026_timesheet_B.Bob.xlsx"))))
                      ([parent child]
                       (proxy [java.io.File] [(str parent "/" child)]
                         (exists [] false))))]
        (let [response (view-timesheet/upload
                        {:params {:file {:size 1024
                                         :filename "2026_timesheet_B.Bob.xlsx"
                                         :tempfile "/tmp/upload.xlsx"}}}
                        {:agiladmin {:budgets {:path "budgets/"}}}
                        {:name "Alice Example"
                         :role nil})]
          (:body response) => (contains "Timesheet filename does not match the authenticated account")
          (:body response) => (contains "Expected A.Example"))))

(fact "Manager upload rejects a timesheet whose B3 owner does not match the account"
      (with-redefs [clojure.java.io/copy (fn [& _] nil)
                    clojure.java.io/delete-file (fn [& _] nil)
                    clojure.java.io/file
                    (fn
                      ([path]
                       (proxy [java.io.File] [path]
                         (exists [] (= path "/tmp/2026_timesheet_A.Example.xlsx"))))
                      ([parent child]
                       (proxy [java.io.File] [(str parent "/" child)]
                         (exists [] false))))
                    agiladmin.view-timesheet/load-timesheet-owner
                    (fn [_] "Bob Example")]
        (let [response (view-timesheet/upload
                        {:params {:file {:size 1024
                                         :filename "2026_timesheet_A.Example.xlsx"
                                         :tempfile "/tmp/upload.xlsx"}}}
                        {:agiladmin {:budgets {:path "budgets/"}}}
                        {:name "Alice Example"
                         :role "manager"})]
          (:body response) => (contains "Timesheet owner in cell B3 does not match the authenticated account")
          (:body response) => (contains "Alice Example"))))

(fact "Manager upload accepts a timesheet when filename and B3 owner both match"
      (with-redefs [clojure.java.io/copy (fn [& _] nil)
                    clojure.java.io/delete-file (fn [& _] nil)
                    clojure.java.io/file
                    (fn
                      ([path]
                       (proxy [java.io.File] [path]
                         (exists [] (= path "/tmp/2026_timesheet_A.Example.xlsx"))))
                      ([parent child]
                       (proxy [java.io.File] [(str parent "/" child)]
                         (exists [] false))))
                    agiladmin.view-timesheet/load-timesheet-owner
                    (fn [_] "Alice Example")
                    agiladmin.core/load-timesheet (fn [_] {:sheets []})
                    agiladmin.core/load-all-projects (fn [_] {})
                    agiladmin.core/map-timesheets (fn [& _] {:rows []})
                    agiladmin.graphics/to-table (fn [_] [:table "hours"])]
        (let [response (view-timesheet/upload
                        {:params {:file {:size 1024
                                         :filename "2026_timesheet_A.Example.xlsx"
                                         :tempfile "/tmp/upload.xlsx"}}}
                        {:agiladmin {:budgets {:path "budgets/"}}}
                        {:name "Alice Example"
                         :role "manager"})]
          (:body response) => (contains "Uploaded: 2026_timesheet_A.Example.xlsx")
          (:body response) => (contains "This is a new timesheet, no historical information available to compare"))))

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
                    agiladmin.view-timesheet/load-timesheet-owner (fn [_] "Admin User")
                    agiladmin.core/load-timesheet (fn [_] {:sheets []})
                    agiladmin.core/load-all-projects (fn [_] {})
                    agiladmin.core/map-timesheets (fn [& _] {:rows []})
                    agiladmin.graphics/to-table (fn [_] [:table "hours"])]
        (let [response (view-timesheet/upload
                        {:params {:file {:size 1024
                                         :filename "upload.xlsx"
                                         :tempfile "/tmp/upload.xlsx"}}}
                        {:agiladmin {:budgets {:path "budgets/"}}}
                        {:email "admin@example.org"
                         :name "Admin User"
                         :role "admin"})]
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
      (let [calls (atom [])
            invalidations (atom [])]
        (with-redefs [clojure.java.io/file
                      (fn [path]
                        (proxy [java.io.File] [path]
                          (exists [] true)
                          (isDirectory [] (= path "budgets/"))))
                      agiladmin.core/invalidate-timesheet-cache!
                      (fn [path]
                        (swap! invalidations conj path))
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
            @invalidations => ["budgets/"]
            (:body response) => (contains "Timesheet archived: upload.xlsx")
            (:body response) => (contains "Go back to Upload User")
            (:body response) => (contains "git log")))))

(fact "Timesheet submit does not invalidate the cache on repository errors"
      (let [invalidations (atom [])]
        (with-redefs [clojure.java.io/file
                      (fn [path]
                        (proxy [java.io.File] [path]
                          (exists [] true)
                          (isDirectory [] true)))
                      agiladmin.core/invalidate-timesheet-cache!
                      (fn [path]
                        (swap! invalidations conj path))
                      agiladmin.view-timesheet/safe-load-repo
                      (fn [_] nil)]
          (let [response (view-timesheet/commit
                          {:params {:path "/tmp/upload.xlsx"}}
                          {:agiladmin {:budgets {:path "budgets/"}}}
                          {:email "admin"})]
            @invalidations => []
            (:body response) => (contains "Timesheet submit is unavailable until the budgets directory is a git repository: budgets/")))))
