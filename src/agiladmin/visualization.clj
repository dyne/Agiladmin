;; Agiladmin visualization helpers

(ns agiladmin.visualization
  (:require
   [clojure.set :as set]
   [clojure.string :as str]
   [cheshire.core :as json]
   [failjure.core :as f]
   [scicloj.tableplot.v1.plotly :as plotly]
   [tablecloth.api :as tc]
   [agiladmin.tabular :as tab]))

(def month-pattern
  #"^(\d{4})-(\d{1,2})$")

(defn parse-month
  "Parse an application month value like `2026-1` or `2026-01`."
  [month]
  (let [value (str/trim (str month))]
    (if-let [[_ year month-number] (re-matches month-pattern value)]
      (let [year (Integer/parseInt year)
            month-number (Integer/parseInt month-number)]
        (if (and (<= 1 month-number) (<= month-number 12))
          {:year year
           :month month-number
           :month-index (+ (* year 12) month-number)
           :month-label (format "%04d-%02d" year month-number)
           :month-date (format "%04d-%02d-01" year month-number)
           :quarter (str "Q" (inc (quot (dec month-number) 3)))}
          (f/fail (str "Invalid month value: " value))))
      (f/fail (str "Invalid month value: " value)))))

(defn month-sort-key
  "Return a sortable key for a normalized month string."
  [month]
  (:month-index (parse-month month)))

(defn month->label
  "Normalize a month string to `YYYY-MM`."
  [month]
  (:month-label (parse-month month)))

(defn month->date
  "Normalize a month string to an ISO first-of-month date."
  [month]
  (:month-date (parse-month month)))

(defn month->quarter
  "Return the quarter label for a month string."
  [month]
  (:quarter (parse-month month)))

(defn to-tablecloth
  "Convert an Agiladmin tabular dataset into a Tablecloth dataset."
  [data]
  (tc/dataset (tab/rows data)))

(defn- escape-json-text
  [s]
  (str/escape s {\< "\\u003c"
                 \> "\\u003e"
                 \& "\\u0026"
                 \u2028 "\\u2028"
                 \u2029 "\\u2029"}))

(defn safe-json
  "Serialize a value to JSON and escape characters that can break a script tag."
  [value]
  (-> value
      json/generate-string
      escape-json-text))

(defn- chart-layout
  [title]
  {:title {:text title}
   :paper_bgcolor "rgba(0,0,0,0)"
   :plot_bgcolor "rgba(0,0,0,0)"
   :font {:family "system-ui, sans-serif"
          :color "#2e3440"}
   :margin {:l 48 :r 24 :t 48 :b 56}
   :legend {:orientation "h"
            :x 0
            :y -0.2}
   :xaxis {:gridcolor "#d8dee9"
           :zeroline false
           :automargin true}
   :yaxis {:gridcolor "#d8dee9"
           :zeroline false
           :automargin true}})

(defn realize-plot
  "Realize a raw Plotly spec through Tableplot."
  [spec]
  (plotly/plot spec))

(defn plotly-chart
  "Render a realized Plotly specification into a Hiccup chart block."
  [id title spec {:keys [description fallback]}]
  [:section {:class "space-y-3"}
   [:div {:class "space-y-1"}
    [:h2 {:class "text-xl font-semibold"} title]
    (when description
      [:p {:class "text-sm text-base-content/70"} description])]
   [:div {:class "rounded-box border border-base-300 bg-base-100 p-3 shadow-sm"}
    [:div {:id id
           :class "min-h-80 w-full"
           :data-plotly-chart "true"
           :data-plotly-spec (safe-json spec)}
     [:p {:class "text-sm text-base-content/60"} fallback]]]])

(defn- sum-hours
  [rows]
  (reduce + 0 (map #(double (or (:hours %) 0)) rows)))

(defn- complete-year-months
  [year rows]
  (let [year (Integer/parseInt (str year))
        rows-by-month (group-by #(month->label (:month %)) rows)]
    (mapv (fn [month]
            (let [month-label (format "%04d-%02d" year month)
                  info (parse-month month-label)
                  month-rows (get rows-by-month month-label [])]
              {:month month-label
               :month-index (:month-index info)
               :month-label month-label
               :month-date (:month-date info)
               :quarter (:quarter info)
               :hours (sum-hours month-rows)}))
          (range 1 13))))

(defn- group-sum
  [rows key-fn]
  (->> rows
       (group-by key-fn)
       (mapv (fn [[k group]]
               [k (sum-hours group)]))))

(defn person-monthly-project-data
  "Return monthly hours per project for a year."
  [hours year]
  (let [year (Integer/parseInt (str year))
        rows (->> (tab/rows hours)
                  (filter #(= (some-> % :month parse-month :year) year))
                  (sort-by #(month-sort-key (:month %))))
        months (complete-year-months year rows)
        projects (->> rows (map :project) distinct sort)
        by-month-project (group-by (juxt #(month->label (:month %)) :project) rows)]
    (tab/dataset
     (for [month months
           project projects]
       (let [month-project-rows (get by-month-project [(:month month) project] [])]
         (merge month
                {:project project
                 :hours (sum-hours month-project-rows)}))))))

(defn person-period-data
  "Return quarter totals for a year's personnel activity."
  [hours year]
  (let [year (Integer/parseInt (str year))
        rows (->> (tab/rows hours)
                  (filter #(= (some-> % :month parse-month :year) year)))]
    (tab/dataset
     (for [quarter ["Q1" "Q2" "Q3" "Q4"]]
       {:year year
        :period quarter
        :hours (sum-hours (filter #(= (month->quarter (:month %)) quarter) rows))}))))

(defn project-monthly-task-data
  "Return monthly hours per task for a project."
  [hours year]
  (let [year (Integer/parseInt (str year))
        rows (->> (tab/rows hours)
                  (filter #(= (some-> % :month parse-month :year) year))
                  (sort-by #(month-sort-key (:month %))))
        tasks (->> rows (map :task) distinct sort)
        months (complete-year-months year rows)
        by-month-task (group-by (juxt #(month->label (:month %)) :task) rows)]
    (tab/dataset
     (for [month months
           task tasks]
       (merge month
              {:task task
               :hours (sum-hours (get by-month-task [(:month month) task] []))})))))

(defn project-monthly-person-data
  "Return monthly hours per person for a project."
  [hours year]
  (let [year (Integer/parseInt (str year))
        rows (->> (tab/rows hours)
                  (filter #(= (some-> % :month parse-month :year) year))
                  (sort-by #(month-sort-key (:month %))))
        people (->> rows (map :name) distinct sort)
        months (complete-year-months year rows)
        by-month-person (group-by (juxt #(month->label (:month %)) :name) rows)]
    (tab/dataset
     (for [month months
           person people]
       (merge month
              {:name person
               :hours (sum-hours (get by-month-person [(:month month) person] []))})))))

(defn project-annual-hours-data
  "Return annual totals for multi-year project data."
  [hours]
  (let [rows (tab/rows hours)]
    (tab/dataset
     (for [[year rows] (->> rows
                            (group-by #(some-> % :month parse-month :year))
                            (sort-by key))]
       {:year year
        :hours (sum-hours rows)}))))

(defn task-budget-data
  "Build actual-versus-budget rows from project task configuration."
  [task-details]
  (tab/dataset
   (for [row (tab/rows task-details)]
     (let [hours (double (or (:hours row) 0))
           planned (double (* 150 (or (:pm row) 0)))]
       {:task (:task row)
        :description (:description row)
        :actual hours
        :planned planned
        :remaining (- planned hours)
        :utilization (if (pos? planned) (/ hours planned) 0)}))))

(defn cumulative-hours-data
  "Build cumulative actual and planned series for a project."
  [hours planned-hours]
  (let [hours-rows (tab/rows hours)
        planned-rows (tab/rows planned-hours)
        year (some-> hours-rows first :month parse-month :year)
        actual-months (complete-year-months year hours-rows)
        actual-by-month (group-by :month-label hours-rows)
        valid-tasks (filter #(and (:start %)
                                  (pos? (double (or (:duration %) 0)))
                                  (pos? (double (or (:pm %) 0))))
                            planned-rows)
        date-format (java.time.format.DateTimeFormatter/ofPattern "dd-MM-yyyy")
        planned-by-month
        (reduce
         (fn [acc row]
           (let [start (java.time.LocalDate/parse (:start row) date-format)
                 duration (int (or (:duration row) 0))
                 budget (* 150 (double (or (:pm row) 0)))
                 monthly-share (if (pos? duration) (/ budget duration) 0)]
             (reduce
              (fn [acc month-offset]
                (let [month (.plusMonths start month-offset)
                      month-label (format "%04d-%02d" (.getYear month) (.getMonthValue month))]
                  (update acc month-label (fnil + 0) monthly-share)))
              acc
              (range duration))))
         {}
         valid-tasks)]
    (tab/dataset
     (map (fn [actual]
            (let [month-label (:month-label actual)]
              {:month month-label
               :month-date (:month-date actual)
               :actual (sum-hours (get actual-by-month month-label []))
               :planned (double (or (get planned-by-month month-label) 0))}))
          actual-months))))

(defn- plot-bar
  [x y & {:keys [name color orientation text customdata hovertemplate]}]
  (cond-> {:type "bar"
           :x x
           :y y}
    name (assoc :name name)
    color (assoc :marker {:color color})
    orientation (assoc :orientation orientation)
    text (assoc :text text :textposition "auto")
    customdata (assoc :customdata customdata)
    hovertemplate (assoc :hovertemplate hovertemplate)))

(defn- plot-line
  [x y & {:keys [name color dash]}]
  (cond-> {:type "scatter"
           :mode "lines+markers"
           :x x
           :y y}
    name (assoc :name name)
    color (assoc :line (cond-> {:color color} dash (assoc :dash dash)))))

(defn- plot-heatmap
  [x y z]
  {:type "heatmap"
   :x x
   :y y
   :z z
   :colorscale "Blues"})

(defn person-monthly-project-chart-spec
  [hours year]
  (let [data (person-monthly-project-data hours year)
        rows (tab/rows data)
        months (mapv :month-label (complete-year-months year rows))
        projects (->> rows (map :project) distinct sort)
        traces (mapv (fn [project]
                       (let [project-rows (filter #(= (:project %) project) rows)]
                         (plot-bar
                          months
                          (mapv :hours project-rows)
                          :name project
                          :hovertemplate "%{x}<br>%{y:.1f} hours<extra>%{fullData.name}</extra>")))
                     projects)]
    (-> {:data traces
         :layout (assoc (chart-layout (str "Monthly activity in " year))
                        :barmode "stack"
                        :hovermode "x unified")}
        realize-plot)))

(defn person-period-chart-spec
  [hours year]
  (let [data (person-period-data hours year)
        rows (tab/rows data)]
    (-> {:data [(plot-bar (mapv :period rows)
                          (mapv :hours rows)
                          :name "Hours"
                          :hovertemplate "%{x}<br>%{y:.1f} hours<extra></extra>")]
         :layout (assoc (chart-layout (str "Quarterly activity in " year))
                        :barmode "group")}
        realize-plot)))

(defn person-activity-heatmap-spec
  [hours year]
  (let [year (Integer/parseInt (str year))
        rows (->> (tab/rows hours)
                  (filter #(= (some-> % :month parse-month :year) year))
                  (sort-by #(month-sort-key (:month %))))
        top-projects (->> rows
                          (group-by :project)
                          (map (fn [[project group]]
                                 [project (sum-hours group)]))
                          (sort-by second >)
                          (take 8))
        visible-projects (vec (map first top-projects))
        other-projects (set/difference (set (map :project rows))
                                       (set visible-projects))
        months (mapv #(format "%04d-%02d" year %) (range 1 13))
        project-order (cond-> visible-projects
                        (seq other-projects) (conj "Other"))
        matrix (mapv (fn [project]
                       (mapv (fn [month]
                               (sum-hours
                                (filter
                                 #(and (= (month->label (:month %)) month)
                                       (if (= project "Other")
                                         (contains? other-projects (:project %))
                                         (= (:project %) project)))
                                 rows)))
                             months))
                     project-order)]
    (-> {:data [(plot-heatmap months project-order matrix)]
         :layout (chart-layout (str "Project mix in " year))}
        realize-plot)))

(defn project-monthly-task-chart-spec
  [hours year]
  (let [data (project-monthly-task-data hours year)
        rows (tab/rows data)
        months (mapv :month-label (complete-year-months year rows))
        tasks (->> rows (map :task) distinct sort)
        traces (mapv (fn [task]
                       (let [task-rows (filter #(= (:task %) task) rows)]
                         (plot-bar months
                                   (mapv :hours task-rows)
                                   :name task
                                   :hovertemplate "%{x}<br>%{y:.1f} hours<extra>%{fullData.name}</extra>")))
                     tasks)]
    (-> {:data traces
         :layout (assoc (chart-layout (str "Monthly task activity in " year))
                        :barmode "stack"
                        :hovermode "x unified")}
        realize-plot)))

(defn project-monthly-person-chart-spec
  [hours year]
  (let [data (project-monthly-person-data hours year)
        rows (tab/rows data)
        months (mapv :month-label (complete-year-months year rows))
        people (->> rows (map :name) distinct sort)
        traces (mapv (fn [person]
                       (let [person-rows (filter #(= (:name %) person) rows)]
                         (plot-bar months
                                   (mapv :hours person-rows)
                                   :name person
                                   :hovertemplate "%{x}<br>%{y:.1f} hours<extra>%{fullData.name}</extra>")))
                     people)]
    (-> {:data traces
         :layout (assoc (chart-layout (str "Monthly person activity in " year))
                        :barmode "stack"
                        :hovermode "x unified")}
        realize-plot)))

(defn project-annual-hours-chart-spec
  [hours]
  (let [data (project-annual-hours-data hours)
        rows (tab/rows data)]
    (when (> (count rows) 1)
      (-> {:data [(plot-bar (mapv :year rows)
                            (mapv :hours rows)
                            :name "Hours"
                            :hovertemplate "%{x}<br>%{y:.1f} hours<extra></extra>")]
           :layout (chart-layout "Annual hours")}
        realize-plot))))

(defn chart-eligibility
  "Describe whether a chart has enough source data to be useful."
  [hours chart]
  (let [rows (tab/rows hours)
        active-months (count (distinct (map #(month->label (:month %))
                                            (filter #(pos? (double (or (:hours %) 0))) rows))))
        active-projects (count (distinct (map :project
                                              (filter #(pos? (double (or (:hours %) 0))) rows))))]
    (cond
      (empty? rows)
      {:status :empty :reason "No recorded hours for this period"}

      (and (= chart :heatmap)
           (or (< active-months 2) (< active-projects 2)))
      {:status :omitted
       :reason "A heatmap needs at least two active projects and two active months"}

      :else
      {:status :ready})))

(defn project-task-budget-chart-spec
  [task-details]
  (let [data (task-budget-data task-details)
        rows (->> (tab/rows data)
                  (sort-by :utilization >))
        tasks (mapv :task rows)]
    (-> {:data [(plot-bar (mapv :actual rows)
                          tasks
                          :name "Actual"
                          :orientation "h"
                          :hovertemplate "%{y}<br>%{x:.1f} hours<extra>Actual</extra>")
                (plot-bar (mapv :planned rows)
                          tasks
                          :name "Budget"
                          :orientation "h"
                          :hovertemplate "%{y}<br>%{x:.1f} hours<extra>Budget</extra>")]
         :layout (assoc (chart-layout "Task budget usage")
                        :barmode "group")}
        realize-plot)))

(defn project-cumulative-chart-spec
  [hours task-details]
  (let [hours-rows (tab/rows hours)
        task-rows (tab/rows task-details)
        year (some-> hours-rows first :month parse-month :year)
        valid-tasks (filter #(and (:start %)
                                  (pos? (double (or (:duration %) 0)))
                                  (pos? (double (or (:pm %) 0))))
                            task-rows)]
    (when (and year (seq valid-tasks))
      (let [months (complete-year-months year hours-rows)
            actual-by-month (group-by #(month->label (:month %)) hours-rows)
            date-format (java.time.format.DateTimeFormatter/ofPattern "dd-MM-yyyy")
            planned-by-month
            (reduce
             (fn [acc row]
               (let [start (java.time.LocalDate/parse (:start row) date-format)
                     duration (int (or (:duration row) 0))
                     budget (* 150 (double (or (:pm row) 0)))
                     monthly-share (if (pos? duration) (/ budget duration) 0)]
                 (reduce
                  (fn [acc month-offset]
                    (let [month (.plusMonths start month-offset)
                          month-label (format "%04d-%02d" (.getYear month) (.getMonthValue month))]
                      (update acc month-label (fnil + 0) monthly-share)))
                  acc
                  (range duration))))
             {}
             valid-tasks)
            actual-series (rest (reductions + 0 (map #(sum-hours (get actual-by-month (:month-label %) [])) months)))
            planned-series (rest (reductions + 0 (map #(double (or (get planned-by-month (:month-label %)) 0)) months)))
            month-labels (mapv :month-label months)]
        (-> {:data [(plot-line month-labels actual-series
                               :name "Actual"
                               :color "#5e81ac")
                    (plot-line month-labels planned-series
                               :name "Planned"
                               :color "#bf616a"
                               :dash "dash")]
             :layout (assoc (chart-layout "Cumulative hours")
                            :hovermode "x unified")}
            realize-plot)))))

(defn plotly-chart-block
  "Render a chart spec as a complete Hiccup block."
  [id title spec {:keys [description fallback]}]
  (plotly-chart id title spec {:description description
                               :fallback fallback}))
