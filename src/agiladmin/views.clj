(ns agiladmin.views
  (:require
   [agiladmin.core :refer :all]
   [agiladmin.utils :refer :all]
   [agiladmin.graphics :refer :all]
   [agiladmin.webpage :as web]
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
       (for [f (->> (list-files-matching "budgets" #"budget.*xlsx$")
                    (map #(.getName %)))]
         [:div {:class "row log-project"}
          [:div {:class "col-lg-4"}
           (web/button config "/project" (proj-name-from-path f)
                       (hf/hidden-field "project" f))]])

       [:h2 "People"]
       ;; list all people
       (for [f (->> (list-files-matching
                     "budgets" #".*_timesheet_.*xlsx$")
                    (map #(second
                           (re-find regex-timesheet-to-name
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
        projname      (proj-name-from-path projfile)
        project-hours (load-all-project-hours "budgets/" projname)]

    ;; write the budget file with updated hours
    (write-workbook-sheet (str "budgets/" projfile) "Personnel hours"
                          ($order :month :asc project-hours))

    (web/render
     [:div
      [:h1 projname]
      [:div {:class "row-fluid"}

       ;; --- CHARTS
       ;; time series
       (with-data
         (->> ($rollup :sum :hours :month project-hours)
              ($order :month :asc))
         [:div {:class "col-lg-6"}
          (chart-to-image (time-series-plot
                           (date-to-ts $data :month)
                           ($ :hours)))])

       ;; pie chart
       (with-data ($rollup :sum :hours :name project-hours)
         [:div {:class "col-lg-6"}
          (chart-to-image
           (pie-chart ($ :name)
                      ($ :hours)
                      :legend true
                      :title (str projname " hours used")))])]
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
          (to-table ($rollup :sum :hours [:name :task] project-hours))]

         [:div {:class "tab-pane fade" :id "task-totals"}
          [:h2 "Totals of hours used for each task"]
          (to-table ($rollup :sum :hours :task project-hours))]

         [:div {:class "tab-pane fade" :id "person-totals"}
          [:h2 "Totals of hours used by each person"]
          (to-table ($rollup :sum :hours :name project-hours))]

         [:div {:class "tab-pane fade" :id "monthly-details"}
          [:h2 "Detail of monthly hours used per person on each task"]
          (to-table ($order :month :desc project-hours))]]

       [:div [:h2 "State of budget repository"]
        (present/edn->html
         (-> (load-repo "budgets") git-status))]]]])))

(defn person-view [config request]
  (let [person (get-in request [:params :person])
        year   (get-in request [:params :year])]

    (web/render [:div
                 [:h1 (dotname person)]
                 [:h2 year]

                 (let [ts (load-timesheet
                           (str "budgets/" year
                                "_timesheet_" person ".xlsx"))
                       rates (load-all-project-rates "budgets/")]

                   (for [m (-> (range 1 12) vec rseq)
                         :let [worked (get-billable-month rates ts year m)
                               mtot (->> ($ :hours worked) wrap sum)]
                         :when (> mtot 0)]
                     [:div {:class "row month-total"}
                      [:h3 (month-name m) " total bill is "
                       [:strong (->> ($ :billable worked) wrap sum)]
                       " for "
                       (keep #(when (= (:month %) (str year '- m))
                                (:hours %)) (:sheets ts))
                       " hours worked across "
                       (keep #(when (= (:month %) (str year '- m))
                                (:days %)) (:sheets ts))
                       " days."]

                      [:div {:class "month-detail"}
                       (to-table worked)]]))

                 [:div {:class "col-lg-2"}
                  (web/button config "/person" "Previous year"
                              (list
                               (hf/hidden-field "year" (dec (Integer. year)))
                               (hf/hidden-field "person" person)))]])))
