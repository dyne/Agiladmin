;; Copyright (C) 2017 Dyne.org foundation

;; Sourcecode designed, written and maintained by
;; Denis Roio <jaromil@dyne.org>

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

(ns agiladmin.graphics
;;  (:import  [org.jfree.chart ChartUtilities]) ; experiment on inline images
  (:require [hiccup.element :refer :all]
            [hiccup.form :as hf]
            [agiladmin.tabular :as tab]
            [agiladmin.webpage :as web]
            [agiladmin.utils :refer :all]
;;            [incanter.charts :refer :all]
            [taoensso.timbre :as log]))

;; TODO: inline images for charts if chart rendering is reintroduced.

(defn to-table
  "Takes a dataset and converts it into an hiccup table ready to be
  converted into html"
  [data]
  (let [columns (:column-names data)]
    [:table {:class "sortable table table-zebra"}
     [:thead nil
      [:tr nil (for [t columns]
                 [:th nil t])]]
     [:tbody nil
      (for [row (:rows data)]
        [:tr nil
         (for [column columns
               :let [value (get row column)]]
           [:td nil
            (cond
              (and (= column :progress) (not= value 0.0))
              (if-let [pro value]
                [:span {:class "space-y-2"}
                 [:span {:class "font-semibold"} (format "%.0f%%" (round (* pro 100)))]
                 [:br]
                 [:progress {:class "progress progress-primary w-full" :max 1 :value pro} pro]])
              (= value 0) "-"
              :else
              value)])])]]))


(defn to-monthly-bill-table
  "Takes a dataset and converts it into an hiccup table with details
  added for the monthly billing of a person, ready for inclusion in a
  hiccup web page"
  [projects data]
  (let [columns (:column-names data)
        rows (:rows data)]
    [:table {:class "sortable table table-zebra"}
     [:thead nil
      (into [:tr nil]
            (map (fn [column]
                   [:th nil column])
                 columns))]
     (into
      [:tbody nil]
      (map (fn [row]
             [:tr nil
              [:td nil (web/button
                        "/project" (:project row)
                        (hf/hidden-field "project" (:project row))
                        "btn btn-primary btn-sm")]
              [:td nil (when-not (= (:task row) "")
                         [:span (str (:task row) " - ")
                          [:small (get-in projects [(-> row :project keyword) :idx
                                                    (-> row :task keyword) :text])]])]
              [:td nil (:tag row)]
              [:td nil (:hours row)]
              [:td nil (if (= (:cost row) 0) "-" (:cost row))]
              [:td nil (:cph row)]])
           rows))]))

(defn to-excel
  "Takes a dataset and converts it in a format read to be written to
  an excel sheet"
  [data]
  (into [(map name (:column-names data))] (tab/to-row-seq data)))

(defn date-to-ts
  "Takes a dataset column and a date format (default yyyy-MM) and
  returns a sequence of epoch dates for time-series usages"
  ([data] (date-to-ts data :month "yyyy-MM"))
  ([data column] (date-to-ts data column "yyyy-MM"))
  ([data column fmt]
   (mapv
    #(.getTime (.parse (java.text.SimpleDateFormat. fmt) %))
    (tab/column-values data column))))

(comment
  ;; Former chart-image helper removed along with the data.codec dependency.
  )
