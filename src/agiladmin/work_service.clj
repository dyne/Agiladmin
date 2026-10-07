(ns agiladmin.work-service
  "Owner-authorized work use cases; transport and persistence are injected."
  (:require [agiladmin.work-policy :as policy]
            [agiladmin.work-ports :as ports]
            [failjure.core :as f]
            [clojure.string :as str])
  (:import [java.security MessageDigest]
           [java.nio.charset StandardCharsets]
           [java.time YearMonth]))

(declare valid-month?)

(defn upsert!
  [ledger owner projects settings {:keys [month expected_revision request_id entries] :as command}]
  (cond
    (seq (remove #{:month :expected_revision :request_id :entries} (keys command)))
    (policy/error :unknown-field [] "Unknown command fields." "Remove caller-supplied ownership and extra fields.")
    (not (and (vector? entries) (seq entries)))
    (policy/error :invalid-batch [:entries] "Supply a nonempty entries array." "Submit one month's records.")
    :else
    (let [state (when (and (valid-month? month) (policy/nonblank-string? (:owner-id owner)))
                  (ports/read-year ledger (:owner-id owner) (subs month 0 4)))
          receipt (get-in state [:receipts request_id])]
      (cond
        (f/failed? state) state
        ;; Replay is checked before resolving against today's catalog. Historical
        ;; success remains retryable after project removal or alias remapping.
        receipt (ports/transact-month! ledger (:owner-id owner) month expected_revision request_id
                                       {:op :upsert :input command})
        :else
        (let [records (mapv #(policy/validate-record owner projects settings %) entries)]
          (if-let [failure (first (filter f/failed? records))]
            failure
            (ports/transact-month! ledger (:owner-id owner) month expected_revision request_id
                                   {:op :upsert :records records :policy settings :input command})))))))

(defn delete! [ledger owner settings {:keys [month expected_revision request_id ids] :as command}]
  (if (seq (remove #{:month :expected_revision :request_id :ids} (keys command)))
    (policy/error :unknown-field [] "Unknown command fields." "Supply only month, IDs, revision and request ID.")
    (ports/transact-month! ledger (:owner-id owner) month expected_revision request_id
                           {:op :delete :ids ids :policy settings})))

(defn- valid-month? [month]
  (and (string? month) (re-matches #"\d{4}-\d{2}" month)
       (try (<= 1 (.getYear (YearMonth/parse month)) 9999) (catch Exception _ false))))
(defn- canonical [value]
  (cond (map? value) (into (sorted-map) (map (fn [[k v]] [k (canonical v)]) value))
        (sequential? value) (mapv canonical value)
        :else value))
(defn- hash-value [value]
  (str "sha256:"
       (apply str (map #(format "%02x" (bit-and (int %) 255))
                       (.digest (MessageDigest/getInstance "SHA-256")
                                (.getBytes (pr-str (canonical value)) StandardCharsets/UTF_8))))))

(defn month-snapshot
  "Immutable exact records and current policy. No workbook or publication effects."
  [ledger owner settings month]
  (cond
    (not (policy/nonblank-string? (:owner-id owner)))
    (policy/error :owner-required [] "Owner authentication required." "Authenticate again.")
    (not (valid-month? month))
    (policy/error :invalid-month [:month] "Expected ISO YYYY-MM." "Choose a real reporting month.")
    :else
    (f/attempt-all
     [state (ports/read-year ledger (:owner-id owner) (subs month 0 4))
      records (->> (vals (:records state)) (filter #(str/starts-with? (:date %) (str month "-")))
                   (sort-by (juxt :date :external_id)) vec)
      allocation (policy/allocate-records settings records)
      capacity (policy/month-capacity allocation (:owner-id owner) month)]
     (let [duplicates (->> records (group-by #(dissoc % :external_id)) vals
                           (filter #(> (count %) 1))
                           (map #(vec (sort (map :external_id %)))) sort vec)
           snapshot {:snapshot-version 1 :owner-id (:owner-id owner) :month month
                     :revision (get-in state [:months month :revision] 0)
                     :records records :allocation allocation :capacity capacity
                     :possible-duplicate-ids duplicates
                     :totals (reduce (fn [totals day] (merge-with + totals (select-keys day [:recorded-minutes :paid-minutes :vol-minutes])))
                                     {:recorded-minutes 0 :paid-minutes 0 :vol-minutes 0} (:days allocation))}]
       (assoc snapshot :digest (hash-value snapshot))))))

(defn snapshot-page
  "Paginate a retained immutable snapshot. Cursors are data, bound to its digest.
  Use get-month with a cursor to refuse mixing pages across live revisions/policy."
  [snapshot owner limit cursor]
  (cond
    (f/failed? snapshot) snapshot
    (not= (:owner-id owner) (:owner-id snapshot))
    (policy/error :owner-mismatch [] "Snapshot belongs to another owner." "Read your own month.")
    (not (and (integer? limit) (<= 1 limit 200)))
    (policy/error :invalid-page [:limit] "Page size must be 1..200." "Choose a bounded page size.")
    (and cursor (not (and (map? cursor) (= #{:digest :offset} (set (keys cursor)))
                                    (= (:digest snapshot) (:digest cursor))
                                    (integer? (:offset cursor)) (<= 0 (:offset cursor) (count (:records snapshot))))))
    (policy/error :stale-cursor [:cursor] "Cursor does not identify this snapshot." "Restart pagination from the first page.")
    :else
    (let [start (get cursor :offset 0) end (min (+ start limit) (count (:records snapshot)))]
      (-> (dissoc snapshot :records)
          (assoc :records (subvec (:records snapshot) start end)
                 :next-cursor (when (< end (count (:records snapshot))) {:digest (:digest snapshot) :offset end}))))))

(defn get-month [ledger owner settings month limit cursor]
  (snapshot-page (month-snapshot ledger owner settings month) owner limit cursor))

(defn check-snapshot
  "Downstream preview gate: check authenticated owner, live revision, policy and
  exact content digest. Call again under publication locking before mutation."
  [ledger owner settings snapshot]
  (if (not= (:owner-id owner) (:owner-id snapshot))
    (policy/error :owner-mismatch [] "Snapshot belongs to another owner." "Read your own month.")
    (f/attempt-all
     [current (month-snapshot ledger owner settings (:month snapshot))]
     (cond
       (not= (:digest current) (:digest snapshot))
       (policy/error :stale-snapshot [] "Draft or policy changed." "Create a fresh preview and obtain owner confirmation.")
       (not (:exportable? (:capacity current)))
       (assoc (policy/error :column-overflow [] "Month requires more than seven assignment columns."
                            "Ask the owner to explicitly consolidate assignments.") :capacity (:capacity current))
       :else current))))
