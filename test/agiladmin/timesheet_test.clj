(ns agiladmin.timesheet-test
  (:use midje.sweet)
  (:require [agiladmin.core :refer :all]
            [clojure.pprint :refer :all]))

(def budgets "test/assets/")

(pprint "Testing timesheet reading")

(fact "Load all timesheets from Luca Pacioli"

      (def all-ts-lucpac
        (load-all-timesheets budgets #".*_timesheet_.*.xlsx"))

      (fact "Load timesheet 'uno'"
            (def uno-ts-lucpac
              (load-project-hours "uno" all-ts-lucpac))
            uno-ts-lucpac =>
      {:column-names [:name :month :task :hours], :rows [{:hours 54.0, :month "2016-1", :name "L.Pacioli", :task nil} {:hours 118.0, :month "2016-2", :name "L.Pacioli", :task nil} {:hours 48.0, :month "2016-3", :name "L.Pacioli", :task nil} {:hours 50.0, :month "2016-4", :name "L.Pacioli", :task nil} {:hours 148.5, :month "2016-5", :name "L.Pacioli", :task nil} {:hours 50.0, :month "2016-6", :name "L.Pacioli", :task nil} {:hours 42.0, :month "2016-7", :name "L.Pacioli", :task nil} {:hours 24.0, :month "2016-8", :name "L.Pacioli", :task nil} {:hours 72.0, :month "2016-9", :name "L.Pacioli", :task nil} {:hours 102.0, :month "2016-10", :name "L.Pacioli", :task nil} {:hours 55.0, :month "2016-11", :name "L.Pacioli", :task nil} {:hours 49.0, :month "2016-12", :name "L.Pacioli", :task "alpha"}]}::incanter.core.Dataset)

      (fact "write 'uno' project hours to budget"
            (write-workbook-sheet (str budgets "Budget_uno.xlsx")
                                  "Personnel hours" uno-ts-lucpac)
            => truthy) ;; TODO: timesheet to budget write better check

      (fact "Load timesheet 'due' and test for same project on two columns"
            (def due-ts-lucpac
              (load-project-hours "due" all-ts-lucpac))
            due-ts-lucpac =>
            {:column-names [:name :month :task :hours], :rows [{:hours 15.0, :month "2016-7", :name "L.Pacioli", :task "alpha"} {:hours 30.0, :month "2016-9", :name "L.Pacioli", :task "beta"} {:hours 49.0, :month "2016-9", :name "L.Pacioli", :task "gamma"}]}::incanter.core.Dataset)

      (fact "write 'due' project hours to budget"
            (write-workbook-sheet (str budgets "Budget_due.xlsx")
                                   "Personnel hours" due-ts-lucpac)
            => truthy)

      (fact "Load timesheet 'tre'"
            (def tre-ts-lucpac
              (load-project-hours "tre" all-ts-lucpac))
            tre-ts-lucpac =>
            {:column-names [:name :month :task :hours], :rows [{:hours 48.0, :month "2016-12", :name "L.Pacioli", :task "gamma"}]}::incanter.core.Dataset)
            
      (fact "write 'tre' project hours to budget"
            (write-workbook-sheet (str budgets "Budget_tre.xlsx")
                                  "Personnel hours" tre-ts-lucpac)
            => truthy)
      )

(fact "Load all projects"
      (def all-pjs (load-all-project-rates "test/assets/"))
      all-pjs => {:UNO
                  {:file "test/assets/Budget_uno.xlsx"
                   :rates {"L. Pacioli" 35.0}}
                  :TRE
                  {:file "test/assets/Budget_tre.xlsx"
                   :rates {"L. Pacioli" 30.0}}
                  :DUE
                  {:file "test/assets/Budget_due.xlsx"
                   :rates {"L. Pacioli" 45.0}}}
      ;; (fact "get billable amount for L. Pacioli on month 7"
      ;;       (pprint (get-billable-month
      ;;        all-pjs
      ;;        (load-timesheet "test/assets/2016_timesheet_Luca-Pacioli.xlsx")
      ;;        2016 7)))
      )
