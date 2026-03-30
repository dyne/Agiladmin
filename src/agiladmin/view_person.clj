;; Agiladmin - spreadsheet based time and budget administration

;; Copyright (C) 2016-2018 Dyne.org foundation

;; Sourcecode written and maintained by Denis Roio <jaromil@dyne.org>

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

(ns agiladmin.view-person
  (:require
   [agiladmin.core :refer :all]
   [agiladmin.tabular :as tab]
   [agiladmin.utils :as util]
   [agiladmin.graphics :refer :all]
   [agiladmin.webpage :as web]
   [agiladmin.session :as s]
   [failjure.core :as f]
   [auxiliary.string :refer [strcasecmp]]
   [clojure.data.json :as json :refer [read-str]]
   [agiladmin.config :as conf]
   [agiladmin.view-timesheet :as view-timesheet]
   [taoensso.timbre :as log :refer [debug]]
   [hiccup.form :as hf]))


(defn person-download-timesheet
  [path]
  [:a {:href (str "/timesheets/download/" path)}
   [:button {:type "button"
             :class "btn btn-primary"}
    "Download current timesheet"]])

(defn person-download-toolbar
  [person year costs]
  [:form {:action "/persons/spreadsheet"
          :method "post"
          :class "space-y-3"}
   [:h3 "Download yearly totals:"]
   ;; (hf/hidden-field "format" "excel")
   (hf/hidden-field "person" person)
   (hf/hidden-field "year" year)
   (hf/hidden-field "costs" (-> costs json/write-str))
   ;; [:input {:type "submit" :name "format1" :value "excel"
   ;;          :class "btn btn-default"}]
   [:div {:class "flex flex-wrap gap-2"}
    [:input {:type "submit" :name "format2" :value "json"
             :class "btn btn-ghost"}]
    [:input {:type "submit" :name "format3" :value "csv"
             :class "btn btn-ghost"}]
    [:input {:type "submit" :name "format4" :value "html"
             :class "btn btn-ghost"}]]])

(defn person-hours-summary
  [hours]
  (let [voluntary-hours (-> (tab/filter-by hours {:tag "VOL"})
                            (tab/sum-col :hours)
                            util/round)]
    (-> [{:Total_hours (-> (tab/sum-col hours :hours) util/round)
          :Voluntary_hours voluntary-hours}]
        tab/dataset
        to-table)))

(defn- load-person-page-data
  "Load the shared timesheet and project data needed by personnel pages."
  [config person year]
  (f/attempt-all
   [ts-path (conf/q config [:agiladmin :budgets :path])
    ts-file (util/name-year-to-timesheet person year)
    timesheet (load-timesheet (str ts-path ts-file))
    projects (load-all-projects config)
    hours (map-timesheets [timesheet] load-monthly-hours (fn [_] true))]
   {:ts-file ts-file
    :timesheet timesheet
    :projects projects
    :hours hours}))

