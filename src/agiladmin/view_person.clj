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
   [agiladmin.utils :as util]
   [agiladmin.graphics :refer :all]
   [agiladmin.webpage :as web]
   [agiladmin.session :as s]
   [agiladmin.ring :as ring]
   [clj-storage.core :as store]
   [failjure.core :as f]
   [auxiliary.string :refer [strcasecmp]]
   [clojure.data.json :as json :refer [read-str]]
   [agiladmin.config :as conf]
   [taoensso.timbre :as log :refer [debug]]
   [incanter.core :refer :all]
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
          :method "post"}
   [:h3 "Download yearly totals:"]
   ;; (hf/hidden-field "format" "excel")
   (hf/hidden-field "person" person)
   (hf/hidden-field "year" year)
   (hf/hidden-field "costs" (-> costs json/write-str))
   ;; [:input {:type "submit" :name "format1" :value "excel"
   ;;          :class "btn btn-default"}]
   [:input {:type "submit" :name "format2" :value "json"
            :class "btn btn-default"}]
   [:input {:type "submit" :name "format3" :value "csv"
            :class "btn btn-default"}]
   [:input {:type "submit" :name "format4" :value "html"
            :class "btn btn-default"}]])

(defn list-all
  "list all persons"
  [request config account]
  (web/render
   account
   [:div {:class "row-fluid"}
    [:div {:class "persons col-lg-4"}
     [:h2 "Persons"]
     ;; list all persons
     (let [year (:year (util/now))]
       (for [f (->> (util/list-files-matching
                     (conf/q config [:agiladmin :budgets :path])
                     #".*_timesheet_.*xlsx$")
                    (map #(second
                           (re-find util/regex-timesheet-to-name
                                    (.getName %)))) sort distinct)]
         ;; (map #(.getName %)) distinct)]
         [:div {:class "row log-person"}
          (web/button "/person" f
                      (list (hf/hidden-field "person" f)
                            (hf/hidden-field "year" year)))]))]
    [:div {:class "newcomers col-lg-4"}
     [:h2 "Newcomers"]
     [:ul
      (map #(conj [:a {:href (:activation-link %)}]
                  (conj [:li] (:activation-link %)))
           (store/query (:account-store @ring/accts)
                        {:activated false}))]]]))

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
      (->> costs-json json/read-str to-dataset to-table
           (web/render account)))))

(defn start [request config account]
  (f/attempt-all
   [person (s/param request :person)
    year   (s/param request :year)]
   (web/render
     account
     [:div
      [:h1 (str year " - " (util/dotname person))]
      (f/attempt-all
       [ts-path (conf/q config [:agiladmin :budgets :path])
        ts-file (str year "_timesheet_" person ".xlsx")
        timesheet (load-timesheet (str ts-path ts-file))
        projects (load-all-projects config)
        costs (-> (map-timesheets
                   [timesheet] load-monthly-hours (fn [_] true))
                  (derive-costs config projects))]
       [:div {:class "container-fluid"}
        ;; insert the Git Id of the file (Git object in master)
        [:p (str "<!-- ID: " (util/git-id config ts-file) "-->")
         (person-download-timesheet ts-file)]
        (if (zero? (->> ($ :cost costs) util/wrap sum))
          (web/render-error
           (log/spy :error [:p "No costs found (blank timesheet)"]))
          ;; else
          [:div [:h1 "Yearly totals"]
           (-> {:Total_hours  (-> ($ :hours costs) util/wrap sum util/round)
                :Voluntary_hours (->> ($where {:tag "VOL"} costs)
                                      ($ :hours) util/wrap sum util/round)
                :Total_billed (->> ($where ($fn [tag] (not (strcasecmp tag "VOL")))
                                           costs) ($ :cost) util/wrap sum util/round)
                :Monthly_average  (->> ($rollup :sum :cost :month costs)
                                       (average :cost) util/round)}
               to-dataset to-table)
           (person-download-toolbar
            person year
            (into [["Date" "Name" "Project" "Task" "Tags" "Hours" "Cost" "CPH"]]
                  (-> costs (derive-cost-per-hour config projects) to-list)))
           [:hr]
           ;; (->> (derive-cost-per-hour costs config projects)
           ;;      ($ [:project :task :tag :hours :cost :cph])
           ;;      to-list))
           [:h1 "Monthly totals"]
           ;; cycle all months to 13 (off-by-one)
           (for [m (-> (range 1 13) vec rseq)
                 :let [worked ($where {:month (str year '- m)} costs)
                       mtot (-> ($ :hours worked) util/wrap sum)]
                 :when (> mtot 0)]
             [:span
              [:strong (util/month-name m)] " total bill for "
              (util/dotname person) " is "
              [:strong (-> ($ :cost worked) util/wrap sum)]
              " for " mtot
              " hours worked across "
              (keep #(when (= (:month %) (str year '- m))
                       (:days %)) (:sheets timesheet))
              " days, including "
              (->> ($where {:tag "VOL"} worked)
                   ($ :hours) util/wrap sum) " voluntary hours."
            [:div {:class "month-detail"}
             (->> (derive-cost-per-hour worked config projects)
                  ($ [:project :task :tag :hours :cost :cph])
                  (to-monthly-bill-table projects))]])])
        (web/button-prev-year year person)]
       (f/when-failed [e]
         [:div (web/render-error (f/message e))
          (web/button-prev-year year person)
          ]))])
   (f/when-failed [e]
     (web/render account (web/render-error (f/message e))))))
