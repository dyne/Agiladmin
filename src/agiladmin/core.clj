(ns agiladmin.core
  (:require [clojure.string :as string]
            [clojure.walk :refer :all]
            [clojure.java.io :as io]
            [gorilla-repl.table :refer :all]
            [clojure.contrib.humanize :refer :all]
            [dk.ative.docjure.spreadsheet :refer :all])
  (:import (org.apache.poi.ss.usermodel Workbook Row CellStyle IndexedColors Font CellValue)
           (org.apache.poi.xssf.usermodel XSSFWorkbook XSSFFont)
           (java.util Date)
           (java.io FileInputStream))
  (:gen-class)
  )

;; (def total-hours (atom []))

(defn load-timesheet [path]
  (let [ts (load-workbook path)
        year (first (string/split (read-cell (select-cell "B2" (first (sheet-seq ts)))) #"-"))]
    {:name (read-cell (select-cell "B3" (first (sheet-seq ts))))
     :file path
     :year year
     :xls ts
     :sheets
     (for [m [1 2 3 4 5 6 7 8 9 10 11 12]
           :let [ms (str year "-" m)
                 sheet (select-sheet ms ts)
                 h (read-cell (select-cell "B4" sheet))]
           :when (not= h 0.0)]
       {:month ms
        :hours h}
       )}))

(defn list-files-matching
  "returns a sequence of files found in a directory whose names match
  a regexp"
  [directory regex]
  (let [dir   (io/file directory)
        files (file-seq dir)]
    (remove nil?
            (map #(let [f (string/lower-case (.getName %))]
                    (if (re-find regex f) %)) files))))

(defn iter-project-hours
  "to be used in a map iterating on timesheets,
  matches the project string and returns a row with [name month task hours]"
  [timesheet project entry]
  (for [n ["B" "C" "D" "E" "F" "G"]

        :let [sheet  (select-sheet (:month entry) (:xls timesheet))
              pcell  (select-cell (str n "7") sheet)
              hours  (read-cell (select-cell (str n "42") sheet))]

        :when (and (not= hours "0") (= (read-cell pcell) project))]

    [(:name timesheet)
     (:month entry)
     (if-let [task (read-cell (select-cell (str n "8") sheet))] task "")
     hours]
    ))

(defn get-project-hours
  "gets all project hours into a lazy-seq of vectors"
  [timesheet project]
  (map #(iter-project-hours timesheet project %) (:sheets timesheet)))

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

(defn load-all-timesheets
  "load all timesheets in a directory matching a certain filename pattern"
  [path regex]
  (let [ts (list-files-matching path #"timesheet.*xls")]
    (for [l (map #(.getName %) ts)]
      (load-timesheet (str path l)))))

(defn load-project-hours
  "load the named project hours from a sequence of timesheets and
  return a bidimensional vector"
  [pname timesheets]
  (vec (mapcat identity
               (for [t timesheets]
                 (for [i (get-project-hours t pname)
                       :let [f (first i)]
                       :when (not-empty f)]
                   (first i))))))

(defn write-project-hours
  "takes a bidimensional vector and writes it to file"
  [budget-file project-hours]
  (let [wb (load-workbook budget-file)
        ;; or use add-sheet!
        sheet (if-let [s (select-sheet "Personnel hours" wb)]
                s
                (add-sheet! wb "Personnel hours"))]
    (remove-all-rows! sheet)
    (print project-hours)
    ;; doall?
    (add-rows! sheet project-hours)
    wb))
