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
   [clojure.java.io :as io]
   [agiladmin.core :refer :all]
   [agiladmin.utils :as util]
   [agiladmin.graphics :refer :all]
   [agiladmin.webpage :as web]
   [agiladmin.config :as conf]
   [taoensso.timbre :as log]
   [taoensso.nippy :as nippy]
   [cheshire.core :as json]
   [failjure.core :as f]
   [hiccup.form :as hf]
   [me.raynes.fs :as fs]
   [incanter.core :refer :all]
   [incanter.charts :refer :all]))

(def json-dataset-pp
  (json/create-pretty-printer
   (assoc json/default-pretty-print-options
          :line-break " "
          :indent-objects? false
          :indent-arrays? false)))

(defn- hours-prepare-diff [data]
  ;; TOOD: better format to enhance diff visuals
  (:rows data))

;; (defn json-visual-diff2 [left right]
;;   [:div {:class "timesheet-diff"}
;;    (loop [[r & right-sections] (:rows right)
;;           l (first (:rows left))
;;           c 0 ;; counter
;;           res []]
;;      (let [i [:div {:class "row"}]]
;;        (conj [[:div {:class "col-md-3 pull-left"}
;;                (-> r (json/generate-string {:pretty true})
;;                    web/highlight-json)]] res)
;;        (conj [[:div {:class "col-md-4" :id (str "visual" c)}]] res)
;;        (conj [[:div {:class "col-md-3"}
;;                (-> l (json/generate-string {:pretty true})
;;                    web/highligh-json)]] res)
;;        (if (empty? right-sections) (conj i res)
;;            (recur  right-sections (




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

(defn start []
  (web/render
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
               :name "submit" :value "submit"}]]]]))



(defn upload [config filename]
  (if (.exists (io/file filename))
    ;; load into dataset
    (f/attempt-all
     [ts (load-timesheet filename)
      all-pjs (load-all-projects config)
      hours (map-timesheets [ts])]

     (web/render
      [:div {:class "container-fluid"}

       [:div {:class "timesheet-dataset-contents"}
        [:h1 (str "Uploaded: " (fs/base-name filename))]
        (web/button-cancel-submit
         {:btn-group-class "pull-right"
          :cancel-url "/timesheets/cancel"
          :cancel-params
          (list (hf/hidden-field "tempfile" filename))
          :cancel-message (str "Upload operation canceled: " filename)
          :submit-url "/timesheets/submit"
          :submit-params
          (list
           (hf/hidden-field "hours" (nippy/freeze hours))
           (hf/hidden-field "path" filename))})]

       [:div {:class "container"}
        [:ul {:class "nav nav-pills"}
         [:li {:class "active"}
          [:a {:href "#diff" :data-toggle "pill" } "Differences"]]
         [:li [:a {:href "#content" :data-toggle "pill" } "Contents"]]]

        [:div {:class "tab-content clearfix"}

         ;; -------------------------------------------------------
         ;; DIFF (default tab
         [:div {:class "tab-pane fade in active" :id "diff"}
          [:h2 "Differences: new (to the left) and old (to the right)"]

          [:div {:class "col-md-3 timesheet-new-json pull-left"}
           (web/highlight-json
            (-> (:rows hours) (json/generate-string {:pretty true})))]

          (if (.exists (io/file (str (get-in config [:agiladmin :budgets :path])
                                     (fs/base-name filename))))
            ;; compare with old timesheet of same name
            (f/attempt-all
             [old-ts (load-timesheet
                      (str (get-in config [:agiladmin :budgets :path])
                           (fs/base-name filename)))
              old-hours (map-timesheets [old-ts])]
             (json-visual-diff old-hours hours)
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
          (to-table (sel hours :except-cols :name))]

         ]]])

     ;; handle failjure of timesheet loading from the uploaded file
     (f/when-failed [e]
       (web/render-error-page
        (log/spy :error ["Error parsing timesheet: " e]))))

    ;; uploaded file not existing
    (f/when-failed [e]
      (web/render-error-page
       (log/spy :error ["Uploaded file not found: " filename])))))
