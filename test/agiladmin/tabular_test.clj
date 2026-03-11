(ns agiladmin.tabular-test
  (:require [agiladmin.tabular :as tab]
            [midje.sweet :refer :all]))

(fact "Tabular dataset preserves rows and infers column names"
      (tab/dataset [{:month "2026-01" :hours 10}
                    {:month "2026-02" :hours 20}])
      => {:column-names [:month :hours]
          :rows [{:month "2026-01" :hours 10}
                 {:month "2026-02" :hours 20}]})

(fact "Tabular column projection and exclusion preserve table shape"
      (let [data {:column-names [:month :name :hours]
                  :rows [{:month "2026-01" :name "Alice" :hours 10}]}]
        (tab/select-cols data [:name :hours])
        => {:column-names [:name :hours]
            :rows [{:name "Alice" :hours 10}]}
        (tab/drop-cols data [:name])
        => {:column-names [:month :hours]
            :rows [{:month "2026-01" :hours 10}]}))

(fact "Tabular row sequence and column extraction stay ordered"
      (let [data {:column-names [:month :hours]
                  :rows [{:month "2026-01" :hours 10}
                         {:month "2026-02" :hours 20}]}]
        (tab/to-row-seq data) => [["2026-01" 10]
                                  ["2026-02" 20]]
        (tab/column-values data :hours) => [10 20]))
