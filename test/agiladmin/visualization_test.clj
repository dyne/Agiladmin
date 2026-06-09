(ns agiladmin.visualization-test
  (:require [agiladmin.tabular :as tab]
            [agiladmin.visualization :as viz]
            [hiccup.core :as hiccup]
            [midje.sweet :refer :all]
            [failjure.core :as f]))

(def sample-hours
  (tab/dataset
   [:month :project :task :tag :hours]
   [{:month "2026-1" :project "CORE" :task "T1" :tag "" :hours 10}
    {:month "2026-2" :project "CORE" :task "T1" :tag "" :hours 5}
    {:month "2026-2" :project "ALPHA" :task "T2" :tag "" :hours 3}
    {:month "2025-12" :project "CORE" :task "T1" :tag "" :hours 2}]))

(fact "Month helpers normalize labels and quarters"
      (viz/parse-month "2026-1") => (contains {:year 2026
                                               :month 1
                                               :month-label "2026-01"
                                               :quarter "Q1"})
      (viz/month->label "2026-9") => "2026-09"
      (viz/month->quarter "2026-10") => "Q4")

(fact "Invalid month strings fail explicitly"
      (f/failed? (viz/parse-month "not-a-month")) => truthy)

(fact "Safe JSON escapes script-breaking content"
      (let [json (viz/safe-json {:text "</script> \"quoted\""})]
        json => (contains "\\u003c/script\\u003e")
        json => (contains "\\\"quoted\\\"")))

(fact "Plotly chart markup exposes the serialized specification in a data attribute"
      (let [html (hiccup/html
                  (viz/plotly-chart
                   "chart-1"
                   "Monthly hours"
                   {:data [] :layout {:title {:text "Monthly hours"}}}
                   {:description "Hours by month"
                    :fallback "No data available."}))]
        html => (contains "data-plotly-chart=\"true\"")
        html => (contains "data-plotly-spec=")
        html => (contains "Monthly hours")
        html => (contains "No data available.")))

(fact "Monthly project data keeps a chronological yearly grid"
      (let [data (viz/person-monthly-project-data sample-hours 2026)
            rows (:rows data)]
        (count rows) => 24
        (tab/sum-col data :hours) => 18.0
        (map :month-label (take 4 rows)) => ["2026-01" "2026-01" "2026-02" "2026-02"]))

(fact "Quarter totals conserve yearly hours"
      (let [data (viz/person-period-data sample-hours 2026)]
        (map :period (:rows data)) => ["Q1" "Q2" "Q3" "Q4"]
        (tab/sum-col data :hours) => 18.0))

(fact "Task budget rows preserve actual and planned values"
      (let [data (viz/task-budget-data (tab/dataset
                                        [:task :description :hours :pm]
                                        [{:task "T1" :description "Task one" :hours 30 :pm 1}
                                         {:task "T2" :description "Task two" :hours 0 :pm 0.5}]))
            rows (:rows data)]
        (map :planned rows) => [150.0 75.0]
        (map :utilization rows) => [0.2 0.0]))

(fact "Project cumulative charts expose actual and planned traces"
      (let [hours (tab/dataset
                   [:month :name :project :task :tag :hours]
                   [{:month "2026-1" :name "Ada" :project "CORE" :task "T1" :tag "" :hours 10}
                    {:month "2026-2" :name "Ada" :project "CORE" :task "T1" :tag "" :hours 5}])
            tasks (tab/dataset
                   [:task :description :start :duration :pm]
                   [{:task "T1" :description "Task one" :start "01-01-2026" :duration 2 :pm 1}])
            spec (viz/project-cumulative-chart-spec hours tasks)]
        (count (:data spec)) => 2
        (map :name (:data spec)) => ["Actual" "Planned"]
        (get-in spec [:layout :title :text]) => "Cumulative hours"))
