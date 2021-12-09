;; Agiladmin - spreadsheet based time and budget administration

;; Copyright (C) 2016-2019 Dyne.org foundation

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

(ns agiladmin.core
  (:refer-clojure :exclude [any?])
  (:require [clojure.string :refer [blank? split lower-case upper-case trim]]
            [agiladmin.utils :as util]
            [agiladmin.graphics :refer :all]
            [agiladmin.config :as conf]
            [incanter.core :refer :all]
            [auxiliary.core :as aux]
            [auxiliary.string :refer [strcasecmp]]
            [failjure.core :as f]
            [taoensso.timbre :as log]
            [clj-time.core :as t]
            [clj-time.format :as tf]
            [dk.ative.docjure.spreadsheet
             :refer [load-workbook select-sheet sheet-seq select-cell read-cell]])
  (:import (org.apache.poi.ss.usermodel Workbook Row CellStyle IndexedColors Font CellValue)
           (org.apache.poi.xssf.usermodel XSSFWorkbook XSSFFont)
           (java.util Date)
           (java.io FileInputStream))
  (:gen-class)
  )

(def timesheet-cols-projects ["B" "C" "D" "E" "F" "G" "H"])
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
           '[clojure.string :refer :all]
           '[clojure.java.io :as io]
           '[clojure.pprint :reder :all]
           :reload))

(defn map-col [data col-name f] 
  (let [new-col-names (sort-by #(= % col-name) (col-names data))
        new-dataset (conj-cols
                     (sel data :except-cols col-name)
                     ($map f col-name data))]
    ($ (col-names data) (col-names new-dataset new-col-names) )))

;; redefining this to work with chaining (dataset is first argument)
(defn aggr
  "aggregate fields (coll) grouping by some (coll) using a :rollup-fun or sum"
  [data fields group-by & {:keys [rollup-fun] :or {rollup-fun :sum}}]
  ;; using aggregate from incanter 1.4x
  (aggregate fields group-by :dataset data :rollup-fun rollup-fun))

(defn sort
  "sort a dataset according to a specific :column in :asc or :desc order"
  [data column order]
  ;; using $order from incanteer 1.4x
  ($order column order data))

(defn average
  "makes an average of the values of a certain column in a dataset"
  [col data]
  (let [count (nrow data)
        tot   (-> ($ col data) util/wrap sum)]
    (util/round (/ tot count))))

(defn get-cell
  "return the value of cell in sheet at column and row position"
  [sheet col row]
  (let [cell (read-cell (select-cell (str col row) sheet))];
    ;; check for errors when reading a cell
    (if (aux/any?
         #{:VALUE :DIV0 :CIRCULAR_REF :REF :NUM :NULL :FUNCTION_NOT_IMPLEMENTED :NAME :NA}
         [cell])
      (->> (str "Error \"" cell "\" reading cell " row ":" col " in sheet " sheet)
           (log/spy :error) f/fail)
      cell)))

(defn load-monthly-hours
  "load hours from a timesheet month if conditions match"
  ([timesheet month]
   (load-monthly-hours timesheet month #(true)))
  ([timesheet month cond-fn]
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
             entry  (if (and (not (nil? hours))
                             (> hours 0.0)
                             (not (blank? proj))
                             (not (strcasecmp proj "total"))
                             (cond-fn {:project proj
                                       :task    task
                                       :tag     tag
                                       :hours   hours}))
                      {:month month
                       :name (:name timesheet)
                       :project (upper-case proj)
                       :task (if-not (blank? task) (upper-case task) "") ;; uppercase all tasks
                       :tag  (if-not (blank? tag)  (upper-case tag)  "") ;; uppercase all tags
                       :hours hours} nil)]
         ;; check for errors
         (map #(when (f/failed? %) (log/error (f/message %)))
              [proj task tag])

         (if (empty? cols) (if (nil? entry) res (conj res entry))
             (recur  cols  (if (nil? entry) res (conj res entry)))))))))

(defn map-timesheets
  "Map a function across all loaded timesheets. The function prototype
  is the one of load-hours, taking 3 arguments: a single timesheet,
  name of tab (month) and a conditional function for selection of
  rows."
  ([timesheets]
   (map-timesheets timesheets load-monthly-hours))
  ([timesheets loop-fn]
   (map-timesheets timesheets loop-fn (fn [_] true)))
  ([timesheets loop-fn cond-fn]
   (->> (for [t timesheets]
          (loop [[m & months]
                 (for [ts (:sheets t)
                       :let [xls (:xls t)]]
                   (loop-fn t (:month ts) cond-fn))
                 res []]
            (let [f (doall m)]
              (if (empty? months) (if (not-empty f) (concat res f) res)
                  (recur  months  (if (not-empty f) (concat res f) res))))))
        (mapcat identity) vec to-dataset)))

