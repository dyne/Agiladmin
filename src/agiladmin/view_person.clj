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
   [agiladmin.auth.core :as auth]
   [agiladmin.tabular :as tab]
   [agiladmin.utils :as util]
   [agiladmin.graphics :refer :all]
   [agiladmin.webpage :as web]
   [agiladmin.session :as s]
   [failjure.core :as f]
   [auxiliary.string :refer [strcasecmp]]
   [clojure.data.json :as json :refer [read-str]]
   [agiladmin.config :as conf]
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

(defn list-all
  "list all persons"
  [_request config account]
  (if-not (s/admin? account)
    (web/render-error-page account "Unauthorized access")
    (let [year (:year (util/now))
          people (->> (util/list-files-matching
                       (conf/q config [:agiladmin :budgets :path])
                       #".*_timesheet_.*xlsx$")
                      (keep #(util/timesheet-to-name (.getName %)))
                      clojure.core/sort
                      distinct)
          person-buttons (mapv (fn [person]
                                 [:div {:class "log-person"}
                                  (web/button "/person" person
                                              (list (hf/hidden-field "person" person)
                                                    (hf/hidden-field "year" year)))])
                               people)]
      (web/render
       account
       [:div {:class "grid gap-6 lg:grid-cols-2"}
        [:div {:class "card bg-base-100 shadow-sm"}
         [:div {:class "card-body"}
          [:h2 "Persons"]
          (into [:div {:class "space-y-2"}] person-buttons)]]
        [:div {:class "card bg-base-100 shadow-sm"}
         [:div {:class "card-body"}
          [:h2 "Newcomers"]
          (f/if-let-failed?
            [pending-users (auth/list-pending-users)]
            (web/render-error (str "Unable to load pending users: "
                                   (f/message pending-users)))
            [:ul {:class "space-y-2"}
             (for [{:keys [email name]} pending-users]
               [:li
                [:strong {:class "font-semibold"} (or name email)]
                (when (and name email)
                  [:span {:class "text-sm text-base-content/70"} (str " <" email ">")])])])]]]))))

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
  (web/render
   account
   [:div
    [:h1 (str year " - " (util/dotname person))]
    (f/attempt-all
     [ts-path (conf/q config [:agiladmin :budgets :path])
      ts-file (util/name-year-to-timesheet person year)
      timesheet (load-timesheet (str ts-path ts-file))
      projects (load-all-projects config)
      costs (-> (map-timesheets
                 [timesheet] load-monthly-hours (fn [_] true))
                (derive-costs config projects))]
     [:div {:class "space-y-6"}
      ;; insert the Git Id of the file (Git object in master)
      (person-download-timesheet ts-file) [:br]
      ;; (if-let [githead (util/git-id config ts-file)]
      ;;   [:small
      ;;    ;;[:a {:href (str "https://gogs.dyne.org/dyne/admin-budgets/commit/" githead)}
      ;;    (str "git rev object hash: " githead)])
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
                  (-> costs (derive-cost-per-hour config projects) tab/to-row-seq)))
           [:div {:class "divider"}]
           [:h1 "Monthly totals"]
           (for [m (-> (range 1 13) vec rseq)
                 :let [worked (tab/filter-by costs {:month (str year '- m)})
                       mtot (tab/sum-col worked :hours)
                       mvol (-> (tab/filter-by worked {:tag "VOL"})
                                (tab/sum-col :hours))
                       pay (tab/sum-col worked :cost)
                       breakdown (-> (derive-cost-per-hour worked config projects)
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
       [:div (web/render-error (f/message e))
        (web/button-prev-year year person)
        ]))]))

(defn start
  [request config account]
  (let [params (:params request)
        person (or (clojure.core/get params :person)
                   (clojure.core/get params "person")
                   (:name account))
        year (or (clojure.core/get params :year)
                 (clojure.core/get params "year")
                 (:year (util/now)))]
    (cond ;; admin check
      (s/admin? account) (list-person config account person year)
      ;; check that person views its own account
      (= (:name account) person) (list-person config account person year)

      :else
      (web/render-error-page account "Unauthorized access"))))
