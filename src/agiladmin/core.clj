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

(ns agiladmin.core
  (:require [clojure.string :refer [blank? split lower-case upper-case]]
            [agiladmin.utils :refer :all]
            [agiladmin.graphics :refer :all]
            [agiladmin.config :as conf]
            [incanter.core :refer :all]
            [clojure.contrib.humanize :refer :all]
            [auxiliary.core :refer :all]
            [auxiliary.string :refer [strcasecmp]]
            [auxiliary.config :as aux]
            [failjure.core :as f]
            [taoensso.timbre :as log]
            [dk.ative.docjure.spreadsheet :refer :all])
  (:import (org.apache.poi.ss.usermodel Workbook Row CellStyle IndexedColors Font CellValue)
           (org.apache.poi.xssf.usermodel XSSFWorkbook XSSFFont)
           (java.util Date)
           (java.io FileInputStream))
  (:gen-class)
  )

(def timesheet-cols-projects ["B" "C" "D" "E" "F" "G"])
(def timesheet-rows-hourtots [43 42 41 40 39 38])

(declare load-all-timesheets)
(declare load-all-projects)
(declare load-timesheet)


(defn repl
  "load all deps for repl"
  []
  (require '[agiladmin.core :refer :all]
           '[agiladmin.graphics :refer :all]
           '[agiladmin.utils :refer :all]
           '[agiladmin.config :refer :all]
           '[incanter.core :refer :all]
           '[incanter.charts :refer :all]
           '[json-html.core :as present]
           '[clojure.string :refer :all]
           '[clojure.java.io :as io]
           '[clojure.pprint :reder :all]
           :reload))

(defn wrap
  "wrap a single element into a collection, safety measure for dataset
  operations on a columns that return a single element"
  [ele] (if (coll? ele) ele (list ele)))

(defn round
  "rounds a float to the first 2 positions after the comma"
  [^double f]
  ;; TODO: error checking on nil and zero using failjure + tests
    (let [factor (Math/pow 10 2)]
      (/ (Math/floor (* f factor)) factor)))

(defn percentage
  "calculates a percentage and rounds"
  ;; TODO: error checking on nil and zero using failjure + tests
  [^double part ^double total]
  (str (round (/ (* part 100) total)) "%"))

(defn average
  "makes an average of the values of a certain column in a dataset"
  [col data]
  (let [count (nrow data)
        tot   (-> ($ col data) wrap sum)]
    (round (/ tot count))))

(defn get-cell
  "return the value of cell in sheet at column and row position"
  [sheet col row]
  (let [cell (read-cell (select-cell (str col row) sheet))];
    ;; check for errors when reading a cell
    (if (any?
         #{:VALUE :DIV0 :CIRCULAR_REF :REF :NUM :NULL :FUNCTION_NOT_IMPLEMENTED :NAME :NA}
         [cell])
      (->> (str "Error \"" cell "\" reading cell " row ":" col " in sheet " sheet)
           (log/spy :error) f/fail)
      cell)))

(defn load-monthly-hours
  "load hours from a timesheet month if conditions match"
  [timesheet month cond-fn]
  (if-let [sheet (select-sheet month (:xls timesheet))]
    (loop [[n & cols] timesheet-cols-projects
           res []]
      (let [proj  (f/ok-> (get-cell sheet n "7") str) ;; row project
            task  (f/ok-> (get-cell sheet n "8") str) ;; row task
            tag   (f/ok-> (get-cell sheet n "9") str) ;; row tag(s) (TODO: support multiple tags)
            ;; take lowest in row totals starting from 42 (as month lenght varies)
            hours  (first (for [i timesheet-rows-hourtots
                                :let  [cell (get-cell sheet n i)]
                                :when  (not (nil? cell))]
                            cell))
            entry  (if (and (> hours 0.0) (not (blank? proj))
                            (cond-fn {:project proj
                                      :task    task
                                      :tag     tag
                                      :hours   hours}))
                     ;; (not (strcasecmp tag "vol"))
                     ;; (strcasecmp project proj))
                     {:month month
                      :name (:name timesheet)
                      :project (upper-case proj)
                      :task (if-not (blank? task) (upper-case task) "")
                      :tag  (if-not (blank? tag)  (upper-case tag)  "")
                      :hours hours} nil)]
        ;; check for errors
        (map #(when (f/failed? %) (log/error (f/message %)))
             [proj task tag])

        (if (empty? cols) (if (nil? entry) res (conj res entry))
            (recur  cols  (if (nil? entry) res (conj res entry))))))))

