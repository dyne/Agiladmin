;; Agiladmin - spreadsheet based time and budget administration

;; Copyright (C) 2016-2019 Dyne.org foundation

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
   [incanter.core :refer [with-data $ $where sel sum to-dataset]]))

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

(defn h2020 [request config account]
  (f/attempt-all
   [projname     (s/param request :project)
    project-conf (conf/load-project config projname)
    conf         (get project-conf (keyword projname))
    ts-path      (conf/q config [:agiladmin :budgets :path])
    timesheets   (load-all-timesheets ts-path #".*_timesheet_.*xlsx$")
    project-hours (-> (load-project-monthly-hours timesheets projname)
                      (derive-costs config project-conf))
    task-details (-> project-hours
                     (aggr :hours [:project :task])
                     (derive-task-details project-conf))]
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

     [:div {:class "container-fluid"}
      [:h1 "Totals"]
      (let [billed (-> ($ :cost project-hours) util/wrap sum util/round)
            hours (-> ($ :hours project-hours) util/wrap sum util/round)
            tasks (:tasks conf)]
        (-> [{:total "Current"
              :cost billed
              :pm (-> hours (/ 150) util/round)
              :cph (if (or (zero? billed) (zero? hours)) 0
                       ;; else
                       (util/round (/ billed hours)))}]
            (concat
             (if (empty? tasks) []
                 (let [max_hours (-> (map #(* (get % :pm) 150) tasks) util/wrap sum)
                       max_cost (* (:cph conf) max_hours)]
                   [{:total "Progress"
                     :cost (util/percentage billed max_cost)
                     :pm (util/percentage hours max_hours)
                     :CPH "⇧ ⇩"}
                    {:total "Total"
                     :cost max_cost
                     :pm (/ max_hours 150)
                     :CPH (:cph conf)}])))
            to-dataset to-table))]
      [:h2 "Overview of tasks"]
      (-> task-details
          (sel :cols [:task :pm :start :duration :end :progress :description]) to-table)]

     [:div {:class "row-fluid"}
      [:h1 "Details "[:small "(switch views using tabs below)"]]
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
         (-> (map-col project-hours :tag #(if (= "VOL" %) "VOL" ""))
             (aggr [:hours :cost] [:name :tag :task])
             (sel :cols [:name :tag :task :hours :cost]) to-table)]
        [:div {:class "tab-pane fade" :id "task-totals"}
         [:h2 "Totals per task"]
         (-> task-details
             (sel :cols [:task :hours :tot-hours :pm :progress :description]) to-table)]
        [:div {:class "tab-pane fade" :id "person-totals"}
         [:h2 "Totals per person"]
         (-> (map-col project-hours :tag #(if (= "VOL" %) "VOL" "")) ;; list only voluntary tags
             (aggr [:hours] [:name :tag])
             (sel :cols [:name :tag :hours]) to-table)]
        [:div {:class "tab-pane fade" :id "monthly-details"}
         [:h2 "Detail of monthly hours used per person on each task"]
         (-> project-hours (sort :month :desc) to-table)]]
       ]]])
   (f/when-failed [e]
     (web/render account (web/render-error (f/message e))))))

(defn infra [config account projname]
  (f/attempt-all
   [project-conf (conf/load-project config projname)
   conf         (get project-conf (keyword projname))
   ts-path      (conf/q config [:agiladmin :budgets :path])
   timesheets   (load-all-timesheets ts-path #".*_timesheet_.*xlsx$")
   project-hours (-> (load-project-monthly-hours timesheets projname)
                     (derive-costs config project-conf)
                     (derive-years config project-conf))]
  (web/render account [:div
                       [:h1 (str projname " fixed costs overview")]
                       [:h2 "Yearly totals"]
                       (-> (aggr project-hours [:hours :cost] [:year :tag])
                           (sel :cols [:year :tag :hours :cost])
                           (sort :year :desc) to-table)
                       [:h2 "Personnel totals"]
                       (-> (aggr project-hours [:hours :cost] [:name :tag])
                           (sel :cols [:name :tag :hours :cost])
                           (sort :year :desc) to-table)
                       ])))

(defn start [request config account]
  (f/attempt-all
   [projname     (s/param request :project)
    project-conf (conf/load-project config projname)
    project      (get project-conf (keyword projname))]
   (cond
     (= (:type project) "infra") (infra config account projname)
     :else
     (h2020 request config account))))
