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
       (present/edn->html
        (->> (git-log repo)
             (map #(commit-info repo %))
             (map #(select-keys % [:author :message :time :changed_files]))))
       ]])))

(defn project-view [config request]
  (let [projfile      (get-in request [:params :project])
        projname      (proj-name-from-path projfile)
        project-hours (load-all-project-hours "budgets/" projname)]

    (write-workbook-sheet (str "budgets/" projfile) "Personnel hours"
                          ($order :month :asc project-hours))

    (web/render
     [:div
      [:h1 projname]
      [:div {:class "row-fluid"}

                        ;;;;; --- CHARTS

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


      [:div {:class "row-fluid dropdown"}
       (to-table ($rollup :sum :hours [:name :task] project-hours))]

      [:div {:class "row-fluid"}
       [:div {:class "project-hours-usage"}
        [:h2 "Project hours usage"]
        (to-table ($order :month :desc project-hours))]

       [:div [:h2 "State of budget repository"]
        (present/edn->html
         (-> (load-repo "budgets") git-status))]]])))
