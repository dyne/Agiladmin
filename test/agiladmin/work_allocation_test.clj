(ns agiladmin.work-allocation-test
  (:use midje.sweet)
  (:require [agiladmin.work-policy :as work]
            [failjure.core :as f]))

(defn record
  ([project minutes] (record project minutes false))
  ([project minutes voluntary]
   {:owner-id "owner" :external_id (str project ":" minutes ":" voluntary)
    :date "2026-10-06" :project_id project :task_id nil
    :minutes minutes :voluntary voluntary :note "Original note"}))

(defn day [cap records]
  (first (:days (work/allocate-records {:paid-cap-minutes cap} records))))

(defn shares [day]
  (mapv (juxt :project_id :task_id :paid-minutes :vol-minutes) (:assignments day)))

(fact "Paid budget uses whole-day eligible totals below, exactly at and above the cap"
  (shares (day 480 [(record "A" 360) (record "B" 60)]))
  => [["A" nil 360 0] ["B" nil 60 0]]
  (shares (day 480 [(record "A" 360) (record "B" 120)]))
  => [["A" nil 360 0] ["B" nil 120 0]]
  (shares (day 480 [(record "A" 360) (record "B" 240)]))
  => [["A" nil 288 72] ["B" nil 192 48]]
  (shares (day 0 [(record "A" 360) (record "B" 240)]))
  => [["A" nil 0 360] ["B" nil 0 240]]
  (shares (day 1440 [(record "A" 1439) (record "B" 1)]))
  => [["A" nil 1439 0] ["B" nil 1 0]]
  (:code (work/allocate-records {} [(record "A" 1440) (record "B" 1)])) => :day-overflow
  (:code (work/allocate-records {} [(record "A" 0)])) => :invalid-record
  (:code (work/allocate-records {:paid-cap-minutes 480.0} [])) => :invalid-config)

(fact "Canonical project/task IDs break remainder ties without arrival-order influence"
  (shares (day 2 [(record "C" 1) (record "B" 1) (record "A" 1)]))
  => [["A" nil 1 0] ["B" nil 1 0] ["C" nil 0 1]]
  (shares (day 2 [(assoc (record "A" 1) :task_id "Z")
                 (assoc (record "A" 1) :task_id "T")
                 (record "A" 1)]))
  => [["A" nil 1 0] ["A" "T" 1 0] ["A" "Z" 0 1]])

(fact "Explicit voluntary work consumes no cap and joins the same assignment's VOL total"
  (shares (day 480 [(record "A" 360) (record "B" 240 true)]))
  => [["A" nil 360 0] ["B" nil 0 240]]
  (shares (day 100 [(record "A" 80) (record "A" 50 true) (record "B" 80)]))
  => [["A" nil 50 80] ["B" nil 50 30]]
  (shares (day 480 [(record "A" 360 true) (record "B" 240 true)]))
  => [["A" nil 0 360] ["B" nil 0 240]]
  (:paid-minutes (day 480 [(record "A" 360 true)])) => 0
  (:days (work/allocate-records {} [])) => [])

