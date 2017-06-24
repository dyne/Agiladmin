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
  (:import  [org.jfree.chart ChartUtilities]) ; experiment on inline images
  (:require [clojure.data.codec.base64 :as b64]
            [hiccup.element :refer :all]
            [incanter.charts :refer :all]
            [incanter.core :refer :all]))

;; TODO: inline images for charts, see
;; https://github.com/incanter/incanter/blob/master/examples/blog/projects/simple_web_app/src/simple_web_app.clj

(defn to-table
  "Takes a dataset and converts it into an hiccup table ready to be
  converted into html"
  [data]
  [:table {:class "sortable table"}
   [:thead nil
    [:tr nil (for [t (:column-names data)]
               [:th nil t])]]
   [:tbody nil
    (for [t (:rows data)]
      [:tr nil (for [tt t] [:td nil tt])])]]
)

(defn to-excel
  "Takes a dataset and converts it in a format read to be written to an excel sheet"
  [data]
  (into [(map name (:column-names data))] (to-list data)))

(defn date-to-ts
  "Takes a dataset column and a date format (default yyyy-MM) and
  returns a sequence of epoch dates for time-series usages"
  ([data] (date-to-ts data :month "yyyy-MM"))
  ([data column] (date-to-ts data column "yyyy-MM"))
  ([data column fmt]
   ($map
    #(.getTime (.parse (java.text.SimpleDateFormat. fmt) %))
    column data)))


(defn project-hours-time-series
  "shows a time-sries-plot of all project hours per month"
  [project-hours]
  (let [data (dataset [:name :date :task :hours] project-hours)
        grouped ($group-by :name data)
        graph (time-series-plot
                ($map date-to-ts :date ($order :date :asc data))
               (repeat (count ($ :hours data)) 0))]
    (clojure.pprint/pprint "---- DATA")
    (clojure.pprint/pprint data)
    (clojure.pprint/pprint "---- GROUPED")
    (clojure.pprint/pprint grouped)
    (for [g grouped]
      (with-data (second g)
        (and (clojure.pprint/pprint "OK")
             (add-lines graph ($map date-to-ts :date)
             ($ :hours)))))
    (view graph)))

  ;; (for [p $data
  ;;       :let [ph ($where {:name (:name p)})]]
  ;;   ; (pprint (:name ph))
  ;;   (add-lines graph
  ;;              (month-to-time-series ($ :date ph))
  ;;              ($ :hours ph)))
  ;; (view graph)))

                                        ;                                          ($order :date :asc))

                                        ;(to-matrix $data)
                                        ;                           (view (bar-chart :date :hours :group-by :date :legend true))

  ;; (view (bar-chart :type :uptake
  ;;                  :title (str projname)
  ;;                  :group-by :date
  ;;                  :x-label "month"
  ;;                  :y-label "hours"
  ;;                  :legend true
  ;;                  ($ [:date :hours] ($order :date :hours ($rollup :sum :hours [:date :task] data)))))


  ;; trying to render in memory to png base64

(defn to-image
  [chart & {:keys [plot-size aspect-ratio]
            :or   {plot-size 800
                   aspect-ratio 1.618}}]
  (let [width (/ plot-size aspect-ratio)
        ba (java.io.ByteArrayOutputStream.)
        _ (org.jfree.chart.ChartUtilities/writeChartAsPNG ba chart plot-size width)]
    (->> (.toByteArray ba)
         b64/encode
         (map char)
         clojure.string/join
         (str "data:image/png;base64,")
         image)))
