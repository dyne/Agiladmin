# Scratchpad

(use 'agiladmin.core :reload)
(repl)


;; billable before dataset

(def rates (load-all-project-rates "budgets/"))
(def prate (get-project-rate rates "Denis Roio" 'dowse))




;; Get the billable months of a single person
(def year "2016")
(def person "Denis-Roio")
(def project "dowse")
(def ts (load-timesheet
         (str "budgets/" year
              "_timesheet_" person ".xlsx")))

(def hours (get-project-hours project ts))

(def worked (let [rates (load-all-project-rates "budgets/")]
              (for [m (-> (range 1 12) vec rseq)
                    :let [worked (get-billable-month rates ts year m)]]
                worked)))
