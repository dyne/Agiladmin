;; Copyright (C) 2015-2018 Dyne.org foundation

;; Sourcecode designed, written and maintained by
;; Denis Roio <jaromil@dyne.org>

;; This program is free software: you can redistribute it and/or modify
;; it under the terms of the GNU Affero General Public License as published by
;; the Free Software Foundation, either version 3 of the License, or
;; (at your option) any later version.

;; This program is distributed in the hope that it will be useful,
;; but WITHOUT ANY WARRANTY; without even the implied warranty of
;; MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
;; GNU Affero General Public License for more details.

;; You should have received a copy of the GNU Affero General Public License
;; along with this program.  If not, see <http://www.gnu.org/licenses/>.

(ns agiladmin.view-timesheet
  (:require
   [clojure.string :as str]
   [clojure.java.io :as io]
   [agiladmin.core :refer :all]
   [agiladmin.utils :as util]
   [agiladmin.graphics :refer :all]
   [agiladmin.webpage :as web]
   [agiladmin.config :as conf]
   [agiladmin.session :as s]
   [taoensso.timbre :as log]
   [cheshire.core :as json]
   [failjure.core :as f]
   [hiccup.form :as hf]
   [me.raynes.fs :as fs]
   [clj-jgit.porcelain :as git]
   [incanter.core :refer [sel]]))

(def json-dataset-pp
  (json/create-pretty-printer
   (assoc json/default-pretty-print-options
          :line-break " "
          :indent-objects? false
          :indent-arrays? false)))

