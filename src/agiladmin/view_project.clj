;; Agiladmin - spreadsheet based time and budget administration

;; Copyright (C) 2016-2018 Dyne.org foundation

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

(ns agiladmin.view-project
  (:require
   [clojure.java.io :as io]
   [agiladmin.core :refer :all]
   [agiladmin.utils :as util]
   [agiladmin.graphics :refer :all]
   [agiladmin.webpage :as web]
   [agiladmin.config :as conf]
   [agiladmin.session :as s]
   [failjure.core :as f]
   [taoensso.timbre :as log :refer [debug]]
   [cheshire.core :as chesh :refer [generate-string]]
   [hiccup.form :as hf]
   [incanter.core :refer :all]))
;;   [incanter.charts :refer :all]))

(defn list-all
  "list all projects"
  [request config account]
  (web/render
   account
   [:div {:class "projects"}
    [:h2 "Projects"]
    (for [pj (conf/q config [:agiladmin :projects])]
     [:div {:class "row log-project"}
      (web/button "/project" pj
                  (hf/hidden-field "project" pj))])]))

(defn edit [request config account]
  (f/attempt-all
   [projname      (s/param request :project)
    project-conf  (conf/load-project config projname)
    conf          (get project-conf (keyword projname))]
   (f/if-let-ok? [config (s/param request :editor)]
     ;; TODO: then edit the configuration
     (web/render
      account
      [:div
       [:h1 (str projname ": apply project configuration")]
       ;; TODO: validate
       (web/highlight-yaml config)])
      ;; else present an editor
     (web/render
      account
      [:form {:action "/projects/edit"
              :method "post"}
       [:h1 (str "Project " projname ": edit configuration")]
       (web/edit-edn project-conf)
       [:input {:type "hidden" :name "project" :value projname}]]))
   (f/when-failed [e]
     (web/render-error-page (f/message e)))))

(defn start [request config account]
  (f/attempt-all
   [projname     (s/param request :project)
    project-conf (conf/load-project config projname)
    conf         (get project-conf (keyword projname))
    ts-path      (conf/q config [:agiladmin :budgets :path])
    timesheets   (load-all-timesheets ts-path #".*_timesheet_.*xlsx$")
    project-hours (-> (load-project-monthly-hours timesheets projname)
                      (derive-costs config project-conf))
    task-details (derive-task-details
                  (aggregate :hours [:project :task] :dataset project-hours)
                  project-conf)]
   (web/render
    account
    [:div {:style "container-fluid"}
     (if-not (empty? (:tasks conf))
       [:div {:style "container-fluid"}
        [:h1 projname
         [:button {:class "pull-right btn btn-info"
                   :onclick "toggleMode(this)"} "Scale to Fit"]]

        ;; GANTT chart
        [:div {:class "row-fluid"
               :style "width:100%; min-height:20em; position: relative;" :id "gantt"}]
        (let [today (util/now)
              gantt-tasks (map (fn [task]
                                 (conj task {:progress
                                             (with-data task-details
                                               ($ :progress ($where {:task (:id task)})))}))
                               (:tasks conf))]
          [:script {:type "text/javascript"}
           (str "\nvar today = new Date("
                (:year today)", "
                (dec (:month today))", "
                (:day today)");\n")
           (str (slurp (io/resource "gantt-loader.js")) "
var tasks = { data:" (chesh/generate-string gantt-tasks) "};
gantt.init('gantt');
gantt.parse(tasks);
")])]
       ;; else
       [:h1 projname])
     (web/button "/projects/edit" "Edit project configuration"
                  (hf/hidden-field "project" projname)
                  "btn-primary btn-lg edit-project")
     [:div {:class "row-fluid"}

      [:h2 "Overview of tasks"]
      (-> task-details
          (sel :cols [:task :pm :start :duration :end :progress :description]) to-table)]
     ;; [:div {:class "row-fluid"}
      ;;  ;; --- CHARTS
      ;;  ;; time series
      ;;  (with-data
      ;;    (->> ($rollup :sum :hours :month project-hours)
      ;;         ($order :month :asc))
      ;;    [:div {:class "col-lg-6"}
      ;;     (chart-to-image
      ;;      (bar-chart :month :hours :group-by :month :legend false))])
      ;;  ;; (time-series-plot (date-to-ts $data :month)
      ;;  ;;                   ($ :hours)))])
     ;; pie chart
      ;; (with-data ($rollup :sum :hours :name project-hours)
      ;;   [:div {:class "col-lg-6"}
      ;;    (chart-to-image
      ;;     (pie-chart (-> ($ :name) wrap)
      ;;                ($ :hours)
      ;;                :legend true
      ;;                :title (str projname " hours used")))])]
     [:div {:class "container-fluid"}
      [:h1 "Totals"]
      (let [billed (-> ($ :cost project-hours) util/wrap sum util/round)
            hours (-> ($ :hours project-hours) util/wrap sum util/round)
            tasks (:tasks conf)]
        (-> [{:total "Current"
              :cost billed
              :hours hours
              :cph (if (or (zero? billed) (zero? hours)) 0
                       ;; else
                       (util/round (/ billed hours)))}]
            (concat
             (if (empty? tasks) []
                 (let [max_hours (-> (map #(* (get % :pm) 150) tasks)
                                     util/wrap sum)
                       max_cost (* (:cph conf) max_hours)]
                   [{:total "Progress"
                     :cost (util/percentage billed max_cost)
                     :hours (util/percentage hours max_hours)
                     :CPH ""}
                    {:total "Total"
                     :cost max_cost
                     :hours max_hours
                     :CPH (:cph conf)}])))
            to-dataset to-table))]
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
         (-> (aggregate [:hours :cost] [:name :task] :dataset project-hours)
             (sel :cols [:name :task :pm :cost]) to-table)]
        [:div {:class "tab-pane fade" :id "task-totals"}
         [:h2 "Totals per task"]
         (-> task-details
             (sel :cols [:task :hours :tot-hours :pm :progress :description]) to-table)]
        [:div {:class "tab-pane fade" :id "person-totals"}
         [:h2 "Totals per person"]
         (-> (aggregate [:hours :cost] :name :dataset project-hours)
             (sel :cols [:name :hours :cost]) to-table)]
        [:div {:class "tab-pane fade" :id "monthly-details"}
         [:h2 "Detail of monthly hours used per person on each task"]
         (-> ($order :month :desc project-hours)
             to-table)]]
       ]]])
   (f/when-failed [e]
     (web/render account (web/render-error (f/message e))))))
