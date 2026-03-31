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
   [agiladmin.core :as core]
   [agiladmin.tabular :as tab]
   [agiladmin.utils :as util]
   [agiladmin.graphics :refer :all]
   [agiladmin.webpage :as web]
   [agiladmin.config :as conf]
   [agiladmin.session :as s]
   [taoensso.timbre :as log]
   [failjure.core :as f]
   [hiccup.form :as hf]
   [me.raynes.fs :as fs]
   [clj-jgit.porcelain :as git]
   [dk.ative.docjure.spreadsheet :refer [load-workbook sheet-seq]]))

(def workspace-id "timesheet-workspace")

(defn- workspace
  [body]
  [:div {:id workspace-id :class "space-y-6"} body])

(defn upload-card
  []
  [:div {:class "card mx-auto max-w-3xl bg-base-100 shadow-xl"}
   [:div {:class "card-body gap-4"}
    [:h1 {:class "card-title text-3xl"} "Upload a new timesheet"]
    [:p "Choose the file in your computer and click 'Submit' to proceed to validation."]
    [:form {:action "/timesheets/upload"
            :method "post"
            :class "space-y-4"
            :enctype "multipart/form-data"
            :hx-post "/timesheets/upload"
            :hx-target (str "#" workspace-id)
            :hx-swap "outerHTML"
            :hx-encoding "multipart/form-data"}
     [:div {:class "flex items-end gap-3"}
      [:input {:name "file"
               :type "file"
               :class "file-input file-input-bordered w-full"}]
      [:input {:class "btn btn-primary btn-lg shrink-0"
               :id "field-submit" :type "submit"
               :name "submit" :value "submit"}]]]]])

(defn- render-workspace
  [request account body]
  (let [fragment (workspace body)]
    (if (web/htmx-request? request)
      (web/render-fragment fragment)
      (web/render account fragment))))

