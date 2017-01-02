;; gorilla-repl.fileformat = 1

;; **
;;; # Gorilloid REPL
;;; 
;;; Small template to write an application based on Gorilla REPL
;;; 
;; **

;; @@
(ns agiladmin.term
  (:require
   [clojure.repl :refer :all]
   [clojure.string :as string]
   [clojure.data.json :as json]
   [clojure.contrib.humanize :refer :all]
   [agiladmin.core :refer :all :reload :true]
   [dk.ative.docjure.spreadsheet :refer :all])
  (:use [gorilla-repl core table latex html]
        ))

;; directory containing timesheets and budgets
(let [conf (def path "/home/jrml/priv/dyne.org/budgets/")
      
      out  (print (str "AgilAdmin initialised for directory: " path))])
;; @@
;; ->
;;; AgilAdmin initialised for directory: /home/jrml/priv/dyne.org/budgets/
;; <-
;; =>
;;; {"type":"html","content":"<span class='clj-nil'>nil</span>","value":"nil"}
;; <=

;; @@
(->> (load-all-timesheets path #"timesheet.*xls")
     
    (load-project-hours "Dowse")     
    (into [["Name" "Date" "Task" "Hours"]])
     
    (write-project-hours (str path "Budget_dowse.xlsx"))
    (save-workbook!      (str path "Budget_dowse.xlsx")))

;; @@

;; @@

;; @@