(defn map-timesheets
  "Map a function across all loaded timesheets. The function prototype
  is the one of load-hours, taking 3 arguments: a single timesheet,
  name of tab (month) and a conditional function for selection of
  rows."
  [timesheets loop-fn cond-fn]
  (->> (for [t timesheets]
         (loop [[m & months]
                (for [ts (:sheets t)
                      :let [xls (:xls t)]]
                  (loop-fn t (:month ts) cond-fn))
                res []]
           (let [f (doall m)]
             (if (empty? months) (if (not-empty f) (concat res f) res)
                 (recur  months  (if (not-empty f) (concat res f) res))))))
       (mapcat identity) vec to-dataset))

(defn load-project-monthly-hours
  "load the named project hours from a sequence of timesheets and
  return a bidimensional vector: [\"Name\" \"Date\" \"Task\" \"Hours\"]"
  [timesheets pname]
  (log/info (str "Loading project hours: " pname))
  (map-timesheets timesheets load-monthly-hours
                  (fn [info]
                    (and (not (strcasecmp (:tag info) "VOL"))
                         (strcasecmp (:project info) pname)))))

(defn get-project-rate
  "gets the rate per hour for a person in a project"
  [projects person projname]
  ;; TODO: make sure that project_file has no case sensitive
  ;; complication
  (get-in projects [(keyword projname) :rates
                    (-> person dotname keyword)]))

(defn derive-costs
  "gets a dataset of hours and adds a 'cost' column deriving the
  billable costs according to project rates."
  ([hours conf]
   (if-let [projects (load-all-projects conf)]
     (derive-costs hours conf projects)))
  ([hours conf projects]
   (with-data hours
     (add-derived-column :cost [:name :project :tag :task :hours]
                         (fn [name proj tag task hours]
                           (if-let [cost (get-in projects [(keyword proj) :rates
                                                           (keyword name)])]
                             (if (and (> cost 0) (not (strcasecmp tag "VOL")))
                               ;; then
                               (round (* cost hours))
                               ;; else
                               0)
                             ;; else
                             0))))))

(defn derive-task-hours-completed
  "gets a dataset of project hours and costs and add a column deriving
  the hours and costs progress on each task according to its
  configured pm and the pm used"
  [p-hours conf]
  (with-data p-hours
    (add-derived-column
     :completed [:project :task :hours]
     (fn [proj task hours]
       (let [p   (-> proj keyword)
             t   (-> task keyword)]
         (if-let [tot (get-in conf [p :idx t :pm])]
           (-> hours (/ (* tot 150)) round)))))))

(defn derive-task-hours-totals
  "gets a dataset of project hours and costs and add a column deriving
  the total hours configured in the project for each task according to
  its configured pm and the pm used"
  [p-hours conf]
  (with-data p-hours
    (add-derived-column
     :tot-hours [:project :task]
     (fn [proj task]
       (let [p   (-> proj keyword)
             t   (-> task keyword)]
         (if-let [tot (get-in conf [p :idx t :pm])]
           (* tot 150)))))))


(defn derive-task-descriptions
  "gets a dataset of project hours and costs and add a column deriving
  the descriptions of each task  configured in the project"
  [p-hours conf]
  (with-data p-hours
    (add-derived-column
     :description [:project :task]
     (fn [proj task]
       (let [p   (-> proj keyword)
             t   (-> task keyword)]
         (get-in conf [p :idx t :text]))))))

(defn load-timesheet [path]
  (let [ts (load-workbook path)
        shs (first (sheet-seq ts))
        year (first (split (get-cell shs 'B 2) #"-"))]
    {:name (dotname (get-cell shs 'B 3))
     :file path
     :year year
     :xls ts
     :sheets
     (for [m [1 2 3 4 5 6 7 8 9 10 11 12]
           :let [ms (str year "-" m)
                 sheet (select-sheet ms ts)
                 h (get-cell sheet 'B 4)]
           :when (not= h 0.0)]
       {:month ms
        :hours h
        :days (get-cell sheet 'B 5)}
       )}))

(defn write-workbook-sheet
  "takes a dataset and writes it to file"
  [file sheet-name data]
  (let [wb (load-workbook file)
        ;; or use add-sheet!
        sheet (if-let [s (select-sheet sheet-name wb)]
                s
                (add-sheet! wb "Personnel hours"))]
    (remove-all-rows! sheet)
    (add-rows! sheet (to-excel ($order :month :asc data)))
    (save-workbook! file wb)
    wb))

(defn load-all-timesheets
  "load all timesheets in a directory matching a certain filename pattern"
  [path regex]
  (let [ts (list-files-matching path regex)]
    (for [l (map #(.getName %) ts)]
      (if (not= (first l) '\.)
        (load-timesheet (str path l))))))

(defn load-all-projects [conf]
  "load all project budgets specified in a directory"
  (loop [[p & projects] (get-in conf [:agiladmin :projects])
         res {}]
    (let [r (conf/load-project conf p)]
      (if (empty? projects) (conj r res)
          (recur  projects  (conj r res))))))