(defn- normalize-full-name
  "Normalize a full name for exact member ownership checks."
  [name]
  (some-> name
          str
          str/trim
          (str/replace #"\s+" " ")
          str/lower-case))

(defn- filename-owner
  "Extract the owner token from a timesheet filename."
  [filename]
  (some-> filename util/timesheet-to-name (str/replace #"-" " ")))

(defn- load-timesheet-owner
  "Read the full owner name from cell B3 of an uploaded timesheet."
  [path]
  (f/attempt-all
   [workbook (or (try
                   (load-workbook path)
                   (catch Exception ex
                     (log/error ["Error in load-workbook:" (-> ex Throwable->map :cause)])))
                 (f/fail (str "Error loading timesheet owner: " path)))
    sheet (or (first (sheet-seq workbook))
              (f/fail (str "Timesheet has no worksheets: " path)))
    full-name (let [value (some-> (get-cell sheet 'B 3) str str/trim)]
                (if (str/blank? value)
                  (f/fail "Timesheet owner is missing in cell B3.")
                  value))]
   full-name))

(defn- require-upload-ownership
  "Ensure members and managers can upload only their own timesheets."
  [account filename path]
  (if (s/admin? account)
    true
    (f/attempt-all
     [account-name (or (:name account)
                       (f/fail "Authenticated account is missing :name."))
      uploaded-name (or (filename-owner filename)
                        (f/fail (str "Invalid timesheet filename: " filename)))
      _ (if (util/namecmp uploaded-name account-name)
          true
          (f/fail
           (str "Timesheet filename does not match the authenticated account. "
                "Expected " (util/dotname account-name)
                " in the uploaded filename.")))
      owner-name (load-timesheet-owner path)
      _ (if (= (normalize-full-name owner-name)
               (normalize-full-name account-name))
          true
          (f/fail
           (str "Timesheet owner in cell B3 does not match the authenticated account. "
                "Expected " account-name ".")))]
     true)))

(defn- action-form
  [request url label fields class-name]
  (let [attrs (cond-> {:action url
                       :method "post"
                       :class "inline-flex"}
                (web/htmx-request? request)
                (assoc :hx-post url
                       :hx-target (str "#" workspace-id)
                       :hx-swap "outerHTML"))]
    (into
     [:form attrs]
     (concat fields
             [[:input {:type "submit" :value label :class class-name}]]))))

(defn textual-diff [left right]
  [:div {:class "grid gap-4 lg:grid-cols-2"}
   [:div {:class "rounded-box border border-base-300 bg-base-100 p-4 shadow-sm"}
    [:pre (str "\n" (with-out-str (print left)))]]
   [:div {:class "rounded-box border border-base-300 bg-base-100 p-4 shadow-sm"}
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

(def upload-form
  (workspace
   (upload-card)))

(defn cancel [request config account]
  (f/if-let-ok? [tempfile (s/param request :tempfile)]
    (render-workspace
     request
     account
     [:div {:class "space-y-4"}
      [:div {:class "alert alert-warning shadow-sm" :role "alert"}
       [:span (str "Canceled upload of timesheet: " tempfile " ")]
       [:span (str "("
                   (if-not (str/blank? tempfile) (io/delete-file tempfile))
                   ")")]]
      upload-form])
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
      (let [_ (io/copy tempfile (io/file "/tmp" filename))
            path (str "/tmp/" filename)]
        (io/delete-file tempfile)
        (if (not (.exists (io/file path)))
          (web/render-error-page
           (log/spy :error
                    [:h1 (str "Uploaded file not found: " filename)]))
          ;; else load into dataset
          (f/attempt-all
           [_ (require-upload-ownership account filename path)
            ts (load-timesheet path)
            hours (map-timesheets [ts])]
           (render-workspace
            request
            account
            [:div {:class "space-y-4 timesheet-dataset-contents"}
             [:div {:class "flex flex-wrap items-center gap-3 rounded-box border border-info/30 bg-info/10 p-4 text-info-content shadow-sm"}
              [:span {:class "font-semibold"} (str "Uploaded: " (fs/base-name path))]
              [:div {:class "ml-auto flex flex-wrap gap-3"}
               (action-form request
                            "/timesheets/cancel"
                            "Cancel"
                            [(hf/hidden-field "tempfile" path)]
                            "btn btn-error btn-lg")
               (action-form request
                            "/timesheets/submit"
                            "Submit"
                            [(hf/hidden-field "path" path)]
                            "btn btn-success btn-lg")]]
             (web/tabs
              "timesheet-upload"
              [{:id "diff"
                :title "Differences"
                :content [:div {:class "space-y-4"}
                          [:h2 {:class "text-2xl font-semibold"} "Differences: old (to the left) and new (to the right)"]
                          (if (.exists
                               (io/file (str (conf/q config
                                                     [:agiladmin :budgets :path])
                                             (fs/base-name filename))))
                            (f/attempt-all
                             [old-ts
                              (load-timesheet
                               (str (conf/q config [:agiladmin :budgets :path])
                                    (fs/base-name filename)))
                              old-hours (map-timesheets [old-ts])]
                             (textual-diff old-hours hours)
                             (f/when-failed [e]
                               (web/render-error
                                (log/spy :error ["Error parsing old timesheet: " e]))))
                            [:div {:class "alert alert-info shadow-sm" :role "alert"}
                             "This is a new timesheet, no historical information available to compare"])]}
               {:id "content"
                :title "Contents"
                :content [:div {:class "space-y-4"}
                          [:h2 {:class "text-2xl font-semibold"} "Contents of the new timesheet"]
                          [:div {:class "overflow-x-auto"}
                           (to-table (tab/drop-cols hours [:name]))]]}])])
     ;; handle failjure of timesheet loading from the uploaded file
     (f/when-failed [e]
       (web/render-error-page
        (log/spy :error [:div
                         [:h1 "Error parsing timesheet"]
                         (web/render-yaml e)])))))))))

(defn- render-commit-message
  ([message]
   (render-commit-message nil nil message))
  ([request account message]
   (let [body [:div {:class "alert alert-info shadow-sm"} message]]
     (if request
       (render-workspace request account body)
       (web/render body)))))

(defn- safe-load-repo
  [repo]
  (try
    (git/load-repo repo)
    (catch Exception ex
      (log/error [:p "Error in git/load-repo: " ex])
      nil)))

(defn- archive-timesheet!
  [gitrepo path dst keypath req]
  (let [base-path (fs/base-name dst)]
    (io/copy (io/file path) (io/file dst))
    (io/delete-file path)
    (git/git-add gitrepo base-path)
    (git/git-status gitrepo)
    (git/git-commit
     gitrepo
     (str "Updated timesheet " base-path)
     {:name (get-in req [:session :auth :name])
      :email (get-in req [:session :auth :email])})
    (git/with-identity {:name keypath :exclusive true}
      (git/git-push gitrepo))
    base-path))

(defn commit [req conf acct]
  (let [path (s/param req :path)]
    (if (.exists (io/file path))
      (let [repo (conf/q conf [:agiladmin :budgets :path])
            dst (str repo (fs/base-name path))]
        (if (not (and (seq repo) (.isDirectory (io/file repo))))
          (render-commit-message req acct
           (str "Timesheet submit is unavailable until the budgets directory exists: " repo))
          (if-let [gitrepo (safe-load-repo repo)]
            (let [keypath (conf/q conf [:agiladmin :budgets :ssh-key])
                  base-path (archive-timesheet! gitrepo path dst keypath req)]
              (core/invalidate-timesheet-cache! repo)
              (render-workspace
               req
               acct
               [:div {:class "space-y-4"}
                [:h1 {:class "text-3xl font-semibold"} dst]
                [:div {:class "space-y-4"}
                 [:p (str "Timesheet archived: " base-path)]
                 (let [pname (util/timesheet-to-name base-path)
                       year (:year (util/now))]
                   (web/button "/person" (str "Go back to " pname)
                               (list (hf/hidden-field "person" pname)
                                     (hf/hidden-field "year" year))))
                 [:h3 {:class "text-2xl font-semibold"} "Log of recent changes:"]
                 (web/render-git-log gitrepo)]]))
            (render-commit-message req acct
             (str "Timesheet submit is unavailable until the budgets directory is a git repository: " repo)))))
      ;; else
      (web/render-error-page
       (str "Where is this file gone?! " path)))))
