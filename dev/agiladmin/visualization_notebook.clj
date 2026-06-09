(ns agiladmin.visualization-notebook
  (:require
   [agiladmin.tabular :as tab]
   [agiladmin.visualization :as viz]
   [scicloj.clay.v2.api :as clay]
   [scicloj.kindly.v4.kind :as kind]))

(kind/md
  "# Agiladmin visualization review

This fixture-only report reviews the production chart specifications. Agiladmin
currently retains monthly assignment totals, so these charts must not be read as
daily or weekly activity."
  )

(def sample-hours
  "Representative fixture data including voluntary work and inactive months."
  (tab/dataset
   [:month :name :project :task :tag :hours]
   [{:month "2026-1" :name "Ada" :project "CORE" :task "T1" :tag "" :hours 10}
    {:month "2026-2" :name "Ada" :project "CORE" :task "T1" :tag "" :hours 5}
    {:month "2026-2" :name "Ada" :project "ALPHA" :task "T2" :tag "" :hours 3}
    {:month "2026-4" :name "Ada" :project "ALPHA" :task "T2" :tag "VOL" :hours 4}]))

(def sample-tasks
  "Representative planned task data, including an overrun."
  (tab/dataset
   [:task :description :start :duration :pm :hours]
   [{:task "T1" :description "Coordination" :start "01-01-2026" :duration 3 :pm 0.1 :hours 20}
    {:task "T2" :description "Delivery" :start "01-02-2026" :duration 3 :pm 0.2 :hours 7}]))

(kind/md
  "## Source and normalization

The legacy tabular dataset remains authoritative. The visualization boundary
converts its row maps to Tablecloth without changing spreadsheet ingestion."
  )

sample-hours
(viz/to-tablecloth sample-hours)

(kind/md
  "## Personnel questions

- Monthly project mix: how did activity vary through the year?
- Heatmap: which projects dominated each period?
- Quarter totals: where was work concentrated?

The heatmap keeps the eight most active projects and combines the rest as
`Other`. It is omitted unless two projects and two months are active."
  )

(viz/person-monthly-project-chart-spec sample-hours 2026)
(viz/person-activity-heatmap-spec sample-hours 2026)
(viz/person-period-chart-spec sample-hours 2026)

(kind/md
  "## Project manager questions

- Monthly composition: which tasks drove effort?
- Cumulative comparison: is actual effort ahead of the configured linear plan?
- Task budget: which tasks are near or beyond their PM-derived budget?

Planned curves omit tasks with incomplete start, duration, or PM data. Costs are
not part of any chart model."
  )

(viz/project-monthly-task-chart-spec sample-hours 2026)
(viz/project-cumulative-chart-spec sample-hours sample-tasks)
(viz/project-task-budget-chart-spec sample-tasks)

(defn payload-measurements
  "Return stable payload size observations for the review report."
  []
  (let [specs [(viz/person-monthly-project-chart-spec sample-hours 2026)
               (viz/person-activity-heatmap-spec sample-hours 2026)
               (viz/person-period-chart-spec sample-hours 2026)]]
    {:charts (count specs)
     :serialized-bytes (mapv #(count (.getBytes (viz/safe-json %) "UTF-8")) specs)
     :total-hours (tab/sum-col sample-hours :hours)}))

(kind/md
  "## Checks and operational decision

Totals are conserved across the source and aggregations. Specifications are
created once per request and category reduction happens before JSON
serialization. These fixture payloads are small, so no chart cache is justified."
  )

(payload-measurements)

(defn -main
  "Generate the static Clay visualization review under target/visualization."
  [& _]
  (clay/make! {:source-path "dev/agiladmin/visualization_notebook.clj"
               :base-target-path "target/visualization"
               :format [:html]
               :show false
               :browse false
               :live-reload false}))
