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
      [:tr nil (for [tt t] [:td nil tt])])]])

(defn to-excel
  "Takes a dataset and converts it in a format read to be written to
  an excel sheet"
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

(defn chart-to-image
  [chart & {:keys [plot-size aspect-ratio]
            :or   {plot-size 800
                   aspect-ratio 1.618}}]
  (let [width (/ plot-size aspect-ratio)
        ba (java.io.ByteArrayOutputStream.)
        _ (org.jfree.chart.ChartUtilities/writeChartAsPNG
           ba chart plot-size width)]
    (->> (.toByteArray ba)
         b64/encode
         (map char)
         clojure.string/join
         (str "data:image/png;base64,")
         image)))
