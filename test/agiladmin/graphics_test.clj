(ns agiladmin.graphics-test
  (:require [agiladmin.graphics :as graphics]
            [hiccup.core :as hiccup]
            [midje.sweet :refer :all]))

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
