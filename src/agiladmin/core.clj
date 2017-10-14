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
(declare load-timesheet-totals)
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

;; TODO: latest and best rewrite, to consolidate into a single generic
;; function in place of other versions in this file
(defn iter-project-hours
  "to be used in a map iterating on timesheets,
  matches the project string and returns a row with [name month task hours]"
  [timesheet project]
  ;; columns containing hours for each project
  (for [ts (:sheets timesheet)
        :let [xls (:xls timesheet)]]
    (loop [[n & cols] timesheet-cols-projects
           res []]

      (let [sheet  (select-sheet (:month ts) xls)
            proj  (get-cell sheet n "7") ;; row project
            task  (get-cell sheet n "8") ;; row task
            tag   (get-cell sheet n "9") ;; row tag(s) (TODO: support multiple tags)
            ;; take lowest in row totals starting from 42 (as month lenght varies)
            hours  (first (for [i timesheet-rows-hourtots
                                :let  [cell (get-cell sheet n i)]
                                :when (not (nil? cell))] cell))
            entry  (if (and (not= hours "0") (not (blank? proj))
                            (not (strcasecmp tag "vol"))
                            (strcasecmp project proj))
                     {:name (:name timesheet)
                      :month (:month ts)
                      :task task
                      :hours hours} nil)]
        (if (empty? cols) (if (nil? entry) res (conj res entry))
            (recur  cols  (if (nil? entry) res (conj res entry))))))))

(defn load-project-hours
  "load the named project hours from a sequence of timesheets and
  return a bidimensional vector: [\"Name\" \"Date\" \"Task\" \"Hours\"]"
  [pname timesheets]
  (log/info (str "Loading project hours: " pname))
  (->> (for [t timesheets]
         (loop [[m & months] (iter-project-hours t pname)
                res []]
           (let [f (doall m)]
             (if (empty? months) (if (not-empty f) (concat res f) res)
                 (recur  months  (if (not-empty f) (concat res f) res))))))
       (mapcat identity) vec to-dataset))

(defn load-all-project-hours [path project-name]
  "load all hours billed to a project according to current timesheets"
  (->> (load-all-timesheets path #".*_timesheet_.*xlsx$")
       (load-project-hours project-name)))

(defn get-project-rate
  "gets the rate per hour for a person in a project"
  [projects person projname]
  ;; TODO: make sure that project_file has no case sensitive
  ;; complication
  (get-in projects [(keyword projname) :rates
                    (-> person dotname keyword)]))

(defn get-billable-month
  "gets all hours of each projects in a month, multiply by the rate of
  each project and calculate total billable amount for that month"
  [rates timesheet year month]
  (if-let [sheet (select-sheet (str year "-" month) (:xls timesheet))]
    (loop [[c & cols] timesheet-cols-projects
           res []]
      (let [proj  (get-cell sheet c "7") ;; row project
            task  (get-cell sheet c "8") ;; row task
            tag   (get-cell sheet c "9") ;; row tag(s)
            ;; TODO: support multiple tags
            hours (if-let [h (first
                              (for [i timesheet-rows-hourtots
                                    :let  [cell (get-cell sheet c i)]
                                    :when (not (nil? cell))]
                                cell))]
                    (Double. h) 0)

            rate  (if-let
                      [r (get-project-rate
                          rates (:name timesheet) (str proj))] r 0)

            entry (if (and (not (nil? hours))
                           (> hours 0)
                           (> rate 0)
                           (not (blank? proj))
                           (not (strcasecmp tag "vol")))
                    {:name (:name timesheet)
                     :month (str year "-" month)
                     :project (upper-case proj)
                     :task  task
                     :hours hours
                     :rate  rate
                     :billable (* hours rate)} nil)]

        (if (empty? cols) (to-dataset (if (nil? entry) res (conj res entry)))
            (recur cols   (if (nil? entry) res (conj res entry))))))))

(defn load-timesheet-totals [sheet]
  (loop [[c & cols] timesheet-cols-projects
         res {}]
    (let [proj  (get-cell sheet c "7") ;; row project
          task  (get-cell sheet c "8") ;; row task
          tag   (get-cell sheet c "9") ;; row tag(s) (TODO: multiple tags)
          ;; take lowest in row totals starting from 42 (as month
          ;; lenght varies)
          hours  (first (for [i timesheet-rows-hourtots
                              :let  [cell (get-cell sheet c i)]
                              :when (not (nil? cell))] cell))
          entry  (if (and (not (blank? proj))
                          (not (nil? hours))
                          (> hours 0)) {(-> proj upper-case keyword)
                                        {:task  (if (nil? task) "" task)
                                         :tag   (if (nil? tag) "" tag)
                                         :hours (if (nil? hours) 0 hours)}})]
          (if (empty? cols) (if (nil? entry) res (conj res entry))
              (recur cols   (if (nil? entry) res (conj res entry)))))))

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
        :days (get-cell sheet 'B 5)
        :totals (load-timesheet-totals sheet)}
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
    (let [r (-> conf (get-in [:agiladmin :budgets :path])
                (str p ".yaml")
                aux/yaml-read)]
      (if (empty? projects) (conj r res)
          (recur  projects  (conj r res))))))

;; (defn push-total-hours
;;   "push the collected hours in the atom"
;;   [lazy-list-hours]
;;   (swap! total-hours #(into % (vec (map first lazy-list-hours)))))

;; (defn write-total-hours
;;   "writes lazy sequences produced by get-project into rows
;;   in a new sheet 'Personnel costs' created in the budget workbook"
;;   [budget-file]
;;   (let [wb (load-workbook budget-file)
;;         ;; or use add-sheet!
;;         sheet (if-let [s (select-sheet "Personnel hours" wb)]
;;                 s
;;                 (add-sheet! wb "Personnel hours"))]
;;     (remove-all-rows! sheet)
;;     ;; doall?
;;     (add-rows! sheet (into [["Name" "Date" "Task" "Hours"]] @total-hours))
;;     wb))

(defn load-person-hours
  ;; TODO: implement load-person-hours
  "load all timesheets from a person and return a bidimensional vector"
  [path regex])
