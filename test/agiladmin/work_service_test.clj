(ns agiladmin.work-service-test
  (:use midje.sweet)
  (:require [agiladmin.work-ledger-test :as fixture]
            [agiladmin.work-service :as service]
            [agiladmin.work-ports :as ports]
            [failjure.core :as f]))

(defn snap [l] (service/month-snapshot l fixture/owner fixture/settings "2024-02"))
(defn delete-records [l id revision ids]
  (service/delete! l fixture/owner fixture/settings
                   {:month "2024-02" :request_id id :expected_revision revision :ids ids}))

(fact "Corrections recompute the complete day; deletes leave immutable audit tombstones"
  (with-open [l (fixture/open (fixture/temp-dir))]
    (let [a (assoc fixture/entry :minutes 360)
          b (assoc fixture/entry :external_id "b" :minutes 240 :project_id "B")
          saved (fixture/submit l "first" 0 [a b])
          original (snap l)]
      (mapv :paid-minutes (get-in saved [:allocation :days 0 :assignments])) => [288 192]
      (:totals original) => {:recorded-minutes 600 :paid-minutes 480 :vol-minutes 120}
      (:revision (fixture/submit l "correct" 1 [(assoc a :minutes 120)])) => 2
      (:totals (snap l)) => {:recorded-minutes 360 :paid-minutes 360 :vol-minutes 0}
      (:revision (delete-records l "remove" 2 ["b"])) => 3
      (:totals (snap l)) => {:recorded-minutes 120 :paid-minutes 120 :vol-minutes 0}
      (:totals original) => {:recorded-minutes 600 :paid-minutes 480 :vol-minutes 120}
      (let [audit (:audit (ports/read-year l "owner-1" "2024"))]
        (count audit) => 3
        (get-in audit [2 :tombstones]) => ["b"]
        (get-in audit [2 :before "b" :minutes]) => 240
        (get-in audit [0 :after "b" :minutes]) => 240)
      (delete-records l "remove" 2 ["b"]) => (contains {:revision 3})
      (:code (delete-records l "remove" 2 ["task:1"])) => :request-id-conflict)))

(fact "Missing IDs and another owner's IDs fail the entire deletion without revealing ownership"
  (with-open [l (fixture/open (fixture/temp-dir))]
    (fixture/submit l "first" 0 [fixture/entry]) => map?
    (let [before (snap l)]
      (:code (delete-records l "bad" 1 ["task:1" "missing"])) => :record-not-found
      (:code (service/delete! l {:owner-id "other"} fixture/settings
                              {:month "2024-02" :request_id "bad" :expected_revision 0 :ids ["task:1"]})) => :record-not-found
      (snap l) => before
      (:code (delete-records l "bad" 0 ["task:1"])) => :revision-conflict)))

(fact "An explicit deletion permits reinserting the same owner ID in a different year"
  (let [dir (fixture/temp-dir)]
    (with-open [l (fixture/open dir)]
      (fixture/submit l "first" 0 [fixture/entry]) => map?
      (:code (fixture/submit l fixture/owner "2025-01" "move" 0 [(assoc fixture/entry :date "2025-01-01")])) => :cross-month-id
      (:revision (delete-records l "remove" 1 ["task:1"])) => 2
      (:revision (fixture/submit l fixture/owner "2025-01" "move" 0 [(assoc fixture/entry :date "2025-01-01")])) => 1)
    (with-open [l (fixture/open dir)]
      (:records (snap l)) => []
      (get-in (ports/read-year l "owner-1" "2025") [:records "task:1" :date]) => "2025-01-01"
      (:revision (fixture/submit l "first" 0 [fixture/entry])) => 1
      (:records (snap l)) => [])))

