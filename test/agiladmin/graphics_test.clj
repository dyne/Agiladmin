(ns agiladmin.graphics-test
  (:require [agiladmin.graphics :as graphics]
            [agiladmin.view-person :as view-person]
            [hiccup.core :as hiccup]
            [midje.sweet :refer :all]))

(fact "Generic tables render row maps as cell values, not hiccup tags"
      (let [data {:column-names [:month :hours]
                  :rows [{:month "2016-12"
                          :hours 49.0}]}
            html (hiccup/html (graphics/to-table data))]
        html => (contains "2016-12")
        html => (contains "49.0")))

(fact "Monthly bill tables render a complete row for each work entry"
      (let [projects {:PACESET {:idx {:T35 {:text "Delivery"}}}}
            data {:column-names [:project :task :tag :hours :cost :cph]
                  :rows [{:project "PACESET"
                          :task "T35"
                          :tag ""
                          :hours 45.0
                          :cost 2700.0
                          :cph 60}]}
            html (hiccup/html (graphics/to-monthly-bill-table projects data))]
        html => (contains "PACESET")
        html => (contains "Delivery")
        html => (contains "2700.0")
        html => (contains "45.0")))

(fact "Personnel pages render an existing timesheet without a 500"
      (let [config {:agiladmin {:budgets {:path "test/assets/"}}}
            response (view-person/list-person
                      config
                      {:email "admin@example.org"
                       :name "Admin"
                       :admin true}
                      "Luca-Pacioli"
                      "2016")]
        (:body response) => (contains "Yearly totals")
        (:body response) => (contains "Monthly totals")
        (:body response) => (contains "Luca-Pacioli")))
