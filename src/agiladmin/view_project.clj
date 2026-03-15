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
   [clojure.string :refer [trim]]
   [clj-time.format :as tf]
   [clj-time.core :as t]
   [agiladmin.core :refer :all]
   [agiladmin.tabular :as tab]
   [agiladmin.utils :as util]
   [agiladmin.graphics :refer :all]
   [agiladmin.webpage :as web]
   [agiladmin.config :as conf]
   [agiladmin.session :as s]
   [failjure.core :as f]
   [taoensso.timbre :as log :refer [debug]]
   [cheshire.core :as chesh :refer [generate-string]]
   [hiccup.form :as hf]))

(defn project-hours
  [config projname]
  (let [ts-path (conf/q config [:agiladmin :budgets :path])
        timesheets (load-all-timesheets ts-path #".*_timesheet_.*xlsx$")]
    (load-project-monthly-hours timesheets projname)))

(defn project-costs
  [config project-conf projname]
  (-> (project-hours config projname)
      (derive-costs config project-conf)))

(defn list-all
  "list all projects"
  [request config account]
  (let [project-buttons
        (mapv (fn [pj]
                [:div {:class "log-project"
                       :data-text-filter-item "true"
                       :data-text-filter-value pj}
                 (web/button "/project" pj
                             (hf/hidden-field "project" pj)
                             "btn btn-outline w-full justify-start")])
              (conf/project-names config))]
  (web/render
   account
   [:div {:class "space-y-4"}
    (web/filterable-button-list "projects-list"
                                "Projects"
                                "No projects match the current filter."
                                project-buttons)])))

(defn edit [request config account]
  (f/attempt-all
   [projname      (s/param request :project)
    project-conf  (conf/load-project config projname)
    conf          (get project-conf (keyword projname))]
   (f/if-let-ok? [config (s/param request :editor)]
     ;; TODO: then edit the configuration
     (web/render
      account
      [:div {:class "space-y-4"}
       [:h1 (str projname ": apply project configuration")]
       ;; TODO: validate
       (web/highlight-yaml config)])
      ;; else present an editor
     (web/render
      account
      [:form {:action "/projects/edit"
              :method "post"
              :class "space-y-4"}
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
    project-hours (if (s/can-view-costs? account)
                    (project-costs config project-conf projname)
                    (project-hours config projname))
    task-details (-> project-hours
                     (aggr :hours [:project :task])
                     (derive-task-details project-conf))
    empty-tasks (-> project-conf (derive-empty-tasks task-details))]
   (web/render
    account
    [:div {:class "space-y-6"}
     (if-not (empty? (:tasks conf))
       [:div {:class "space-y-4"}
        [:div {:class "flex flex-wrap items-center gap-3"}
         [:h1 {:class "text-4xl font-semibold"} projname]
         [:button {:class "btn btn-info ml-auto"
                   :onclick "toggleMode(this)"} "Scale to Fit"]]
        [:div {:class "rounded-box border border-base-300 bg-base-100 p-2 shadow-sm"}
         [:div {:class "w-full min-h-80" :style "position: relative;" :id "gantt"}]]
        (let [today (util/now)
              gantt-tasks (map (fn [task]
                                 (conj task {:progress
                                             (some-> (tab/filter-by task-details {:task (:id task)})
                                                     :rows
                                                     first
                                                     :progress)}))
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
       [:h1 {:class "text-4xl font-semibold"} projname])
     (when (s/admin? account)
       (web/button "/projects/edit" "Edit project configuration"
                   (hf/hidden-field "project" projname)
                   "btn btn-primary btn-lg edit-project"))
     [:div {:class "grid gap-6 xl:grid-cols-[minmax(0,1fr)_minmax(0,1fr)]"}
      [:div {:class "space-y-6"}
       [:h1 (str "Totals - M "
                 (let [lastm (:duration conf)
                       currm (current-proj-month conf)]
                   (str currm " / " lastm)))]
       (let [hours (-> (tab/sum-col project-hours :hours) util/round)
             tasks (:tasks conf)]
         (if (s/can-view-costs? account)
           (let [billed (-> (tab/sum-col project-hours :cost) util/round)]
             (-> [{:total "Current"
                   :cost billed
                   :pm (-> hours (/ 150) util/round)
                   :cph (if (or (zero? billed) (zero? hours))
                          0
                          (util/round (/ billed hours)))}]
                 (concat
                  (if (empty? tasks)
                    []
                    (let [max_hours (reduce + 0 (map #(* (get % :pm) 150) tasks))
                          max_cost (* (:cph conf) max_hours)]
                      [{:total "Progress"
                        :cost (util/percentage billed max_cost)
                        :pm (util/percentage hours max_hours)
                        :CPH "⇧ ⇩"}
                       {:total "Total"
                        :cost max_cost
                        :pm (/ max_hours 150)
                        :CPH (:cph conf)}])))
                 tab/dataset to-table))
           (-> [{:total "Current"
                 :hours hours
                 :pm (-> hours (/ 150) util/round)}]
               (concat
                (if (empty? tasks)
                  []
                  (let [max_hours (reduce + 0 (map #(* (get % :pm) 150) tasks))]
                    [{:total "Progress"
                      :hours (util/percentage hours max_hours)
                      :pm (util/percentage hours max_hours)}
                     {:total "Total"
                      :hours max_hours
                      :pm (/ max_hours 150)}])))
               tab/dataset to-table)))]
       [:h2 "Overview of tasks"]
       [:div {:class "overflow-x-auto"}
        (let [overview-cols [:task :pm :h-left :start :end :progress :description]
              used-tasks (tab/select-cols task-details overview-cols)
              unused-tasks
              (tab/dataset overview-cols
                           (map (fn [row]
                                  {:task (:id row)
                                   :pm (:pm row)
                                   :h-left nil
                                   :start (:start_date row)
                                   :end (:end_date row)
                                   :progress nil
                                   :description (:text row)})
                                (:rows empty-tasks)))]
          (-> (tab/append-rows overview-cols used-tasks unused-tasks)
              (tab/order-by-col :task :asc)
              to-table))]]
      [:div {:class "space-y-4"}
       [:h1 "Details " [:small "(switch views using tabs below)"]]
       (web/tabs
        (str "project-details-" projname)
        [{:id "task-sum-hours"
          :title "Task/Person totals"
          :content [:div {:class "space-y-3"}
                    [:h2 "Totals grouped per person and per task"]
                    [:div {:class "overflow-x-auto"}
                     (if (s/can-view-costs? account)
                       (-> (map-col project-hours :tag #(if (= "VOL" %) "VOL" ""))
                           (aggr [:hours :cost] [:name :tag :task])
                           (tab/select-cols [:name :tag :task :hours :cost]) to-table)
                       (-> (map-col project-hours :tag #(if (= "VOL" %) "VOL" ""))
                           (aggr [:hours] [:name :tag :task])
                           (tab/select-cols [:name :tag :task :hours]) to-table))]]}
         {:id "task-totals"
          :title "Task totals"
          :content [:div {:class "space-y-3"}
                    [:h2 "Totals per task"]
                    [:div {:class "overflow-x-auto"}
                     (-> task-details
                         (tab/select-cols [:task :hours :tot-hours :pm :progress :description]) to-table)]]}
         {:id "person-totals"
          :title "Person totals"
          :content [:div {:class "space-y-3"}
                    [:h2 "Totals per person"]
                    [:div {:class "overflow-x-auto"}
                     (if (s/can-view-costs? account)
                       (-> (map-col project-hours :tag #(if (= "VOL" %) "VOL" ""))
                           (aggr [:hours :cost] [:name :tag])
                           (tab/select-cols [:name :tag :hours :cost]) to-table)
                       (-> (map-col project-hours :tag #(if (= "VOL" %) "VOL" ""))
                           (aggr [:hours] [:name :tag])
                           (tab/select-cols [:name :tag :hours]) to-table))]]}
         {:id "monthly-details"
          :title "Monthly details"
          :content [:div {:class "space-y-3"}
                    [:h2 "Detail of monthly hours used per person on each task"]
                    [:div {:class "overflow-x-auto"}
                     (if (s/can-view-costs? account)
                       (-> project-hours
                           (sort :month :desc)
                           to-table)
                       (-> project-hours
                           (tab/select-cols [:month :name :task :hours])
                           (sort :month :desc)
                           to-table))]]}])]])
   (f/when-failed [e]
     (web/render account (web/render-error (f/message e))))))

(defn infra [config account projname]
  (f/attempt-all
   [project-conf (conf/load-project config projname)
   conf         (get project-conf (keyword projname))
   project-hours (-> (if (s/can-view-costs? account)
                       (project-costs config project-conf projname)
                       (project-hours config projname))
                     (derive-years config project-conf))]
  (web/render account [:div
                       [:h1 (str projname " fixed costs overview")]
                       [:h2 "Yearly totals"]
                       (if (s/can-view-costs? account)
                         (-> (aggr project-hours [:hours :cost] [:year :tag])
                             (tab/select-cols [:year :tag :hours :cost])
                             (sort :year :desc) to-table)
                         (-> (aggr project-hours [:hours] [:year :tag])
                             (tab/select-cols [:year :tag :hours])
                             (sort :year :desc) to-table))
                       [:h2 "Personnel totals"]
                       (if (s/can-view-costs? account)
                         (-> (aggr project-hours [:hours :cost] [:name :tag])
                             (tab/select-cols [:name :tag :hours :cost])
                             (sort :year :desc) to-table)
                         (-> (aggr project-hours [:hours] [:name :tag])
                             (tab/select-cols [:name :tag :hours])
                             (sort :year :desc) to-table))
                       ])))

(defn rolling [config account projname]
  (f/attempt-all
   [project-conf (conf/load-project config projname)
   conf         (get project-conf (keyword projname))
   project-hours (-> (if (s/can-view-costs? account)
                       (project-costs config project-conf projname)
                       (project-hours config projname))
                     (derive-years config project-conf))]
  (web/render account [:div
                       [:h1 (str projname " fixed costs overview")]
                       [:h2 "Yearly totals"]
                       (if (s/can-view-costs? account)
                         (-> (aggr project-hours [:hours :cost] [:year :tag])
                             (tab/select-cols [:year :tag :hours :cost])
                             (sort :year :desc) to-table)
                         (-> (aggr project-hours [:hours] [:year :tag])
                             (tab/select-cols [:year :tag :hours])
                             (sort :year :desc) to-table))
                       [:h2 "Personnel totals"]
                       (if (s/can-view-costs? account)
                         (-> (aggr project-hours [:hours :cost] [:name :tag])
                             (tab/select-cols [:name :tag :hours :cost])
                             (sort :year :desc) to-table)
                         (-> (aggr project-hours [:hours] [:name :tag])
                             (tab/select-cols [:name :tag :hours])
                             (sort :year :desc) to-table))
                       ])))

(defn start [request config account]
  (f/attempt-all
   [projname     (-> request (s/param :project) trim)
    project-conf (conf/load-project config projname)
    project      (get project-conf (keyword projname))]
   (cond
     (= (:type project) "infra") (infra config account projname)
     (= (:type project) "rolling") (rolling config account projname)
     :else
     (h2020 request config account))))
