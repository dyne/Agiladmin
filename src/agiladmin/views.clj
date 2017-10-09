(ns agiladmin.views
  (:require
   [agiladmin.utils :refer :all]
   [agiladmin.webpage :as web]
   [hiccup.form :as hf]
   [json-html.core :as present]
   [clj-jgit.porcelain :refer :all]
   [clj-jgit.querying  :refer :all]))

(defn project-log-view [config request]
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
