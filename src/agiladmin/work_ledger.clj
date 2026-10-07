(ns agiladmin.work-ledger
  "Single-process file adapter. Receipts, audit and records share one durable commit."
  (:require [agiladmin.work-ports :as ports]
            [agiladmin.work-policy :as policy]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [failjure.core :as f])
  (:import [java.nio.file Files Path Paths LinkOption StandardOpenOption StandardCopyOption]
           [java.nio.channels FileChannel]
           [java.nio.charset StandardCharsets]
           [java.security MessageDigest]
           [java.time YearMonth]
           [java.io Closeable]))

(def ^:private no-links (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))
(defonce ^:private open-roots (atom #{}))
(defn- fail [code] (policy/error code [] "Ledger operation refused." "Refresh the draft or ask the operator to inspect ledger storage."))
(defn- digest [s]
  (apply str (map #(format "%02x" (bit-and (int %) 255))
                  (.digest (MessageDigest/getInstance "SHA-256") (.getBytes ^String s StandardCharsets/UTF_8)))))
(defn- safe-path! [^Path root ^Path path]
  (when-not (.startsWith path root) (throw (ex-info "Outside ledger root" {})))
  (loop [p path]
    (when p
      (when (Files/isSymbolicLink p) (throw (ex-info "Symlink ledger path" {})))
      (recur (.getParent p))))
  path)
(defn- sync-dir! [^Path root]
  (with-open [channel (FileChannel/open root (into-array StandardOpenOption [StandardOpenOption/READ]))]
    (.force channel true)))
(defn- write-sync! [^Path path value]
  (with-open [channel (FileChannel/open path (into-array StandardOpenOption
                                            [StandardOpenOption/WRITE StandardOpenOption/TRUNCATE_EXISTING]))]
    (let [buffer (java.nio.ByteBuffer/wrap (.getBytes (pr-str value) StandardCharsets/UTF_8))]
      (while (.hasRemaining buffer) (.write channel buffer)))
    (.force channel true)))
(defn durable-write!
  "Force temp bytes, atomically replace, then force directory before acknowledgement.
  Backup is a forced copy of the previous valid aggregate; never silently recover corruption."
  [^Path root ^Path target previous value]
  (let [temp (Files/createTempFile root ".ledger-" ".tmp" (make-array java.nio.file.attribute.FileAttribute 0))]
    (try
      (when previous
        (let [backup (safe-path! root (.resolve root (str (.getFileName target) ".bak")))
              bt (Files/createTempFile root ".backup-" ".tmp" (make-array java.nio.file.attribute.FileAttribute 0))]
          (try
            (write-sync! bt previous)
            (Files/move bt backup (into-array StandardCopyOption [StandardCopyOption/ATOMIC_MOVE StandardCopyOption/REPLACE_EXISTING]))
            (finally (Files/deleteIfExists bt)))))
      (write-sync! temp value)
      (Files/move temp target (into-array StandardCopyOption [StandardCopyOption/ATOMIC_MOVE StandardCopyOption/REPLACE_EXISTING]))
      (sync-dir! root)
      (finally (Files/deleteIfExists temp)))))

(defn- empty-year [owner year]
  {:schema-version 1 :owner-id owner :year year :months {} :records {} :receipts {} :audit []})
(defn- valid-state? [state owner year]
  (and (map? state) (= 1 (:schema-version state)) (= owner (:owner-id state)) (= year (:year state))
       (map? (:months state)) (map? (:records state)) (map? (:receipts state)) (vector? (:audit state))
       (not (f/failed? (policy/allocate-records {} (vals (:records state)))))
       (every? (fn [[id r]] (and (= id (:external_id r)) (= owner (:owner-id r))
                                  (policy/valid-date? (:date r)) (= year (subs (:date r) 0 4))
                                  (policy/nonblank-string? id) (string? (:note r)) (<= (policy/unicode-length (:note r)) 240)
                                  (integer? (:minutes r)) (<= 1 (:minutes r) 1440))) (:records state))
       (every? (fn [[month data]] (and (string? month) (str/starts-with? month (str year "-"))
                                      (integer? (:revision data)) (pos? (:revision data)))) (:months state))
       (every? (fn [[id receipt]] (and (policy/nonblank-string? id) (map? (:request receipt))
                                      (map? (:result receipt)) (= owner (get-in receipt [:result :owner-id])))) (:receipts state))))
(defn- load-state [root target owner year]
  (safe-path! root target)
  (if (Files/exists target no-links)
    (try
      (with-open [reader (java.io.PushbackReader. (Files/newBufferedReader target StandardCharsets/UTF_8))]
        (let [value (edn/read {:eof ::eof :readers {} :default (fn [& _] (throw (ex-info "Tagged EDN" {})))} reader)
              trailing (edn/read {:eof ::eof :readers {} :default (fn [& _] (throw (ex-info "Tagged EDN" {})))} reader)]
          (if (and (= trailing ::eof) (valid-state? value owner year)) value (fail :corrupt-ledger))))
      (catch Exception _ (fail :corrupt-ledger)))
    (empty-year owner year)))
(defn- month? [v]
  (and (string? v) (re-matches #"\d{4}-\d{2}" v)
       (try (<= 1 (.getYear (YearMonth/parse v)) 9999) (catch Exception _ false))))
(defn- owner-years [root owner]
  (with-open [files (Files/newDirectoryStream root (str (digest owner) "-*.edn"))]
    (mapv (fn [path]
            (let [year (subs (str (.getFileName ^Path path)) 65 69)]
              (load-state root path owner year))) files)))
(defn- apply-upsert [state owner month records]
  (cond
    (not (and (vector? records) (seq records))) (fail :invalid-batch)
    (not= (count records) (count (set (map :external_id records)))) (fail :duplicate-id)
    (not (every? #(and (= owner (:owner-id %)) (policy/nonblank-string? (:external_id %))
                      (policy/valid-date? (:date %)) (str/starts-with? (:date %) (str month "-"))
                      (integer? (:minutes %)) (<= 1 (:minutes %) 1440)) records)) (fail :invalid-batch)
    (some #(when-let [old (get-in state [:records (:external_id %)])]
             (not (str/starts-with? (:date old) (str month "-")))) records) (fail :cross-month-id)
    :else (let [updated (update state :records into (map (juxt :external_id identity) records))
                valid (policy/allocate-records {} (vals (:records updated)))]
            (cond (f/failed? valid) valid
                  (not (valid-state? updated owner (:year state))) (fail :invalid-batch)
                  :else updated))))

(defn- apply-delete [state month ids]
  (cond
    (not (and (vector? ids) (seq ids) (every? policy/nonblank-string? ids))) (fail :invalid-batch)
    (not= (count ids) (count (set ids))) (fail :duplicate-id)
    (some #(not (contains? (:records state) %)) ids) (fail :record-not-found)
    (some #(not (str/starts-with? (get-in state [:records % :date]) (str month "-"))) ids) (fail :cross-month-id)
    :else (update state :records #(apply dissoc % ids))))

(defrecord FileLedger [root channel process-lock locks closed]
  Closeable
  (close [_] (when (compare-and-set! closed false true)
               (try (.release process-lock) (.close channel)
                    (finally (swap! open-roots disj root)))))
  ports/WorkLedger
  (read-year [_ owner year]
    (if (or @closed (not (policy/nonblank-string? owner))
            (not (and (string? year) (re-matches #"\d{4}" year) (<= 1 (parse-long year) 9999))))
      (fail :invalid-ledger-read)
      (try (load-state root (.resolve root (str (digest owner) "-" year ".edn")) owner year)
           (catch Exception _ (fail :storage-failure)))))
  (transact-month! [this owner month expected request-id payload]
    (if (or @closed (not (policy/nonblank-string? owner)) (not (month? month))
            (not (and (integer? expected) (<= 0 expected))) (not (policy/nonblank-string? request-id)))
      (fail :invalid-command)
      (let [year (subs month 0 4)
            lock (get (swap! locks #(if (contains? % owner) % (assoc % owner (Object.)))) owner)]
        (locking lock
          (try
            (let [state (ports/read-year this owner year)
                  all-years (owner-years root owner)
                  receipt-key request-id
                  ;; Current server policy is not client retry identity. A cap
                  ;; change must not invalidate a historical command receipt.
                  request {:month month :expected_revision expected
                           :payload (if (contains? payload :input)
                                      (select-keys payload [:op :input]) (dissoc payload :policy))}
                  receipt (some #(get-in % [:receipts receipt-key]) all-years)
                  revision (get-in state [:months month :revision] 0)]
              (cond
                (f/failed? state) state
                (some f/failed? all-years) (fail :corrupt-ledger)
                receipt (if (= request (:request receipt))
                          ;; A previous directory force or acknowledgement may
                          ;; have failed after rename. Re-establish durability.
                          (do (sync-dir! root) (:result receipt))
                          (fail :request-id-conflict))
                (not= expected revision) (assoc (fail :revision-conflict) :revision revision)
                (not (contains? #{:upsert :delete} (:op payload))) (fail :invalid-command)
                (seq (remove (if (= :upsert (:op payload)) #{:op :records :policy :input} #{:op :ids :policy})
                             (keys payload))) (fail :invalid-command)
                (some (fn [other] (and (not= year (:year other))
                                       (some #(contains? (:records other) (:external_id %)) (:records payload))))
                      all-years) (fail :cross-month-id)
                :else
                (let [updated (if (= :delete (:op payload)) (apply-delete state month (:ids payload))
                                  (apply-upsert state owner month (:records payload)))]
                  (if (f/failed? updated) updated
                    (let [records (->> (vals (:records updated))
                                       (filter #(str/starts-with? (:date %) (str month "-")))
                                       (sort-by (juxt :date :external_id)) vec)
                          allocation (policy/allocate-records (:policy payload) records)
                          ids (if (= :delete (:op payload)) (:ids payload) (map :external_id (:records payload)))
                          result {:owner-id owner :month month :revision (inc revision)
                                  :records records :allocation allocation
                                  :capacity (policy/month-capacity allocation owner month)}
                          next-state (-> updated
                                         (assoc-in [:months month :revision] (inc revision))
                                         (assoc-in [:receipts receipt-key] {:request request :result result})
                                         (update :audit conj {:month month :revision (inc revision) :request-id request-id
                                                              :op (:op payload) :before (select-keys (:records state) ids)
                                                              :after (select-keys (:records updated) ids)
                                                              :tombstones (if (= :delete (:op payload)) (vec ids) [])}))]
                      (when (f/failed? allocation) (throw (ex-info "Invalid allocation policy" {})))
                      (durable-write! root (.resolve root (str (digest owner) "-" year ".edn"))
                                      (when (seq (:audit state)) state) next-state)
                      result)))))
            (catch Exception _ (fail :storage-failure))))))))

(defn open-ledger!
  "Composition boundary. Requires separate configured storage; rejects symlink ancestors.
  Holds an OS lock until close, including across separate JVM processes."
  [settings budgets-path]
  (f/attempt-all
   [config (policy/validate-config (assoc settings :enabled true) budgets-path)]
   (try
     (let [root (.normalize (.toAbsolutePath (Paths/get (:data-path config) (make-array String 0))))]
       (safe-path! root root)
       (Files/createDirectories root (make-array java.nio.file.attribute.FileAttribute 0))
       ;; Persist newly created root/ancestor directory entries as well as files;
       ;; forcing only the aggregate's directory cannot persist its own mkdir.
       (loop [directory root]
         (when directory (sync-dir! directory) (recur (.getParent directory))))
       ;; Closing any descriptor for a POSIX locked file can release the process's
       ;; lock. Refuse duplicate JVM opens before touching that file at all.
       (locking open-roots
         (if (contains? @open-roots root)
           (fail :writer-already-running)
           (let [lock-path (safe-path! root (.resolve root ".writer.lock"))
                 channel (FileChannel/open lock-path (into-array StandardOpenOption [StandardOpenOption/CREATE StandardOpenOption/WRITE]))]
             (try
               (if-let [lock (.tryLock channel)]
                 (do (swap! open-roots conj root)
                     (->FileLedger root channel lock (atom {}) (atom false)))
                 (do (.close channel) (fail :writer-already-running)))
               (catch Exception ex (.close channel) (throw ex)))))))
     (catch Exception _ (fail :storage-failure)))))
