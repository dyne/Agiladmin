(ns agiladmin.view-timesheet-test
  (:require [agiladmin.view-timesheet :as view-timesheet]
            [agiladmin.tabular :as tab]
            [clojure.java.io :as io]
            [hiccup.core :as hiccup]
            [failjure.core]
            [me.raynes.fs :as fs]
            [midje.sweet :refer :all]))

(fact "Timesheet upload form uses HTMX for progressive enhancement"
      (let [html (hiccup/html (view-timesheet/upload-form {}))]
        html => (contains "hx-post=\"/timesheets/upload\"")
        html => (contains "id=\"timesheet-workspace\"")
        html => (contains "class=\"flex items-end gap-3\"")
        html => (contains "data-upload-progress=\"true\"")
        html => (contains "data-upload-progress-label=\"true\"")
        html => (contains "data-skip-page-loading=\"true\"")
        html => (contains "Uploading and validating timesheet...")
        html => (contains "shrink-0")))

(fact "Timesheet upload form paths honor a configured base path"
      (let [html (hiccup/html (view-timesheet/upload-form
                               {:agiladmin {:webserver {:base-path "/admin"}}}))]
        html => (contains "action=\"/admin/timesheets/upload\"")
        html => (contains "hx-post=\"/admin/timesheets/upload\"")))

(fact "Timesheet diff model tracks added removed changed and unchanged rows"
      (let [old-hours (tab/dataset
                       [{:month "2026-1" :project "ALPHA" :task "A" :tag "" :hours 10}
                        {:month "2026-2" :project "ALPHA" :task "B" :tag "" :hours 5}
                        {:month "2026-3" :project "BETA" :task "C" :tag "VOL" :hours 4}])
            new-hours (tab/dataset
                       [{:month "2026-1" :project "ALPHA" :task "A" :tag "" :hours 12}
                        {:month "2026-3" :project "BETA" :task "C" :tag "VOL" :hours 4}
                        {:month "2026-4" :project "GAMMA" :task "D" :tag "" :hours 8}])
            model (#'agiladmin.view-timesheet/timesheet-diff-model old-hours new-hours)]
        (get-in model [:summary :changed]) => 1
        (get-in model [:summary :unchanged]) => 1
        (get-in model [:summary :removed]) => 1
        (get-in model [:summary :added]) => 1
        (get-in model [:summary :total-delta]) => 5.0
        (->> (:rows model)
             (map (juxt :status :old-hours :new-hours))
             vec)
        => [[:changed 10 12]
            [:removed 5 nil]
            [:unchanged 4 4]
            [:added nil 8]]))

(fact "Timesheet diff render shows summary and side-by-side columns"
      (let [old-hours (tab/dataset
                       [{:month "2026-1" :project "ALPHA" :task "A" :tag "" :hours 10}
                        {:month "2026-2" :project "ALPHA" :task "B" :tag "" :hours 5}])
            new-hours (tab/dataset
                       [{:month "2026-1" :project "ALPHA" :task "A" :tag "" :hours 12}
                        {:month "2026-3" :project "GAMMA" :task "C" :tag "" :hours 8}])
            html (hiccup/html (#'agiladmin.view-timesheet/timesheet-diff old-hours new-hours))]
        html => (contains "Added")
        html => (contains "Removed")
        html => (contains "Changed")
        html => (contains "Old hours")
        html => (contains "New hours")
        html => (contains "Delta")
        html => (contains "2026-1")
        html => (contains "2026-2")
        html => (contains "2026-3")))

(fact "Timesheet diff render shows an explicit message when there are no differences"
      (let [hours (tab/dataset
                   [{:month "2026-1" :project "ALPHA" :task "A" :tag "" :hours 10}])
            html (hiccup/html (#'agiladmin.view-timesheet/timesheet-diff hours hours))]
        html => (contains "No differences found between the archived timesheet and this upload.")))

(fact "Timesheet upload rejects files above the default size limit"
      (let [response (view-timesheet/upload
                      {:params {:file {:size 500001
                                       :filename "upload.xlsx"
                                       :tempfile "/tmp/upload.xlsx"}}}
                      {}
                      {:email "admin@example.org"
                       :name "Admin User"
                       :role "admin"})]
        (:body response) => (contains "Maximum size is 500000 bytes.")))

(fact "Timesheet upload accepts files below a custom configured size limit"
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
                        {:params {:file {:size 1499
                                         :filename "upload.xlsx"
                                         :tempfile "/tmp/upload.xlsx"}}}
                        {:agiladmin {:webserver {:upload-max-size 1500}
                                     :budgets {:path "budgets/"}}}
                        {:email "admin@example.org"
                         :name "Admin User"
                         :role "admin"})]
          (:body response) => (contains "Uploaded: upload.xlsx"))))

(fact "Timesheet upload rejects files above a custom configured size limit"
      (let [response (view-timesheet/upload
                      {:params {:file {:size 1501
                                       :filename "upload.xlsx"
                                       :tempfile "/tmp/upload.xlsx"}}}
                      {:agiladmin {:webserver {:upload-max-size 1500}}}
                      {:email "admin@example.org"
                       :name "Admin User"
                       :role "admin"})]
        (:body response) => (contains "Maximum size is 1500 bytes.")))

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

(fact "Timesheet upload accepts a real xlsx workbook fixture"
      (let [temp-root (.toFile (java.nio.file.Files/createTempDirectory "agiladmin-upload-test"
                                                                       (make-array java.nio.file.attribute.FileAttribute 0)))
            upload-path (str temp-root "/fixture-upload.xlsx")
            budgets-path (str temp-root "/budgets/")]
        (.mkdirs (io/file budgets-path))
        (io/copy (io/file "test/assets/2016_timesheet_Luca-Pacioli.xlsx")
                 (io/file upload-path))
        (try
          (let [response (view-timesheet/upload
                          {:params {:file {:size (.length (io/file upload-path))
                                           :filename "2016_timesheet_Luca-Pacioli.xlsx"
                                           :tempfile (io/file upload-path)}}}
                          {:agiladmin {:budgets {:path budgets-path}}}
                          {:name "Admin User"
                           :role "admin"})]
            (:body response) => (contains "Uploaded: 2016_timesheet_Luca-Pacioli.xlsx")
            (:body response) => (contains "Contents of the new timesheet")
            (:body response) => (contains "Timesheet changes")
            (:body response) => (contains "This is a new timesheet, no historical information available to compare")
            (:body response) =not=> (contains "Error parsing timesheet"))
          (finally
            (fs/delete-dir temp-root)))))

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

(fact "Timesheet commit identity derives name from email when account name is blank"
      (#'agiladmin.view-timesheet/git-commit-identity
       {:session {:auth {:name "  "
                         :email "manager@example.org"}}})
      => {:name "manager"
          :email "manager@example.org"})

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
