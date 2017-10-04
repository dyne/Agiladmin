;; Agiladmin - spreadsheet based time and budget administration

;; Copyright (C) 2016-2017 Dyne.org foundation

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

(ns agiladmin.utils
  (:require [clojure.string :refer [split replace blank? lower-case upper-case]]
            [clojure.java.io :as io]
            [auxiliary.maps :refer [compress]]
            [auxiliary.string :refer [strcasecmp]]
            [clojure.walk :refer :all]))


(defn dotname
  "Shorten up a name and surname tuple into initial and surname format"
  [inname]
  (let [toks (-> (replace inname #"-" " ") (split #"\s") compress)
        dot  (first (first toks))]
    (str dot ". " (second toks))))

(defn namecmp
  "dotted comparison of two name strings, assuming only two names"
  [str1 str2]
  (strcasecmp (dotname str1) (dotname str2)))

(defn list-files-matching
  "returns a sequence of files found in a directory whose names match
  a regexp"
  [directory regex]
  (let [dir   (io/file directory)
        files (file-seq dir)]
    (remove nil?
            (map #(let [f (lower-case (.getName %))]
                    (if (re-find regex f) %)) files))))

(def regex-timesheet-to-name      (re-pattern "^\\d+_\\w+_(.*).xlsx$"))
(def regex-budget-to-project-name (re-pattern "^.udget_(.*).xlsx$"))

(defn proj-name-from-path
  "get a project name from path"
  [path]
  (->> (split path #"/") last
       (re-find regex-budget-to-project-name) second upper-case))

(defn timesheet-to-name
  "get a timesheet filename and extract a dotname"
  [path]
  (-> (re-find regex-timesheet-to-name path)
      second dotname))

(def month-names
  "A vector of abbreviations for the twelve months, in order."
  ["January"
   "February"
   "March"
   "April"
   "May"
   "June"
   "July"
   "August"
   "September"
   "October"
   "November"
   "December"])

(defn month-name
  "Returns the abbreviation for a month in the range [1..12]."
  [month]
  (get month-names (dec month)))

(defn month-to-time-series
  "Takes a column of months and returns a sequence of epoch dates for time-series"
  [mseq]  
  (map
   #(.getTime (.parse (java.text.SimpleDateFormat. "yyyy-MM") %))
   mseq))
