(ns agiladmin.work-policy
  "Daily-work contracts. Record validation is pure; config paths are read only.
  No auth, filesystem mutations, workbook or Git effects."
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [failjure.core :as f]
            [schema.core :as s])
  (:import [java.time LocalDate YearMonth ZoneId]
           [java.time.format DateTimeParseException]
           [java.security MessageDigest]
           [java.nio.charset StandardCharsets]))

(defn nonblank-string? [v] (and (string? v) (not (str/blank? v))))
(defn cap? [v] (and (integer? v) (<= 0 v 1440)))
(def Nonblank (s/pred nonblank-string? 'nonblank-string))
(def Cap (s/pred cap? 'integer-minutes-0-to-1440))
(s/defschema McpConfig
  {(s/optional-key :enabled) s/Bool
   (s/optional-key :data-path) Nonblank
   (s/optional-key :timezone) Nonblank
   (s/optional-key :paid-cap-minutes) Cap
   ;; Keys are stable auth account IDs, never display names or token IDs.
   (s/optional-key :person-cap-overrides) {(s/cond-pre s/Keyword s/Str) Cap}
   (s/optional-key :organization-aliases) {(s/cond-pre s/Keyword s/Str) [Nonblank]}})

(def defaults {:enabled false :timezone "Europe/Rome" :paid-cap-minutes 480
               :person-cap-overrides {} :organization-aliases {}})

(defn error
  "Failjure-compatible failure with safe, structured repair information."
  [code field message next-action]
  (assoc (f/fail message) :code code :field field :next-action next-action))

(defn canonical-id [v]
  (when (nonblank-string? v) (str/upper-case (str/trim v))))

(defn alias-key [v]
  (when (nonblank-string? v)
    (-> v str/trim (str/replace #"\s+" " ") str/lower-case)))

(defn- key-text [k]
  ;; YAML keywordization must not erase the namespace in IDs/aliases with '/'.
  (if (keyword? k) (subs (str k) 1) k))

(defn validate-config
  "Validate optional MCP settings; omission leaves legacy applications disabled."
  ([settings] (validate-config settings nil))
  ([settings budgets-path]
   (try
     (s/validate McpConfig (or settings {}))
     (let [policy (merge defaults settings)]
       (ZoneId/of (:timezone policy))
       (cond
         (not (every? (comp nonblank-string? key-text)
                      (concat (keys (:person-cap-overrides policy))
                              (keys (:organization-aliases policy)))))
         (error :invalid-config [:mcp] "Owner and organization keys must be nonempty."
                "Configure stable account IDs and named organization aliases.")
         (not= (count (:person-cap-overrides policy))
               (count (set (map (comp key-text key) (:person-cap-overrides policy)))))
         (error :invalid-config [:mcp :person-cap-overrides] "Duplicate owner cap keys."
                "Configure each stable account ID once.")
         (and (:enabled policy) (not (nonblank-string? (:data-path policy))))
         (error :invalid-config [:mcp :data-path] "Enabled MCP requires data-path."
                "Configure a ledger directory outside the budgets repository.")
         (and (:data-path policy) budgets-path
              (.startsWith (.toPath (.getCanonicalFile (io/file (:data-path policy))))
                           (.toPath (.getCanonicalFile (io/file budgets-path)))))
         (error :invalid-config [:mcp :data-path] "Ledger data-path is inside the budgets repository."
                "Configure a separate ledger directory.")
         :else policy))
     (catch Exception _
       (error :invalid-config [:mcp] "Invalid MCP configuration."
              "Use known settings, a valid timezone, and integer caps in 0..1440.")))))

(defn paid-cap [policy owner-id]
  (let [overrides (:person-cap-overrides policy)]
    (get overrides owner-id
         (get overrides (keyword owner-id) (:paid-cap-minutes policy)))))

(defn resolve-project
  "Resolve against the supplied existing project catalog. Ambiguity stays explicit."
  [projects aliases {:keys [project_id organization] :as record}]
  (cond
    (= (contains? record :project_id) (contains? record :organization))
    (error :project-required [:project_id] "Supply project_id OR organization."
           "Ask the person for one existing project or organization.")

    (contains? record :project_id)
    (let [id (canonical-id project_id)]
      (if (and id (contains? projects (keyword id))) id
          (error :unknown-project [:project_id] "Unknown project."
                 "Choose an existing project from the owner catalog.")))

    :else
    (let [candidates (->> aliases
                          (filter (fn [[k _]] (= (alias-key (key-text k)) (alias-key organization))))
                          (mapcat val) (keep canonical-id) distinct sort vec)]
      (cond
        (empty? candidates)
        (error :unknown-organization [:organization] "Unknown organization alias."
               "Ask the person to choose an existing project.")
        (> (count candidates) 1)
        (assoc (error :ambiguous-organization [:organization] "Organization maps to multiple projects."
                      "Ask the person to choose a project_id.") :candidates candidates)
        (not (contains? projects (keyword (first candidates))))
        (error :unknown-project [:organization] "Alias references an unknown project."
               "Ask an administrator to repair the organization mapping.")
        :else (first candidates)))))

(def record-fields #{:external_id :date :minutes :project_id :organization :task_id :note :voluntary})

(defn valid-date? [date]
  (and (string? date) (boolean (re-matches #"\d{4}-\d{2}-\d{2}" date))
       (try (let [parsed (LocalDate/parse date)] (<= 1 (.getYear parsed) 9999))
            (catch DateTimeParseException _ false))))

(defn unicode-length [^String text] (.codePointCount text 0 (.length text)))

(defn validate-record
  "The owner comes exclusively from authenticated server context, including admins.
  Input uses keyword keys after JSON decoding. Returns a canonical record or failure."
  [owner projects policy record]
  (cond
    (not (nonblank-string? (:owner-id owner)))
    (error :owner-required [] "An authenticated owner is required." "Authenticate again.")
    (not (map? record))
    (error :invalid-record [] "A work record must be an object." "Supply the documented fields.")
    (seq (remove record-fields (keys record)))
    (error :unknown-field [] "Unknown record fields." "Remove extra fields, including caller-supplied person.")
    (not (nonblank-string? (:external_id record)))
    (error :invalid-id [:external_id] "external_id must be a nonempty string." "Use a stable owner-scoped ID.")
    (not (valid-date? (:date record)))
    (error :invalid-date [:date] "Expected a real ISO YYYY-MM-DD date." "Ask the person for the local work date.")
    (not (and (integer? (:minutes record)) (<= 1 (:minutes record) 1440)))
    (error :invalid-minutes [:minutes] "Minutes must be a positive integer at most 1440."
           "Ask the person for the duration in whole minutes.")
    (and (contains? record :note)
         (not (and (string? (:note record)) (<= (unicode-length (:note record)) 240)
                   (not (re-find #"[\x00-\x08\x0B\x0C\x0E-\x1F\x7F]" (:note record))))))
    (error :invalid-note [:note] "Notes must be plain text of at most 240 Unicode characters."
           "Shorten the note without losing its meaning.")
    (and (contains? record :voluntary) (not (instance? Boolean (:voluntary record))))
    (error :invalid-voluntary [:voluntary] "voluntary must be a boolean." "Use true or false.")
    :else
    (f/attempt-all
     [project (resolve-project projects (:organization-aliases policy) record)
      task (if (contains? record :task_id)
             (let [task (canonical-id (:task_id record))
                   tasks (set (keep (comp canonical-id :id) (get-in projects [(keyword project) :tasks])))]
               (if (and task (contains? tasks task)) task
                   (error :unknown-task [:task_id] "Unknown task under this project."
                          "Choose a task from this project's catalog or omit task_id.")))
             nil)]
     (merge (select-keys record [:external_id :date :minutes])
            {:owner-id (:owner-id owner) :project_id project :task_id task
             :note (get record :note "") :voluntary (get record :voluntary false)}))))

(defn validate-day-totals
  "Validate the complete proposed records, including existing draft entries."
  [records]
  (if-let [[[owner date] total]
           (first (sort-by key
                          (filter (fn [[_ minutes]] (> minutes 1440))
                                  (reduce (fn [totals r]
                                            (update totals [(:owner-id r) (:date r)] (fnil + 0) (:minutes r)))
                                          {} records))))]
    (assoc (error :day-overflow [:date] "Recorded day exceeds 1440 minutes."
                  "Correct the complete day's entries before saving.")
           :owner-id owner :date date :minutes total)
    records))

(defn owner-mapping
  "Map active account IDs to legacy workbook names. Refuse every dotted-name
  collision and unsafe filename rather than authorizing caller-supplied identity."
  [accounts]
  (let [dotname (fn [n] (let [parts (str/split (str/replace n "-" " ") #"\s+")]
                          (if (= 1 (count parts)) (first parts)
                              (str (first (first parts)) "." (second parts)))))
        safe? (fn [a] (and (nonblank-string? (:id a)) (nonblank-string? (:name a))
                           (= (:name a) (str/trim (:name a)))
                           (not (re-find #"[/\\\p{Cntrl}]|\.\." (:name a)))
                           (not (contains? #{"." ".."} (:name a))))) ]
    (if-not (every? safe? accounts)
      (error :invalid-identity [] "Account identity cannot form a safe workbook name."
             "Repair the account name and stable ID.")
      (let [owners (mapv (fn [a] {:owner-id (:id a) :person (:name a)
                                 :person-alias (dotname (:name a))}) accounts)]
        (if (or (not= (count owners) (count (set (map :owner-id owners))))
                (not= (count owners) (count (set (map (comp str/lower-case :person-alias) owners)))))
          (error :identity-collision [] "Accounts share an ID or dotted workbook alias."
                 "Resolve the account collision before provisioning credentials.")
          (into {} (map (juxt :owner-id identity) owners)))))))

(def policy-version "daily-work/v1")

(defn policy-snapshot
  "Snapshot effective allocation/resolution policy in stable, hashable order.
  Storage paths and enabled state do not change the domain policy. Version changes
  whenever allocation semantics change; published snapshots remain immutable."
  [settings]
  (f/attempt-all
   [policy (validate-config settings)]
   (let [aliases (reduce (fn [acc [alias projects]]
                           (update acc (alias-key (key-text alias)) (fnil into #{})
                                   (keep canonical-id projects)))
                         (sorted-map) (:organization-aliases policy))
         effective (sorted-map
                    :version policy-version
                    :timezone (:timezone policy)
                    :paid-cap-minutes (:paid-cap-minutes policy)
                    :person-cap-overrides (into (sorted-map)
                                                (map (fn [[id cap]] [(key-text id) cap])
                                                     (:person-cap-overrides policy)))
                    :organization-aliases (into (sorted-map)
                                                (map (fn [[alias projects]] [alias (vec (sort projects))]) aliases)))
         bytes (.digest (MessageDigest/getInstance "SHA-256")
                        (.getBytes (pr-str effective) StandardCharsets/UTF_8))]
     {:policy-version policy-version
      :policy-hash (str "sha256:" (apply str (map #(format "%02x" (bit-and (int %) 255)) bytes)))
      :policy effective})))

(defn- allocation-record? [record]
  (and (map? record) (nonblank-string? (:owner-id record))
       (valid-date? (:date record))
       (integer? (:minutes record)) (<= 1 (:minutes record) 1440)
       (nonblank-string? (:project_id record))
       (= (:project_id record) (canonical-id (:project_id record)))
       (or (nil? (:task_id record))
           (and (nonblank-string? (:task_id record))
                (= (:task_id record) (canonical-id (:task_id record)))))
       (instance? Boolean (:voluntary record))))

(defn- allocate-day
  [policy snapshot [[owner date] records]]
  (let [cap (paid-cap policy owner)
        assignments (reduce (fn [acc record]
                              (let [key [(:project_id record) (or (:task_id record) "")]
                                    minutes (:minutes record)]
                                (-> acc
                                    (update-in [key :recorded-minutes] (fnil + 0) minutes)
                                    (update-in [key :eligible-minutes] (fnil + 0)
                                               (if (:voluntary record) 0 minutes)))))
                            (sorted-map) records)
        eligible (reduce + 0 (map :eligible-minutes (vals assignments)))
        budget (min eligible cap)
        shares (mapv (fn [[key totals]]
                       (let [numerator (* budget (:eligible-minutes totals))]
                         (assoc totals :key key
                                :paid-minutes (if (zero? eligible) 0 (quot numerator eligible))
                                :remainder (if (zero? eligible) 0 (mod numerator eligible)))))
                     assignments)
        remaining (- budget (reduce + 0 (map :paid-minutes shares)))
        winners (set (map :key (take remaining (sort-by (juxt (comp - :remainder) :key) shares))))
        result (mapv (fn [{:keys [key recorded-minutes eligible-minutes paid-minutes]}]
                       (let [[project task] key
                             paid (+ paid-minutes (if (contains? winners key) 1 0))]
                         {:project_id project :task_id (when-not (empty? task) task)
                          :recorded-minutes recorded-minutes :eligible-minutes eligible-minutes
                          :paid-minutes paid :vol-minutes (- recorded-minutes paid)}))
                     shares)
        total (reduce + 0 (map :recorded-minutes result))]
    (merge (select-keys snapshot [:policy-version :policy-hash])
           {:owner-id owner :date date :cap-minutes cap :assignments result
            :recorded-minutes total :paid-minutes budget :vol-minutes (- total budget)})))

(defn allocate-records
  "Allocate COMPLETE canonical records across owners/days. Ledger callers must
  include existing entries and recalculate affected days after upsert/delete.
  Aggregate by project/task before largest-remainder allocation, so record IDs,
  splitting and arrival order cannot affect paid minutes. Original notes remain
  in the ledger records, never in assignment keys. Returns policy-bound days."
  [settings records]
  (f/attempt-all
   [policy (validate-config settings)
    snapshot (policy-snapshot policy)
    _canonical (if (every? allocation-record? records) true
                   (error :invalid-record [] "Allocation requires canonical validated records."
                          "Validate and resolve records before allocation."))
    _days (validate-day-totals records)]
   (assoc snapshot :days
          (mapv #(allocate-day policy snapshot %)
                (sort-by key (group-by (juxt :owner-id :date) records))))))

(defn month-capacity
  "Count nonzero project/task/tag combinations over the WHOLE owner/month.
  Overflow stays a valid draft result but must block downstream export before
  mutation. Paid and VOL consume separate B:H columns. Never consolidate here."
  [allocation owner-id month]
  (cond
    (f/failed? allocation) allocation
    (not (and (string? month) (re-matches #"\d{4}-\d{2}" month)
              (try (<= 1 (.getYear (YearMonth/parse month)) 9999)
                   (catch DateTimeParseException _ false))))
    (error :invalid-month [:month] "Expected a real ISO YYYY-MM month."
           "Choose a reporting month.")
    :else
    (let [totals (reduce (fn [acc assignment]
                           (reduce (fn [acc [field tag]]
                                     (let [minutes (get assignment field)]
                                       (if (pos? minutes)
                                         (update acc [(:project_id assignment) (or (:task_id assignment) "") tag]
                                                 (fnil + 0) minutes)
                                         acc)))
                                   acc [[:paid-minutes ""] [:vol-minutes "VOL"]]))
                         (sorted-map)
                         (mapcat :assignments
                                 (filter #(and (= owner-id (:owner-id %))
                                               (str/starts-with? (:date %) (str month "-")))
                                         (:days allocation))))
          assignments (mapv (fn [[[project task tag] minutes]]
                              {:project_id project :task_id (when-not (empty? task) task)
                               :tag tag :minutes minutes}) totals)
          required (count assignments)]
      {:owner-id owner-id :month month :required-columns required :max-columns 7
       :exportable? (<= required 7) :assignments assignments
       :policy-version (:policy-version allocation) :policy-hash (:policy-hash allocation)})))