(defn load-project-monthly-hours
  "load the named project hours from a sequence of timesheets and
  return a bidimensional vector: [\"Name\" \"Date\" \"Task\" \"Hours\"]"
  [timesheets pname]
  (log/info (str "Loading project hours: " pname))
  (map-timesheets timesheets load-monthly-hours
                  (fn [info]
                    (and (not (strcasecmp (:tag info) "VOL"))
                         (strcasecmp (:project info) pname)))))

(def time-format (tf/formatter "dd-MM-yyyy"))
(defn current-proj-month [conf]
  "gets a project-conf and returns the current month in the project
  schedule. end month is attainable from (:duration project-conf)."
  (let [start (-> "dd-MM-yyyy"
                  tf/formatter
                  (tf/parse (-> conf :start_date)))
        now (t/now)]
    (cond
      (t/before? now start) 0
      :else
      (-> start (t/interval (t/now)) t/in-months))))

(defn get-project-rate
  "gets the rate per hour for a person in a project. this is being used
  to create derivated columns."
  [projects name project month]
  (let [proj (get-in projects [(-> project trim keyword)])
        cph  (get-in proj [:rates (keyword name)])
        cm (-> "yyyy-MM" tf/formatter (tf/parse month))
        sdate (-> "dd-MM-yyyy" tf/formatter
                  (tf/parse (or (:start_date proj) "01-01-2000")))]
    (cond
      (coll?   cph) ;; (log/info "TODO: coll in project conf rates")
      (loop [[e & rates] cph
             res {}]
        (let [until (-> "yyyy-MM"
                        tf/formatter
                        (tf/parse (or (:before e)
                                      (t/plus sdate (t/months (+ (:duration proj) 2)))))
                        (t/plus (t/months 1))) ;; before excludes mentioned month
              k (if (and (t/before? cm until)
                         (empty? res))
                  {:cph (:cph e)} { })]
          ;; cannot use failjure inside a loop/recur?
          ;; tried (when (f/failed?)) here but no
          ;; attempt all also cannot allow recur to be last
          (if (empty? rates) (-> (conj k res) :cph)
              (recur  rates  (conj k res)))))
      (number? cph) cph ;; (log/info "TODO: numbrer in project conf rates")
      (string? cph) (log/debug "TODO: string in project conf rates")
      (nil? cph)    (log/debug "TODO: nil in project conf rates")
      :else
      (log/debug "TODO: anything else in project rates"))))

(defn derive-costs
  "gets a dataset of hours and adds a 'cost' column deriving the
  billable costs according to project rates."
  ([hours conf]
   (if-let [projects (load-all-projects conf)]
     (derive-costs hours conf projects)))
  ([hours conf projects]
   (with-data hours
     (add-derived-column :cost
                         [:month :name :project :tag :hours]
                         (fn [month name proj tag hours]
                           (if-let [cost (get-project-rate projects name proj month)]
                             (if (and (> cost 0) (not (strcasecmp tag "VOL")))
                               ;; then
                               (util/round (* cost hours))
                               ;; else
                               0)
                             ;; else
                             0))))))

(defn derive-years
  "gets a dataset of hours per month and adds a 'year' column from its
  YYYY-MM field in :month."
  [hours conf projects]
  (with-data hours
    (add-derived-column :year [:month]
                        (fn [month] (first (split month #"-" 2))))))



(defn derive-cost-per-hour
  "gets a dataset of hours and adds a 'cph' column deriving the cost
  per hour from the project configuration"
  ([hours conf]
   (if-let [projects (load-all-projects conf)]
     (derive-cost-per-hour hours conf projects)))
  ([hours conf projects]
   (with-data hours
     (add-derived-column :cph [:month :name :project]
                         (fn [month name project]
                           (get-project-rate projects name project month))))))


(defn simple-task-derivation
  "internal macro: used inside derive-task-details for the columns that simply
  copy a value from the project/task configuration"
  [conf table-field conf-field data]
  (add-derived-column table-field [:project :task]
                      (fn [proj task]
                        (let [p   (-> proj keyword)
                              t   (-> task keyword)]
                          (get-in conf [p :idx t conf-field]))) data))

(defn derive-empty-tasks
  "gets a dataset of project tasks that are empty and add column
  names used in task details"
  [conf used]
  (let [tasks (-> conf (get-in [(-> conf first first) :tasks]))
        ut ($ :task used)]
        (log/info (seq ut))
        (-> (to-dataset tasks)
          (query-dataset (fn [row] (not (some #{(:id row)} (seq ut))))))
  )
)

(defn derive-task-details
  "gets a dataset of project hours and costs and add columns derived
  from calculations on each task row and its prject configuration:
  tot-hours, pm, description, progress etc."
  [p-hours conf]
  (->> p-hours
       (simple-task-derivation conf :pm :pm)
        (add-derived-column :h-left [:project :task :hours]
                        (fn [proj task hours]
                          (let [p   (-> proj keyword)
                                t   (-> task keyword)]
                            (if-let [tot (get-in conf [p :idx t :pm])]
                               (-> (- (* tot 150) hours))))))
       (simple-task-derivation conf :description :text)
       (simple-task-derivation conf :start :start_date)
       (add-derived-column :end [:project :task]
                           (fn [proj task]
                             (let [p   (-> proj keyword)
                                   t   (-> task keyword)]
                               (let [duration (get-in conf [p :idx t :duration])
                                     start (get-in conf [p :idx t :start_date])]
                                 (if (or (= start nil) (= duration nil)
                                         (= start 0) (= duration 0))
                                   ""
                                        ; else
                                   (tf/unparse time-format
                                               (t/plus (tf/parse time-format start)
                                                       (t/months duration)))
                                   )))))
       (add-derived-column :progress [:project :task :hours]
                           (fn [proj task hours]
                             (let [p   (-> proj keyword)
                                   t   (-> task keyword)]
                               (if-let [tot (get-in conf [p :idx t :pm])]
                                 (-> hours (/ (* tot 150)) util/round)))))))

(defn load-timesheet [path]
  (if-let [ts (try (load-workbook path)
                   (catch Exception ex
                     (log/error
                      ["Error in load-workbook:" (->> ex Throwable->map :cause)])))]
    (let [shs (first (sheet-seq ts))
          year (first (split (get-cell shs 'B 2) #"-"))]
      {:name (util/dotname (get-cell shs 'B 3))
       :file path
       :year year
       :xls ts
       :sheets
       (for [m [1 2 3 4 5 6 7 8 9 10 11 12]
             :let [ms (str year "-" m)
                   sheet (try (select-sheet ms ts)
                              (catch Exception ex
                                (log/error
                                 (str "Error: load-timesheet: select-sheet can't find tab: " ms))))
                   h (get-cell sheet 'B 4)]
             :when (not= h 0.0)]
         {:month ms
          :hours h
          :days (get-cell sheet 'B 5)}
         )})
    (f/fail (str "Error loading timesheet: " path
                 "<br/>Exception in dk.ative.docjure.spreadsheet/load-workbook"
                 "<br/>Catched in core/load-timesheet"))))


  ;; (defn write-workbook-sheet
  ;;   "takes a dataset and writes it to file"
  ;;   [file sheet-name data]
  ;;   (let [wb (load-workbook file)
  ;;         ;; or use add-sheet!
  ;;         sheet (if-let [s (select-sheet sheet-name wb)]
  ;;                 s
  ;;                 (add-sheet! wb "Personnel hours"))]
  ;;     (remove-all-rows! sheet)
  ;;     (add-rows! sheet (to-excel ($order :month :asc data)))
  ;;     (save-workbook! file wb)
  ;;     wb))

  (defn load-all-timesheets
    "load all timesheets in a directory matching a certain filename pattern"
    [path regex]
    (let [ts (util/list-files-matching path regex)]
      (for [l (map #(.getName %) ts)]
        (if (not= (first l) '\.)
          (load-timesheet (str path l))))))

  (defn load-all-projects [conf]
    "load all project budgets specified in a directory"
    (loop [[p & projects] (get-in conf [:agiladmin :projects])
           res {}]
      (let [r (conf/load-project conf p)]
        ;; cannot use failjure inside a loop/recur?
        ;; tried (when (f/failed?)) here but no
        ;; attempt all also cannot allow recur to be last
        (if (empty? projects) (conj r res)
            (recur  projects  (conj r res))))))