(defn list-person-manager
  [config account person year]
  (web/render
   account
   [:div
    [:h1 (str year " - " (util/dotname person))]
    (view-timesheet/upload-card)
    (f/attempt-all
     [person-data (load-person-page-data config person year)]
     (let [{:keys [ts-file timesheet projects hours]} person-data
           monthly-sections
           (for [m (-> (range 1 13) vec rseq)
                 :let [worked (tab/filter-by hours {:month (str year '- m)})
                       mtot (tab/sum-col worked :hours)
                       mvol (-> (tab/filter-by worked {:tag "VOL"})
                                (tab/sum-col :hours))
                       breakdown (tab/select-cols worked [:project :task :tag :hours])]
                 :when (> mtot 0)]
             [:div {:class "card bg-base-100 shadow-sm"}
              [:div {:class "card-body gap-3"}
               [:strong (util/month-name m)] " total hours for "
               (util/dotname person) " are "
               [:strong mtot]
               " across "
               (keep #(when (= (:month %) (str year '- m))
                        (:days %))
                     (:sheets timesheet))
               " days, plus " mvol
               " voluntary hours."
               [:div {:class "month-detail overflow-x-auto"}
                (to-monthly-hours-table projects breakdown)]]])]
       [:div {:class "space-y-6"}
        (person-download-timesheet ts-file)
        [:br]
        [:div {:class "space-y-6"}
         [:h1 "Yearly totals"]
         (person-hours-summary hours)
         [:div {:class "divider"}]
         [:h1 "Monthly totals"]
         monthly-sections]
        (web/button-prev-year year person)])
     (f/when-failed [e]
       [:div
        (web/render-error (f/message e))
        (web/button-prev-year year person)]))]))

(defn list-all
  "list all persons"
  [_request config account]
  (if-not (s/admin? account)
    (web/render-error-page account "Unauthorized access")
    (let [year (:year (util/now))
          recent-cutoff (dec year)
          timesheet-files (util/list-direct-files-matching
                           (conf/q config [:agiladmin :budgets :path])
                           #".*_timesheet_.*xlsx$")
          people-by-latest-year
          (reduce (fn [people file]
                    (let [filename (.getName file)
                          [_ y person] (re-find #"^(\d+)_timesheet_(.*)\.xlsx$" filename)
                          timesheet-year (some-> y Integer/parseInt)]
                      (if (and person timesheet-year)
                        (update people person #(max (or % 0) timesheet-year))
                        people)))
                  {}
                  timesheet-files)
          recent-people (->> people-by-latest-year
                             (filter (fn [[_ latest-year]]
                                       (>= latest-year recent-cutoff)))
                             (map first)
                             clojure.core/sort)
          old-people (->> people-by-latest-year
                          (remove (fn [[_ latest-year]]
                                    (>= latest-year recent-cutoff)))
                          (map first)
                          clojure.core/sort)
          person-buttons
          (fn [people]
            (mapv (fn [person]
                    [:div {:class "log-person"
                           :data-text-filter-item "true"
                           :data-text-filter-value person}
                     (web/button "/person" person
                                 (list (hf/hidden-field "person" person)
                                       (hf/hidden-field "year" year))
                                 "btn btn-outline w-full justify-start")])
                  people))
          old-members-section
          (when (seq old-people)
            [:section {:class "card bg-base-100 shadow-sm"}
             [:div {:class "card-body gap-4"}
              [:h2 "Old members"]
              (into [:div {:class "grid gap-2 sm:grid-cols-2 xl:grid-cols-3"}]
                    (person-buttons old-people))]])
          page-body
          (cond-> [:div {:class "space-y-4"}
                   (view-timesheet/upload-card)
                   (web/filterable-button-list "persons-list"
                                               "Persons"
                                               "No persons match the current filter."
                                               (person-buttons recent-people))]
            old-members-section (conj old-members-section))]
      (web/render
       account
       page-body))))

(defn download
  [request config account]
  (f/if-let-ok?
      [costs-json (s/param request :costs)]
    (cond
      (= "json" (s/param request :format2))
      {:headers {"Content-Type"
                 "text/json; charset=utf-8"}
       :body costs-json} ;; its already a json
      (= "csv"  (s/param request :format3))
      (-> costs-json json/read-str web/download-csv)
      (= "html" (s/param request :format4))
      (->> costs-json json/read-str tab/from-row-seq to-table
           (web/render account)))))

(defn list-person [config account person year]
  (if (s/can-view-costs? account)
    (web/render
     account
     [:div
      [:h1 (str year " - " (util/dotname person))]
      (view-timesheet/upload-card)
      (f/attempt-all
       [person-data (load-person-page-data config person year)]
       (let [{:keys [ts-file timesheet projects hours]} person-data]
         (f/attempt-all
          [costs (derive-costs hours config projects)
           costs-with-cph (derive-cost-per-hour costs config projects)]
          [:div {:class "space-y-6"}
           (person-download-timesheet ts-file)
           [:br]
           (if (zero? (tab/sum-col costs :cost))
             (web/render-error
              (log/spy :error [:p "No costs found (blank timesheet)"]))
             (let [voluntary-costs (tab/filter-by costs {:tag "VOL"})
                   billed-costs (tab/filter-rows costs
                                                 (fn [row]
                                                   (not (strcasecmp (:tag row) "VOL"))))
                   monthly-costs (tab/dataset
                                  (->> (:rows costs)
                                       (group-by :month)
                                       vals
                                       (mapv (fn [rows]
                                               {:cost (reduce + 0 (map :cost rows))}))))
                   monthly-average (-> (tab/average-col monthly-costs :cost)
                                       util/round)]
               [:div {:class "space-y-6"}
                [:h1 "Yearly totals"]
                (-> {:Total_hours (-> (tab/sum-col costs :hours) util/round)
                     :Voluntary_hours (-> (tab/sum-col voluntary-costs :hours) util/round)
                     :Total_billed (-> (tab/sum-col billed-costs :cost) util/round)
                     :Monthly_average monthly-average}
                    vector tab/dataset to-table)
                (person-download-toolbar
                 person year
                 (into [["Date" "Name" "Project" "Task" "Tags" "Hours" "Cost" "CPH"]]
                       (tab/to-row-seq costs-with-cph)))
                [:div {:class "divider"}]
                [:h1 "Monthly totals"]
                (for [m (-> (range 1 13) vec rseq)
                      :let [worked (tab/filter-by costs {:month (str year '- m)})
                            mtot (tab/sum-col worked :hours)
                            mvol (-> (tab/filter-by worked {:tag "VOL"})
                                     (tab/sum-col :hours))
                            pay (tab/sum-col worked :cost)
                            breakdown (-> (tab/filter-by costs-with-cph {:month (str year '- m)})
                                          (tab/select-cols [:project :task :tag :hours :cost :cph]))]
                      :when (> mtot 0)]
                  [:div {:class "card bg-base-100 shadow-sm"}
                   [:div {:class "card-body gap-3"}
                    [:strong (util/month-name m)] " total bill for "
                    (util/dotname person) " is "
                    [:strong pay]
                    " for " (- mtot mvol)
                    " hours worked across "
                    (keep #(when (= (:month %) (str year '- m))
                             (:days %))
                          (:sheets timesheet))
                    " days, plus " mvol
                    " voluntary hours."
                    " (with 21% VAT added is " (+ pay (* pay 0.21)) ")"
                    [:div {:class "month-detail overflow-x-auto"}
                     (to-monthly-bill-table projects breakdown)]]])]))
           (web/button-prev-year year person)]
          (f/when-failed [e]
            [:div
             (web/render-error (f/message e))
             (web/button-prev-year year person)])))
       (f/when-failed [e]
         [:div
          (web/render-error (f/message e))
          (web/button-prev-year year person)]))
      ])
    (list-person-manager config account person year)))

(defn start
  [request config account]
  (let [params (:params request)
        person (s/effective-person account request)
        year (or (clojure.core/get params :year)
                 (clojure.core/get params "year")
                 (:year (util/now)))]
    (cond ;; admin check
      (s/admin? account) (list-person config account person year)
      ;; check that person views its own account
      (= (:name account) person) (list-person config account person year)

      :else
      (web/render-error-page account "Unauthorized access"))))
