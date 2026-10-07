(ns agiladmin.work-ledger-test
  (:use midje.sweet)
  (:require [agiladmin.work-ledger :as ledger]
            [agiladmin.work-service :as service]
            [agiladmin.work-ports :as ports]
            [agiladmin.work-policy :as policy]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [failjure.core :as f])
  (:import [java.nio.file Files Path]))

(def owner {:owner-id "owner-1" :credential-id "first"})
(def projects {:A {:tasks []} :B {:tasks []}})
(def settings (policy/validate-config {}))
(def entry {:external_id "task:1" :date "2024-02-29" :minutes 60 :project_id "A"})
(defn temp-dir [] (str (Files/createTempDirectory "work-ledger-" (make-array java.nio.file.attribute.FileAttribute 0))))
(defn open [dir] (ledger/open-ledger! {:data-path dir} (str dir "-budgets")))
(defn submit
  ([l id revision entries] (submit l owner "2024-02" id revision entries))
  ([l principal month id revision entries]
   (service/upsert! l principal projects settings
                    {:month month :request_id id :expected_revision revision :entries entries})))
(defn aggregate-file [dir]
  (first (filter #(.endsWith (.getName %) ".edn") (.listFiles (io/file dir)))))

(fact "Acknowledged batches survive restart; original receipts survive later writes and token rotation"
  (let [dir (temp-dir)
        first-result (with-open [l (open dir)] (submit l "r1" 0 [entry]))]
    (:revision first-result) => 1
    (with-open [l (open dir)]
      (:minutes (get-in (ports/read-year l "owner-1" "2024") [:records "task:1"])) => 60
      (:revision (submit l "r2" 1 [(assoc entry :external_id "second" :minutes 30)])) => 2
      (submit l (assoc owner :credential-id "rotated") "2024-02" "r1" 0 [entry]) => first-result
      (:code (submit l "r1" 0 [(assoc entry :minutes 61)])) => :request-id-conflict
      (count (:audit (ports/read-year l "owner-1" "2024"))) => 2
      (.exists (io/file (str (aggregate-file dir) ".bak"))) => true)))

(fact "One month's validation is all-or-nothing and ownership never comes from command fields"
  (with-open [l (open (temp-dir))]
    (:code (submit l "bad" 0 [entry (assoc entry :external_id "invalid" :project_id "unknown")])) => :unknown-project
    (:records (ports/read-year l "owner-1" "2024")) => {}
    (:code (submit l "bad" 0 [entry (assoc entry :external_id "other-month" :date "2024-03-01")])) => :invalid-batch
    (:code (submit l "bad" 0 [entry entry])) => :duplicate-id
    (:code (submit l "bad" 0 [(assoc entry :person "Another Person")])) => :unknown-field
    (:code (submit l "bad" 0 [(assoc entry :minutes 1400) (assoc entry :external_id "more" :minutes 41)])) => :day-overflow
    (:code (ports/transact-month! l "owner-1" "2024-02" 0 "bad"
                                 {:op :upsert :records [(assoc (policy/validate-record owner projects settings entry) :owner-id "other")]})) => :invalid-batch
    (:revision (submit l "good" 0 [entry])) => 1
    (:records (ports/read-year l "other" "2024")) => {}
    (:revision (submit l {:owner-id "other"} "2024-02" "good" 0 [entry])) => 1))

(fact "Owner-wide request and external IDs cannot silently move into other months or years"
  (with-open [l (open (temp-dir))]
    (submit l "first" 0 [entry]) => map?
    (:code (submit l owner "2025-02" "first" 0 [(assoc entry :date "2025-02-01")])) => :request-id-conflict
    (:code (submit l owner "2025-02" "new" 0 [(assoc entry :date "2025-02-01")])) => :cross-month-id
    (:code (submit l owner "2024-03" "new" 0 [(assoc entry :date "2024-03-01")])) => :cross-month-id
    (:records (ports/read-year l "owner-1" "2025")) => {}))

(fact "Concurrent equal revisions admit exactly one complete command"
  (with-open [l (open (temp-dir))]
    (let [gate (promise)
          jobs (mapv (fn [n] (future @gate (submit l (str "race" n) 0 [(assoc entry :external_id (str n))]))) (range 8))]
      (deliver gate true)
      (count (remove f/failed? (mapv deref jobs))) => 1
      (count (:records (ports/read-year l "owner-1" "2024"))) => 1
      (count (:audit (ports/read-year l "owner-1" "2024"))) => 1)))

(fact "Pre-commit disk failure preserves prior records and receipts"
  (with-open [l (open (temp-dir))]
    (submit l "first" 0 [entry]) => map?
    (let [before (ports/read-year l "owner-1" "2024")]
      (with-redefs [ledger/durable-write! (fn [& _] (throw (java.io.IOException. "disk failure")))]
        (:code (submit l "failure" 1 [(assoc entry :minutes 120)])) => :storage-failure)
      (ports/read-year l "owner-1" "2024") => before
      (:revision (submit l "failure" 1 [(assoc entry :minutes 120)])) => 2)))

(fact "Corrupt, tagged and unsupported aggregates are refused without replacement"
  (let [dir (temp-dir)]
    (with-open [l (open dir)]
      (submit l "first" 0 [entry]) => map?
      (doseq [text ["{" "#evil/data {:a 1}" "{:schema-version 99}" "{} {}"]]
        (spit (aggregate-file dir) text)
        (:code (ports/read-year l "owner-1" "2024")) => :corrupt-ledger
        (:code (submit l "next" 1 [entry])) => :corrupt-ledger
        (slurp (aggregate-file dir)) => text))))

(fact "Single process writer is enforced in this JVM and an actual separate JVM"
  (let [dir (temp-dir)]
    (with-open [l (open dir)]
      (:code (open dir)) => :writer-already-running
      (let [result (shell/sh (str (System/getProperty "java.home") "/bin/java")
                             "-cp" (System/getProperty "java.class.path") "clojure.main" "-e"
                             (str "(require '[agiladmin.work-ledger :as l]) (println (:code (l/open-ledger! {:data-path "
                                  (pr-str dir) "} nil)))"))]
        (:exit result) => 0
        (.contains (:out result) ":writer-already-running") => true))
    (with-open [l (open dir)] (f/failed? l) => false)
    (let [result (shell/sh (str (System/getProperty "java.home") "/bin/java")
                           "-cp" (System/getProperty "java.class.path") "clojure.main" "-e"
                           (str "(require '[agiladmin.work-ledger :as l] '[failjure.core :as f]) (with-open [ledger (l/open-ledger! {:data-path "
                                (pr-str dir) "} nil)] (println (f/failed? ledger)))"))]
      (:exit result) => 0
      (.contains (:out result) "false") => true)))

(fact "Failure after atomic replacement is resolved by replaying the persisted receipt"
  (let [dir (temp-dir)]
    (with-open [l (open dir)]
      (let [write ledger/durable-write!]
        (with-redefs [ledger/durable-write! (fn [& args] (apply write args) (throw (java.io.IOException. "lost acknowledgement")))]
          (:code (submit l "uncertain" 0 [entry])) => :storage-failure)))
    (with-open [l (open dir)]
      (:revision (submit l "uncertain" 0 [entry])) => 1
      (count (:audit (ports/read-year l "owner-1" "2024"))) => 1)))

(fact "Directory-fsync failure requires retry to establish durability before acknowledgement"
  (with-open [l (open (temp-dir))]
    (with-redefs-fn {#'agiladmin.work-ledger/sync-dir! (fn [& _] (throw (java.io.IOException. "fsync unavailable")))}
      #(do
         (:code (submit l "sync" 0 [entry])) => :storage-failure
         (:code (submit l "sync" 0 [entry])) => :storage-failure))
    (:revision (submit l "sync" 0 [entry])) => 1
    (count (:audit (ports/read-year l "owner-1" "2024"))) => 1))

(fact "Configured root containment and symlinks are refused"
  (let [dir (temp-dir) link (io/file dir "link") real (temp-dir)]
    (:code (ledger/open-ledger! {:data-path dir} dir)) => :invalid-config
    (Files/createSymbolicLink (.toPath link) (.toPath (io/file real)) (make-array java.nio.file.attribute.FileAttribute 0))
    (:code (open (str link))) => :storage-failure)
  (let [dir (temp-dir)]
    (with-open [l (open dir)]
      (submit l "first" 0 [entry]) => map?
      (let [file (aggregate-file dir) outside (java.io.File/createTempFile "ledger-outside" ".edn")]
        (.delete file)
        (Files/createSymbolicLink (.toPath file) (.toPath outside) (make-array java.nio.file.attribute.FileAttribute 0))
        (:code (submit l "next" 1 [entry])) => :storage-failure
        (slurp outside) => ""))))