(defn textual-diff [left right]
  [:div {:class "row"}
   [:div {:class "col-md-5"}
    [:pre (str "\n" (with-out-str (print left)))]]
   [:div {:class "col-md-5"}
    [:pre {:id "display"}]
    [:script
     (str "\n"
          "function dodiff() {\n"
          "var left = `" (with-out-str (print left)) "`;\n"
          "var right = `" (with-out-str (print right)) "`;\n"
          "var color = '', span = null;\n"
          "var diff = JsDiff.diffLines(left, right);\n"
          "var display = document.getElementById('display')\n"
          "var fragment = document.createDocumentFragment();\n"
          "diff.forEach(function(part){\n
  color = part.added ? 'green' : part.removed ? 'red' : 'darkgrey';\n
  span = document.createElement('span');\n
  span.style.color = color;\n
  span.appendChild(document.createTextNode(part.value));\n
  fragment.appendChild(span);\n
});\n
display.appendChild(fragment);\n
}\n
window.onload = dodiff;\n")]]])

(defn- hours-prepare-diff [data] (:rows data))
(defn json-visual-diff [left right]
  [:div {:class "timesheet-diff"}
   [:div {:class "col-md-3 timesheet-old-json"}
    (web/highlight-json
     (-> (:rows right) (json/generate-string {:pretty true})))]
   [:div {:class "col-md-4" :id "visual"}]
   [:script
    (str "\n"
         "function jsondiff() {\n"
         " var left = " (-> left hours-prepare-diff json/generate-string) ";\n"
         " var right = " (-> right hours-prepare-diff json/generate-string) ";\n"
         " var delta = jsondiffpatch.diff(left,right);\n"
         " jsondiffpatch.formatters.html.hideUnchanged();"
         " document.getElementById('visual').innerHTML = jsondiffpatch.formatters.html.format(delta, left);\n}\n"
         "window.onload = jsondiff;\n")]])

(def upload-form
  [:div {:class "container-fluid"}
   [:h1 "Upload a new timesheet"]
   [:p " Choose the file in your computer and click 'Submit' to
proceed to validation."]
   [:div {:class "form-group"}
    [:form {:action "/timesheets/upload" :method "post"
            :class "form-shell"
            :enctype "multipart/form-data"}
     [:fieldset {:class "fieldset btn btn-default btn-file btn-lg"}
      [:input {:name "file" :type "file"}]]
     ;; [:fieldset {:class "fieldset-submit"}
     [:input {:class "btn btn-primary btn-lg"
              :id "field-submit" :type "submit"
              :name "submit" :value "submit"}]]]])

(defn cancel [request config account]
  (f/if-let-ok? [tempfile (s/param request :tempfile)]
    (web/render
     account
     [:div {:class (str "alert alert-danger") :role "alert"}
      (str "Canceled upload of timesheet: " tempfile " ")
      (str "("
           (if-not (str/blank? tempfile) (io/delete-file tempfile))
           ")")])
    (web/render-error-page (f/message tempfile))))

(defn upload [request config account]
  (let
      [tempfile (get-in request [:params :file :tempfile])
       filename (get-in request [:params :file :filename])
       params   (:params request)]
    (cond
      (> (get-in params [:file :size]) 500000)
      ;; max upload size in bytes
      ;; TODO: put in config
      (web/render-error-page params "File too big in upload.")
      :else
      (let [file (io/copy tempfile (io/file "/tmp" filename))
            path (str "/tmp/" filename)]
        (io/delete-file tempfile)
        (if (not (.exists (io/file path)))
          (web/render-error-page
           (log/spy :error
                    [:h1 (str "Uploaded file not found: " filename)]))
          ;; else load into dataset
          (f/attempt-all
           [ts (load-timesheet path)
            all-pjs (load-all-projects config)
            hours (map-timesheets [ts])]
           (web/render
            account
            [:div {:class "container-fluid"}
             [:div {:class "timesheet-dataset-contents"}
              [:div {:class "alert alert-info"}
               (str "Uploaded: " (fs/base-name path))]
              ;; TODO: do not visualise submit button if diff is equal
              (web/button-cancel-submit
               {:btn-group-class "pull-right"
                :cancel-url "/timesheets/cancel"
                :cancel-params
                (list (hf/hidden-field "tempfile" path))
                :cancel-message (str "Upload operation canceled: " filename)
                :submit-url "/timesheets/submit"
                :submit-params
                (list (hf/hidden-field "path" path))})
              [:div {:class "container"}
               [:ul {:class "nav nav-pills"}
                [:li {:class "active"}
                 [:a {:href "#diff" :data-toggle "pill" } "Differences"]]
                [:li [:a {:href "#content" :data-toggle "pill" } "Contents"]]]
               [:div {:class "tab-content clearfix"}
          ;; -------------------------------------------------------
          ;; DIFF (default tab
          [:div {:class "tab-pane fade in active" :id "diff"}
           [:h2 "Differences: old (to the left) and new (to the right)"]
           (if (.exists
                (io/file (str (conf/q config
                                      [:agiladmin :budgets :path])
                              (fs/base-name filename))))
             ;; compare with old timesheet of same name
             (f/attempt-all
              [old-ts
               (load-timesheet
                (str (conf/q config [:agiladmin :budgets :path])
                     (fs/base-name filename)))
               old-hours (map-timesheets [old-ts])]
              ;; ---------------
              (textual-diff old-hours hours)
              (f/when-failed [e]
                (web/render-error
                 (log/spy :error ["Error parsing old timesheet: " e]))))
             ;; else - this timesheet did not exist before (new year)
             [:div {:class "alert alert-info" :role "alert"}
              "This is a new timesheet, no historical information available to compare"])]
          ;; -------------------------------------------------------
          ;; CONTENT tab
          [:div {:class "tab-pane fade" :id "content"}
           [:h2 "Contents of the new timesheet"]
           (to-table (sel hours :except-cols :name))]]]]])
     ;; handle failjure of timesheet loading from the uploaded file
     (f/when-failed [e]
       (web/render-error-page
        (log/spy :error [:div
                         [:h1 "Error parsing timesheet"]
                         (web/render-yaml e)])))))))))

(defn commit [req conf acct]
  (let [path (s/param req :path)]
    (if (.exists (io/file path))
      (let [repo (conf/q conf [:agiladmin :budgets :path])
            dst (str repo (fs/base-name path))]
        (web/render
         [:div {:class "container-fluid"}
          [:h1 dst ]
          (io/copy (io/file path) (io/file dst))
          (io/delete-file path)
          (let
              [base_path (fs/base-name dst)
               gitrepo  (git/load-repo repo)
               dircache (git/git-add gitrepo base_path)
               gitstatus (git/git-status gitrepo)
               gitcommit (git/git-commit
                          gitrepo
                          (str "Updated timesheet " base_path)
                          {:name (get-in req [:session :auth  :name])
                           :email (get-in req [:session :auth :email])})
               keypath (conf/q conf [:agiladmin :budgets :ssh-key])]
            (git/with-identity {:name keypath :exclusive true}
              (git/git-push gitrepo))
            [:div
             [:p (str "Timesheet archived: " base_path)]
             ;; button to quickly move back to person
             (let [pname (util/timesheet-to-name base_path)
                   year  (:year (util/now))]
               (web/button "/person" (str "Go back to " pname)
                           (list (hf/hidden-field "person" pname)
                                 (hf/hidden-field "year" year))))
             [:h3 "Log of recent changes:"]
             (web/render-git-log gitrepo)])]))
      ;; else
      (web/render-error-page
       (str "Where is this file gone?! " path)))))
