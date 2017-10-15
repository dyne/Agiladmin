;; Agiladmin - spreadsheet based time and budget administration

;; Copyright (C) 2016-2017 Dyne.org foundation

;; Sourcecode written and maintained by Denis Roio <jaromil@dyne.org>
;; designed in cooperation with Manuela Annibali <manuela@dyne.org>

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

(ns agiladmin.views
  (:require
   [clojure.java.io :as io]
   [clojure.pprint :refer [pprint]]
   [agiladmin.core :refer :all]
   [agiladmin.utils :as util]
   [agiladmin.graphics :refer :all]
   [agiladmin.webpage :as web]
   [agiladmin.config :as conf]
   [auxiliary.string :refer [strcasecmp]]
   [taoensso.timbre :as log]
   [cheshire.core :as json]
   [hiccup.form :as hf]
   [json-html.core :as present]
   [incanter.core :refer :all]
   [incanter.charts :refer :all]
   [clj-jgit.porcelain :refer :all]
   [clj-jgit.querying  :refer :all]))

(defn index-log-view [config request]
  (let [repo (load-repo "budgets")]
    (web/render
     [:div {:class "row-fluid"}

      [:div {:class "projects col-lg-4"}

       [:h2 "Projects"]
       ;; list all projects
       (for [f (->> (util/list-files-matching "budgets" #"budget.*xlsx$")
                    (map #(.getName %)))]
         [:div {:class "row log-project"}
          [:div {:class "col-lg-4"}
           (web/button config "/project" (util/proj-name-from-path f)
                       (hf/hidden-field "project" f))]])

       [:h2 "People"]
       ;; list all people
       (for [f (->> (util/list-files-matching
                     "budgets" #".*_timesheet_.*xlsx$")
                    (map #(second
                           (re-find util/regex-timesheet-to-name
                                    (.getName %)))) sort distinct)]
         ;; (map #(.getName %)) distinct)]
         [:div {:class "row log-person"}
          [:div {:class "col-lg-4"}
           (web/button config "/person" f
                       (list (hf/hidden-field "person" f)
                             (hf/hidden-field "year" 2017)))]])
       ]

      [:div {:class "commitlog col-lg-6"}
       (web/button config "/pull" (str "Pull updates from " (:git config)))
       (->> (git-log repo)
            (map #(commit-info repo %))
            (map #(select-keys % [:author :message :time :changed_files]))
            present/edn->html)]])))

(defn project-view [config request]
  (let [projfile      (get-in request [:params :project])
        projname      (util/proj-name-from-path projfile)
        project-conf  (conf/load-project config projname)
        ts-path       (get-in config [:agiladmin :budgets :path])
        timesheets    (load-all-timesheets ts-path #".*_timesheet_.*xlsx$")
        project-hours (load-project-monthly-hours timesheets projname)
        monthly-costs (derive-costs project-hours config project-conf)]

    ;; write the budget file with updated hours
    (write-workbook-sheet (str "budgets/" projfile) "Personnel hours"
                          ($order :month :asc project-hours))

    (web/render
     [:div {:style "container-fluid"}
      (if-not (empty? (-> project-conf
                          (get-in [(keyword projname) :tasks])))
        [:div {:style "container-fluid"}
         [:h1 projname
          [:button {:class "pull-right btn btn-info"
                    :onclick "toggleMode(this)"} "Scale to Fit"]]
         ;; GANTT chart
         [:div {:class "row-fluid"
                :style "width:100%; min-height:20em; position: relative;" :id "gantt"}]
         [:script {:type "text/javascript"}
         (str (slurp (io/resource "gantt-loader.js")) "
var tasks = { data:" (-> project-conf
                         (get-in [(keyword projname) :tasks])
                         json/generate-string) "};
gantt.init('gantt');
gantt.parse(tasks);
")]]
        ;; else
        [:h1 projname])

      [:div {:class "row-fluid"}
       ;; --- CHARTS
       ;; time series
       (with-data
         (->> ($rollup :sum :hours :month project-hours)
              ($order :month :asc))
         [:div {:class "col-lg-6"}
          (chart-to-image (bar-chart :month :hours :group-by :month :legend false))])
       ;; (time-series-plot
       ;;                           (date-to-ts $data :month)
       ;;                           ($ :hours)))])

       ;; pie chart
       (with-data ($rollup :sum :hours :name project-hours)
         [:div {:class "col-lg-6"}
          (chart-to-image
           (pie-chart ($ :name)
                      ($ :hours)
                      :legend true
                      :title (str projname " hours used")))
          [:h2 {:class "text-center"}
           (str "Total hours: " (sum ($ :hours project-hours)))]])]


      [:div {:class "row-fluid"}
       [:h1 "Switch to different views on project"]
       [:div {:class "container"}
        [:ul {:class "nav nav-pills"}

         [:li {:class "active"}
          [:a {:href "#task-sum-hours" :data-toggle "pill" }
           "Task/Person totals"]]

         [:li [:a {:href "#task-totals" :data-toggle "pill" }
               "Task totals"]]

         [:li [:a {:href "#person-totals" :data-toggle "pill" }
               "Person totals"]]

         [:li [:a {:href "#monthly-details" :data-toggle "pill" }
               "Monthly details"]]]

        [:div {:class "tab-content clearfix"}

         [:div {:class "tab-pane fade in active" :id "task-sum-hours"}
          [:h2 "Totals grouped per person and per task"]
          (-> (aggregate [:hours :cost] [:name :task] :dataset monthly-costs)
              ;; (derive-costs config project-conf)
              (sel :cols [:name :task :hours :cost]) to-table)]

         [:div {:class "tab-pane fade" :id "task-totals"}
          [:h2 "Totals per task"]
          (-> (aggregate [:hours :cost] :task :dataset monthly-costs)
              ;; (derive-costs config project-conf)
              (sel :cols [:task :hours :cost]) to-table)]

         [:div {:class "tab-pane fade" :id "person-totals"}
          [:h2 "Totals per person"]
          (-> (aggregate [:hours :cost] :name :dataset monthly-costs)
              (sel :cols [:name :hours :cost]) to-table)]

         [:div {:class "tab-pane fade" :id "monthly-details"}
          [:h2 "Detail of monthly hours used per person on each task"]
          (-> ($order :month :desc monthly-costs)
              to-table)]]

        [:div [:h2 "State of budget repository"]
         (present/edn->html
          (-> (load-repo "budgets") git-status))]]]])))

(defn person-view [config request]
  (let [person (get-in request [:params :person])
        year   (get-in request [:params :year])]
    (log/info (str "Loading person: " person " (" year")"))
    (web/render
     [:div
      [:h1 (str year " - " (util/dotname person))]

      (let [ts-path (get-in config [:agiladmin :budgets :path])
            timesheet (load-timesheet
                       (str ts-path year "_timesheet_" person ".xlsx"))
            projects (load-all-projects config)
            costs (-> (map-timesheets
                       [timesheet] load-monthly-hours (fn [_] true))
                      (derive-costs config projects))]

        [:div {:class "row-fluid year-total"}
         [:h2 "Yearly totals"]
         (-> {:Total_hours  (-> ($ :hours costs) wrap sum)
              :Voluntary_hours (->> ($where {:tag "VOL"} costs)
                                    ($ :hours) wrap sum)
              :Total_billed (-> ($ :cost costs) wrap sum)}
             to-dataset to-table)

         [:h2 "Monthly totals"]
         ;; cycle all months to 13 (off-by-one)
         (for [m (-> (range 1 13) vec rseq)
               :let [worked ($where {:month (str year '- m)} costs)
                     mtot (-> ($ :hours worked) wrap sum)]
               :when (> mtot 0)]
           [:div {:class "row-fluid month-total"}
            [:h3 (util/month-name m) " total bill is "
             [:strong (-> ($ :cost worked) wrap sum)]
             " for "
             (keep #(when (= (:month %) (str year '- m))
                      (:hours %)) (:sheets timesheet))
             " hours worked across "
             (keep #(when (= (:month %) (str year '- m))
                      (:days %)) (:sheets timesheet))
             " days."]

            [:div {:class "month-detail"}
             (to-table worked)]])

        [:div {:class "col-lg-2"}
         (web/button config "/person" "Previous year"
                     (list
                      (hf/hidden-field "year" (dec (Integer. year)))
                      (hf/hidden-field "person" person)))]])])))