(fact "Moving entries between days recomputes both days and keeps one-month boundaries"
  (with-open [l (fixture/open (fixture/temp-dir))]
    (fixture/submit l "first" 0 [(assoc fixture/entry :minutes 600)
                                (assoc fixture/entry :external_id "b" :minutes 240 :project_id "B")]) => map?
    (fixture/submit l "move" 1 [(assoc fixture/entry :date "2024-02-28" :minutes 600)]) => map?
    (mapv #(select-keys % [:date :paid-minutes :vol-minutes]) (get-in (snap l) [:allocation :days]))
    => [{:date "2024-02-28" :paid-minutes 480 :vol-minutes 120}
        {:date "2024-02-29" :paid-minutes 240 :vol-minutes 0}]
    (:code (fixture/submit l "cross" 2 [(assoc fixture/entry :date "2024-03-01")])) => :invalid-batch
    (:revision (snap l)) => 2))

(fact "An explicit delete then insert can move an ID between months of one year"
  (with-open [l (fixture/open (fixture/temp-dir))]
    (fixture/submit l "first" 0 [fixture/entry]) => map?
    (delete-records l "remove" 1 ["task:1"]) => map?
    (:revision (fixture/submit l fixture/owner "2024-03" "insert" 0 [(assoc fixture/entry :date "2024-03-01")])) => 1
    (:records (snap l)) => []
    (:revision (service/month-snapshot l fixture/owner fixture/settings "2024-03")) => 1))

(fact "Snapshots and page cursors identify exact immutable records, revision and policy"
  (with-open [l (fixture/open (fixture/temp-dir))]
    (fixture/submit l "first" 0 [(assoc fixture/entry :external_id "z")
                                (assoc fixture/entry :external_id "a")
                                (assoc fixture/entry :external_id "b" :date "2024-02-28")]) => map?
    (let [original (snap l)
          page1 (service/get-month l fixture/owner fixture/settings "2024-02" 1 nil)
          cursor (:next-cursor page1)]
      (mapv :external_id (:records original)) => ["b" "a" "z"]
      (:possible-duplicate-ids original) => [["a" "z"]]
      (mapv :external_id (:records page1)) => ["b"]
      (mapv :external_id (:records (service/get-month l fixture/owner fixture/settings "2024-02" 2 cursor))) => ["a" "z"]
      (service/check-snapshot l fixture/owner fixture/settings original) => original
      (:code (service/snapshot-page original {:owner-id "other"} 1 cursor)) => :owner-mismatch
      (:code (service/get-month l fixture/owner fixture/settings "2024-02" 0 nil)) => :invalid-page
      (:code (service/get-month l fixture/owner fixture/settings "2024-02" 1 {:digest (:digest original) :offset -1})) => :stale-cursor
      (:code (service/get-month l fixture/owner (assoc fixture/settings :paid-cap-minutes 60) "2024-02" 1 cursor)) => :stale-cursor
      (:code (service/check-snapshot l fixture/owner (assoc fixture/settings :paid-cap-minutes 60) original)) => :stale-snapshot
      (service/upsert! l fixture/owner fixture/projects (assoc fixture/settings :paid-cap-minutes 60)
                       {:month "2024-02" :request_id "first" :expected_revision 0
                        :entries [(assoc fixture/entry :external_id "z")
                                  (assoc fixture/entry :external_id "a")
                                  (assoc fixture/entry :external_id "b" :date "2024-02-28")]})
      => (contains {:revision 1})
      (fixture/submit l "correct" 1 [(assoc fixture/entry :external_id "a" :minutes 61)]) => map?
      (:code (service/get-month l fixture/owner fixture/settings "2024-02" 1 cursor)) => :stale-cursor
      (:code (service/check-snapshot l fixture/owner fixture/settings original)) => :stale-snapshot
      (mapv :external_id (:records (service/snapshot-page original fixture/owner 2 cursor))) => ["a" "z"])))

(fact "Drafts retain all eight assignment combinations and block downstream export"
  (with-open [l (fixture/open (fixture/temp-dir))]
    (let [projects (into {} (map #(vector (keyword (str "P" %)) {:tasks []}) (range 8)))
          entries (mapv #(assoc fixture/entry :external_id (str %) :project_id (str "P" %) :minutes 1) (range 8))]
      (:revision (service/upsert! l fixture/owner projects fixture/settings
                                 {:month "2024-02" :expected_revision 0 :request_id "eight" :entries entries})) => 1
      (let [snapshot (snap l)]
        (count (:records snapshot)) => 8
        (:capacity snapshot) => (contains {:required-columns 8 :exportable? false})
        (:code (service/check-snapshot l fixture/owner fixture/settings snapshot)) => :column-overflow
        (count (:records (snap l))) => 8))))

(fact "Policy-independent record snapshots survive adapter restarts and bad month reads fail safely"
  (let [dir (fixture/temp-dir)
        original (with-open [l (fixture/open dir)]
                   (fixture/submit l "first" 0 [fixture/entry])
                   (snap l))]
    (with-open [l (fixture/open dir)]
      (snap l) => original
      (:code (service/month-snapshot l fixture/owner fixture/settings "2024-13")) => :invalid-month
      (:code (service/month-snapshot l {} fixture/settings "2024-02")) => :owner-required)))

(fact "Original client retries survive alias remapping and catalog removal without rewriting work"
  (let [dir (fixture/temp-dir)
        command {:month "2024-02" :expected_revision 0 :request_id "alias"
                 :entries [(-> fixture/entry (dissoc :project_id) (assoc :organization "org"))]}
        original (with-open [l (fixture/open dir)]
                   (service/upsert! l fixture/owner fixture/projects
                                    (assoc fixture/settings :organization-aliases {"org" ["A"]}) command))]
    (with-open [l (fixture/open dir)]
      (service/upsert! l fixture/owner {} fixture/settings command) => original
      (service/upsert! l fixture/owner fixture/projects
                       (assoc fixture/settings :organization-aliases {"org" ["B"]}) command) => original
      (:code (service/upsert! l fixture/owner {} fixture/settings
                             (assoc-in command [:entries 0 :minutes] 61))) => :request-id-conflict
      (get-in (ports/read-year l "owner-1" "2024") [:records "task:1" :project_id]) => "A")))
