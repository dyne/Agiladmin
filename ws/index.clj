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
   [clojure.string :as string]
   [clojure.data.json :as json]
   [clojure.contrib.humanize :refer :all]
   [agiladmin.core :refer :all :reload :true]
   [dk.ative.docjure.spreadsheet :refer :all])
  (:use [gorilla-repl core table latex html]
        ))
;; @@
;; =>
;;; {"type":"html","content":"<span class='clj-nil'>nil</span>","value":"nil"}
;; <=
