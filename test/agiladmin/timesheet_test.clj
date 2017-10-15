(ns agiladmin.timesheet-test
  (:use midje.sweet)
  (:require [auxiliary.config :as aux]
            [agiladmin.core :refer :all]
            [clojure.pprint :refer :all]))

(def budgets "test/assets/")
(def conf (aux/yaml-read "test/assets/agiladmin.yaml"))
(pprint conf)

(pprint "Testing timesheet reading")

(fact "Load all timesheets from Luca Pacioli"

      (def all-ts-lucpac
        (load-all-timesheets budgets #".*_timesheet_.*.xlsx"))

      (fact "Load timesheet 'uno'"
            (def uno-ts-lucpac
              (load-project-monthly-hours all-ts-lucpac "uno"))
            uno-ts-lucpac => {:column-names [:month :name :project :task :tag :hours], :rows [{:hours 54.0, :month "2016-1", :name "L.Pacioli", :project "UNO", :tag "", :task ""} {:hours 118.0, :month "2016-2", :name "L.Pacioli", :project "UNO", :tag "", :task ""} {:hours 48.0, :month "2016-3", :name "L.Pacioli", :project "UNO", :tag "", :task ""} {:hours 50.0, :month "2016-4", :name "L.Pacioli", :project "UNO", :tag "", :task ""} {:hours 148.5, :month "2016-5", :name "L.Pacioli", :project "UNO", :tag "", :task ""} {:hours 50.0, :month "2016-6", :name "L.Pacioli", :project "UNO", :tag "", :task ""} {:hours 42.0, :month "2016-7", :name "L.Pacioli", :project "UNO", :tag "", :task ""} {:hours 24.0, :month "2016-8", :name "L.Pacioli", :project "UNO", :tag "", :task ""} {:hours 72.0, :month "2016-9", :name "L.Pacioli", :project "UNO", :tag "", :task ""} {:hours 102.0, :month "2016-10", :name "L.Pacioli", :project "UNO", :tag "", :task ""} {:hours 55.0, :month "2016-11", :name "L.Pacioli", :project "UNO", :tag "", :task ""} {:hours 49.0, :month "2016-12", :name "L.Pacioli", :project "UNO", :tag "", :task "ALPHA"}]})

      (fact "write 'uno' project hours to budget"
            (write-workbook-sheet (str budgets "Budget_uno.xlsx")
                                  "Personnel hours" uno-ts-lucpac)
            => truthy) ;; TODO: timesheet to budget write better check

      (fact "Load timesheet 'due' and test for same project on two columns"
            (def due-ts-lucpac
              (load-project-monthly-hours all-ts-lucpac "due"))
            due-ts-lucpac => {:column-names [:month :name :project :task :tag :hours], :rows [{:hours 15.0, :month "2016-7", :name "L.Pacioli", :project "DUE", :tag "", :task "ALPHA"} {:hours 30.0, :month "2016-9", :name "L.Pacioli", :project "DUE", :tag "", :task "BETA"} {:hours 49.0, :month "2016-9", :name "L.Pacioli", :project "DUE", :tag "", :task "GAMMA"}]})

      (fact "write 'due' project hours to budget"
            (write-workbook-sheet (str budgets "Budget_due.xlsx")
                                   "Personnel hours" due-ts-lucpac)
            => truthy)

      (fact "Load timesheet 'tre'"
            (def tre-ts-lucpac
              (load-project-monthly-hours all-ts-lucpac "tre"))
            tre-ts-lucpac => {:column-names [:month :name :project :task :tag :hours], :rows [{:hours 48.0, :month "2016-12", :name "L.Pacioli", :project "TRE", :tag "", :task "GAMMA"}]})
            
      (fact "write 'tre' project hours to budget"
            (write-workbook-sheet (str budgets "Budget_tre.xlsx")
                                  "Personnel hours" tre-ts-lucpac)
            => truthy)
      )

(fact "Load all projects"
      (def all-pjs (load-all-projects conf))
      all-pjs => {:DUE {:duration 12, :rates {:L.Pacioli 30}, :start_date "01-01-2016", :tasks [{:duration 36, :id "ALPHA", :pm 1, :start_date "01-01-2016", :text "Management and coordination"} {:duration 14, :id "BETA", :pm 6, :start_date "01-01-2016", :text "Social Dynamics"} {:duration 36, :id "GAMMA", :pm 12, :start_date "01-07-2016", :text "Public design and technological implementation"}]}, :TRE {:duration 12, :rates {:L.Pacioli 35}, :start_date "01-01-2016", :tasks [{:duration 36, :id "ALPHA", :pm 1, :start_date "01-01-2016", :text "Management and coordination"} {:duration 14, :id "BETA", :pm 6, :start_date "01-01-2016", :text "Social Dynamics"} {:duration 36, :id "GAMMA", :pm 12, :start_date "01-07-2016", :text "Public design and technological implementation"}]}, :UNO {:duration 12, :rates {:L.Pacioli 40}, :start_date "01-01-2016", :tasks [{:duration 36, :id "ALPHA", :pm 1, :start_date "01-01-2016", :text "Management and coordination"} {:duration 14, :id "BETA", :pm 6, :start_date "01-01-2016", :text "Social Dynamics"} {:duration 36, :id "GAMMA", :pm 12, :start_date "01-07-2016", :text "Public design and technological implementation"}]}}

      (fact "load all monthly hours"
            (def all-hours (map-timesheets all-ts-lucpac load-monthly-hours 
                                           (fn [_] true))))
      (fact "derive project costs"
            (def costs (derive-costs all-hours conf all-pjs))
            (print costs)
            costs => {:column-names [:month :name :project :task :tag :hours :cost], :rows [{:cost 2160.0, :hours 54.0, :month "2016-1", :name "L.Pacioli", :project "UNO", :tag "", :task ""} {:cost 4720.0, :hours 118.0, :month "2016-2", :name "L.Pacioli", :project "UNO", :tag "", :task ""} {:cost 1920.0, :hours 48.0, :month "2016-3", :name "L.Pacioli", :project "UNO", :tag "", :task ""} {:cost 2000.0, :hours 50.0, :month "2016-4", :name "L.Pacioli", :project "UNO", :tag "", :task ""} {:cost 5940.0, :hours 148.5, :month "2016-5", :name "L.Pacioli", :project "UNO", :tag "", :task ""} {:cost 2000.0, :hours 50.0, :month "2016-6", :name "L.Pacioli", :project "UNO", :tag "", :task ""} {:cost 1680.0, :hours 42.0, :month "2016-7", :name "L.Pacioli", :project "UNO", :tag "", :task ""} {:cost 450.0, :hours 15.0, :month "2016-7", :name "L.Pacioli", :project "DUE", :tag "", :task "ALPHA"} {:cost 960.0, :hours 24.0, :month "2016-8", :name "L.Pacioli", :project "UNO", :tag "", :task ""} {:cost 2880.0, :hours 72.0, :month "2016-9", :name "L.Pacioli", :project "UNO", :tag "", :task ""} {:cost 900.0, :hours 30.0, :month "2016-9", :name "L.Pacioli", :project "DUE", :tag "", :task "BETA"} {:cost 1470.0, :hours 49.0, :month "2016-9", :name "L.Pacioli", :project "DUE", :tag "", :task "GAMMA"} {:cost 4080.0, :hours 102.0, :month "2016-10", :name "L.Pacioli", :project "UNO", :tag "", :task ""} {:cost 2200.0, :hours 55.0, :month "2016-11", :name "L.Pacioli", :project "UNO", :tag "", :task ""} {:cost 480.0, :hours 16.0, :month "2016-11", :name "L.Pacioli", :project "DUE", :tag "VOL", :task ""} {:cost 1960.0, :hours 49.0, :month "2016-12", :name "L.Pacioli", :project "UNO", :tag "", :task "ALPHA"} {:cost 300.0, :hours 10.0, :month "2016-12", :name "L.Pacioli", :project "DUE", :tag "VOL", :task "BETA"} {:cost 1680.0, :hours 48.0, :month "2016-12", :name "L.Pacioli", :project "TRE", :tag "", :task "GAMMA"} {:cost 0, :hours 32.0, :month "2016-12", :name "L.Pacioli", :project "QUATTRO", :tag "VOL", :task "DELTA"}]}))
