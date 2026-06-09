(ns agiladmin.visualization-notebook
  (:require
   [agiladmin.tabular :as tab]
   [agiladmin.visualization :as viz]))

(def sample-hours
  "Small fixture dataset for Clay and REPL visualization checks."
  (tab/dataset
   [:month :name :project :task :tag :hours]
   [{:month "2026-1" :name "Ada" :project "CORE" :task "T1" :tag "" :hours 10}
    {:month "2026-2" :name "Ada" :project "CORE" :task "T1" :tag "" :hours 5}
    {:month "2026-2" :name "Ada" :project "ALPHA" :task "T2" :tag "" :hours 3}]))

(def monthly-project-chart
  "Production chart specification rendered from the notebook fixture."
  (viz/person-monthly-project-chart-spec sample-hours 2026))

(def period-chart
  "Production quarter chart specification rendered from the notebook fixture."
  (viz/person-period-chart-spec sample-hours 2026))
