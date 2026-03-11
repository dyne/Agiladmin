(ns agiladmin.core-test
  (:require [midje.sweet :refer :all]
            [agiladmin.core :as core]
            [agiladmin.ring :as ring]
            [failjure.core :as f]
            [incanter.core :as inc]))

(def projects
  {:CORE {:start_date "01-01-2026"
          :duration 12
          :rates {:Alice 50
                  :Bob [{:before "2026-06" :cph 40}
                        {:before "2026-12" :cph 60}]}}})

(fact "Make sure the ring server starts"
      (f/ok? (ring/init)) => truthy)

(fact "Project rates support fixed and month-ranged values"
      (core/get-project-rate projects "Alice" "CORE" "2026-01") => 50
      (core/get-project-rate projects "Bob" "CORE" "2026-02") => 40
      (core/get-project-rate projects "Bob" "CORE" "2026-10") => 60
      (core/get-project-rate projects "Carol" "CORE" "2026-01") => nil)

(fact "Cost derivation bills hours, but keeps voluntary and unknown-rate rows at zero"
      (let [hours (inc/to-dataset [{:month "2026-01"
                                    :name "Alice"
                                    :project "CORE"
                                    :tag ""
                                    :hours 2}
                                   {:month "2026-01"
                                    :name "Alice"
                                    :project "CORE"
                                    :tag "VOL"
                                    :hours 3}
                                   {:month "2026-01"
                                    :name "Carol"
                                    :project "CORE"
                                    :tag ""
                                    :hours 4}])
            costs (core/derive-costs hours {} projects)]
        costs => {:column-names [:month :name :project :tag :hours :cost]
                  :rows [{:month "2026-01"
                          :name "Alice"
                          :project "CORE"
                          :tag ""
                          :hours 2
                          :cost 100.0}
                         {:month "2026-01"
                          :name "Alice"
                          :project "CORE"
                          :tag "VOL"
                          :hours 3
                          :cost 0}
                         {:month "2026-01"
                          :name "Carol"
                          :project "CORE"
                          :tag ""
                          :hours 4
                          :cost 0}]}))

(fact "Cost-per-hour and year derivation add the expected columns"
      (let [hours (inc/to-dataset [{:month "2026-10"
                                    :name "Bob"
                                    :project "CORE"
                                    :tag ""
                                    :hours 5}])]
        (core/derive-cost-per-hour hours {} projects)
        => {:column-names [:month :name :project :tag :hours :cph]
            :rows [{:month "2026-10"
                    :name "Bob"
                    :project "CORE"
                    :tag ""
                    :hours 5
                    :cph 60}]}
        (core/derive-years hours {} projects)
        => {:column-names [:month :name :project :tag :hours :year]
            :rows [{:month "2026-10"
                    :name "Bob"
                    :project "CORE"
                    :tag ""
                    :hours 5
                    :year "2026"}]}))