(fact "Owners and local dates have independent caps across the entire record set"
  (let [result (work/allocate-records {:person-cap-overrides {"second" 600}}
                                     [(record "A" 600)
                                      (assoc (record "A" 600) :owner-id "second")
                                      (assoc (record "A" 600) :date "2026-10-07")])]
    (mapv (juxt :owner-id :date :cap-minutes :paid-minutes :vol-minutes) (:days result))
    => [["owner" "2026-10-06" 480 480 120]
        ["owner" "2026-10-07" 480 480 120]
        ["second" "2026-10-06" 600 600 0]]
    (every? #(= (:policy-hash result) (:policy-hash %)) (:days result)) => true))

(def property-cases
  (for [a [1 2 3 60 360] b [1 2 3 60 360] c [1 2 3 60 360]
        cap [0 1 2 10 480 600 1440]
        va [false true] vb [false true] vc [false true]]
    {:cap cap :records [(record "A" a va) (record "B" b vb) (record "C" c vc)]}))

(defn split-record [r]
  (if (= 1 (:minutes r)) [r]
      [(assoc r :external_id (str (:external_id r) ":split1") :minutes 1)
       (assoc r :external_id (str (:external_id r) ":split2") :minutes (dec (:minutes r)))]))

(fact "7000 bounded cases conserve assignment minutes, cap paid work and preserve equivalence"
  (count property-cases) => 7000
  (every? (fn [{:keys [cap records]}]
            (let [result (day cap records)
                  assigned (:assignments result)
                  eligible (reduce + (map #(if (:voluntary %) 0 (:minutes %)) records))]
              (and (= (:paid-minutes result) (min cap eligible))
                   (<= (:paid-minutes result) cap)
                   (= (:recorded-minutes result) (+ (:paid-minutes result) (:vol-minutes result)))
                   (every? #(and (= (:recorded-minutes %) (+ (:paid-minutes %) (:vol-minutes %)))
                                 (<= 0 (:paid-minutes %) (:eligible-minutes %))
                                 (<= 0 (:vol-minutes %))) assigned)
                   (= result (day cap (reverse records)))
                   (= result (day cap (mapcat split-record records))))))
          property-cases) => true)

(fact "Policy snapshots are stable across map order and detect effective policy changes"
  (let [base (work/policy-snapshot {})
        hash (:policy-hash base)
        left {:person-cap-overrides {"b" 600 :a 300}
              :organization-aliases {" Team " ["b" "a"] "team" ["A"]}}
        right {:organization-aliases {:TEAM ["A" "B"]}
               :person-cap-overrides {"a" 300 :b 600}}]
    (:policy-version base) => "daily-work/v1"
    (boolean (re-matches #"sha256:[0-9a-f]{64}" hash)) => true
    (:policy-hash (work/policy-snapshot work/defaults)) => hash
    (:policy-hash (work/policy-snapshot {:enabled true :data-path "/tmp/another-ledger"})) => hash
    (= (:policy-hash (work/policy-snapshot left)) (:policy-hash (work/policy-snapshot right))) => true
    (= (:policy-hash (work/policy-snapshot {:person-cap-overrides {:team/owner 600}
                                         :organization-aliases {:org/team ["A"]}}))
       (:policy-hash (work/policy-snapshot {:person-cap-overrides {"team/owner" 600}
                                         :organization-aliases {"org/team" ["A"]}}))) => true
    (not= (:policy-hash (work/policy-snapshot {:person-cap-overrides {:team/owner 600}}))
          (:policy-hash (work/policy-snapshot {:person-cap-overrides {:owner 600}}))) => true
    (every? #(not= hash (:policy-hash (work/policy-snapshot %)))
            [{:paid-cap-minutes 600} {:timezone "UTC"}
             {:person-cap-overrides {"owner" 0}}
             {:organization-aliases {"team" ["A"]}}]) => true
    (:policy-hash (work/allocate-records {} [])) => hash))

(fact "Column capacity covers the whole owner/month; paid and VOL each consume a column"
  (let [seven (mapv #(assoc (record (str "P" %) 60) :date (format "2026-10-%02d" %)) (range 1 8))
        seven-allocation (work/allocate-records {} seven)
        eight (conj seven (assoc (record "P8" 60) :date "2026-10-20"))
        eight-allocation (work/allocate-records {} eight)
        overflow (work/month-capacity eight-allocation "owner" "2026-10")]
    (:required-columns (work/month-capacity seven-allocation "owner" "2026-10")) => 7
    (:exportable? (work/month-capacity seven-allocation "owner" "2026-10")) => true
    (:required-columns overflow) => 8
    (:max-columns overflow) => 7
    (:exportable? overflow) => false
    (count (:assignments overflow)) => 8
    (reduce + (map :recorded-minutes (:days eight-allocation))) => 480
    (:required-columns (work/month-capacity eight-allocation "other-owner" "2026-10")) => 0
    (:required-columns (work/month-capacity eight-allocation "owner" "2026-11")) => 0
    (:code (work/month-capacity eight-allocation "owner" "2026-13")) => :invalid-month)
  (let [records [(record "A" 600) (assoc (record "A" 600) :date "2026-10-07")
                 (assoc (record "A" 60) :task_id "T1" :date "2026-10-08")
                 (assoc (record "A" 60 true) :task_id "T1" :date "2026-10-09")]
        allocation (work/allocate-records {} records)
        capacity (work/month-capacity allocation "owner" "2026-10")]
    (:required-columns capacity) => 4
    (mapv (juxt :project_id :task_id :tag :minutes) (:assignments capacity))
    => [["A" nil "" 960] ["A" nil "VOL" 240] ["A" "T1" "" 60] ["A" "T1" "VOL" 60]]
    (:policy-hash capacity) => (:policy-hash allocation)))

(fact "Seven/eight limits count tasks and paid/VOL separately, even on different days"
  (let [records [(record "A" 600)
                 (assoc (record "A" 60) :task_id "T1" :date "2026-10-07")
                 (assoc (record "A" 60 true) :task_id "T1" :date "2026-10-08")
                 (assoc (record "A" 60) :task_id "T2" :date "2026-10-09")
                 (assoc (record "B" 60) :date "2026-10-10")
                 (assoc (record "B" 60 true) :task_id "T1" :date "2026-10-11")]
        extra (assoc (record "B" 60) :task_id "T1" :date "2026-10-12")
        capacity #(work/month-capacity (work/allocate-records {} %) "owner" "2026-10")]
    (:required-columns (capacity records)) => 7
    (:exportable? (capacity records)) => true
    (:required-columns (capacity (conj records extra))) => 8
    (:exportable? (capacity (conj records extra))) => false
    (count (:assignments (capacity (conj records extra)))) => 8))
