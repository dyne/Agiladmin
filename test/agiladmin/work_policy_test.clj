(ns agiladmin.work-policy-test
  (:use midje.sweet)
  (:require [agiladmin.work-policy :as work]
            [agiladmin.config :as config]
            [failjure.core :as f]
            [schema.core :as s]))

(def projects {:A {:tasks [{:id "T1"}]} :B {:tasks []}})
(def owner {:owner-id "account-1" :person "Luca Pacioli" :role "admin"})
(def policy (work/validate-config {}))
(def entry {:external_id "tracker:1" :date "2024-02-29" :minutes 60 :project_id "a"})
(defn check-entry [record] (work/validate-record owner projects policy record))

(fact "Canonical records use authenticated ownership, strict dates and integer minutes"
  (check-entry entry) => {:external_id "tracker:1" :date "2024-02-29" :minutes 60
                         :owner-id "account-1" :project_id "A" :task_id nil :note "" :voluntary false}
  (mapv #(-> (check-entry (assoc entry :date %)) :code)
        ["2023-02-29" "2024-02-30" "2024-2-29" "0000-01-01" "2024-02-29T12:00:00Z"])
  => (every-checker #(every? #{:invalid-date} %) #(= 5 (count %)))
  (mapv #(-> (check-entry (assoc entry :minutes %)) :code) [0 -1 1.5 "60" 1441])
  => [:invalid-minutes :invalid-minutes :invalid-minutes :invalid-minutes :invalid-minutes]
  (:minutes (check-entry (assoc entry :minutes 1440))) => 1440
  (:code (check-entry (assoc entry :person "Someone Else"))) => :unknown-field
  (:code (work/validate-record {} projects policy entry)) => :owner-required
  (:code (check-entry nil)) => :invalid-record)

(fact "Projects and tasks resolve only from existing canonical definitions"
  (:task_id (check-entry (assoc entry :task_id " t1 "))) => "T1"
  (:code (check-entry (assoc entry :task_id "missing"))) => :unknown-task
  (:code (check-entry (assoc entry :task_id nil))) => :unknown-task
  (:code (check-entry (assoc entry :project_id "missing"))) => :unknown-project
  (:code (check-entry (assoc entry :organization "a"))) => :project-required
  (:code (check-entry (assoc entry :organization nil))) => :project-required
  (work/resolve-project projects {" Example  Org " ["a"]} {:organization "EXAMPLE org"}) => "A"
  (work/resolve-project projects {:org/team ["A"]} {:organization "ORG/team"}) => "A"
  (:candidates (work/resolve-project projects {"Org" ["a"] " org " ["B"]} {:organization "org"}))
  => ["A" "B"]
  (:code (work/resolve-project projects {} {:organization "unknown"})) => :unknown-organization
  (:code (work/resolve-project projects {"org" ["absent"]} {:organization "org"})) => :unknown-project)

(fact "Notes count Unicode characters, preserve literals, and reject control characters"
  (:note (check-entry (assoc entry :note "=SUM(A1)\n+literal @mention"))) => "=SUM(A1)\n+literal @mention"
  (f/failed? (check-entry (assoc entry :note (apply str (repeat 240 "😀"))))) => false
  (:code (check-entry (assoc entry :note (apply str (repeat 241 "😀"))))) => :invalid-note
  (:code (check-entry (assoc entry :note "\u0000"))) => :invalid-note
  (:code (check-entry (assoc entry :note nil))) => :invalid-note
  (:code (check-entry (assoc entry :voluntary "true"))) => :invalid-voluntary
  (:voluntary (check-entry (assoc entry :voluntary true))) => true)

(fact "Whole-day validation checks all records, including explicit volunteering"
  (f/failed? (work/validate-day-totals [(check-entry (assoc entry :minutes 1440))])) => false
  (:code (work/validate-day-totals [(check-entry (assoc entry :minutes 1400))
                                   (check-entry (assoc entry :external_id "second" :minutes 41 :voluntary true))]))
  => :day-overflow)

(fact "Optional config preserves legacy defaults and bounds every cap"
  (:enabled policy) => false
  (:timezone policy) => "Europe/Rome"
  (work/paid-cap policy "account-1") => 480
  (work/paid-cap (work/validate-config {:person-cap-overrides {:account-1 0}}) "account-1") => 0
  (work/paid-cap (work/validate-config {:person-cap-overrides {"account-1" 1440}}) "account-1") => 1440
  (mapv #(f/failed? (work/validate-config %))
        [{:paid-cap-minutes -1} {:paid-cap-minutes 1441} {:paid-cap-minutes 480.0}
         {:person-cap-overrides {"account-1" "480"}} {:timezone "No/SuchZone"}
         {:enabled true} {:unknown true} {:organization-aliases {"org" "A"}}])
  => [true true true true true true true true]
  (f/failed? (work/validate-config {:enabled true :data-path "/tmp/drafts"})) => false
  (f/failed? (work/validate-config {:data-path "/tmp/budgets/drafts"} "/tmp/budgets")) => true
  (f/failed? (work/validate-config {:data-path "/tmp/budgets"} "/tmp/budgets")) => true
  (f/failed? (work/validate-config {:data-path "/tmp/budgets-other"} "/tmp/budgets")) => false
  (f/failed? (work/validate-config {:person-cap-overrides {:one 480 "one" 600}})) => true
  (f/failed? (work/validate-config {:organization-aliases {"" ["A"]}})) => true
  (s/validate config/Config (config/yaml-read "test/assets/agiladmin.yaml")) => truthy
  (s/check config/Config (assoc-in (config/yaml-read "test/assets/agiladmin.yaml")
                                  [:agiladmin :mcp] {:paid-cap-minutes "480"})) => truthy
  (get-in (config/load-project (config/yaml-read "test/assets/agiladmin.yaml") "UNO")
          [:UNO :tasks 0 :id]) => "ALPHA")

(fact "The real config loader enforces conditional MCP settings without changing legacy files"
  (let [file (java.io.File/createTempFile "agiladmin-mcp-config-" ".yaml")
        base "appname: agiladmin\nagiladmin:\n  budgets:\n    git: local\n    ssh-key: unused\n    path: /tmp/mcp-config-budgets\n"]
    (try
      (spit file (str base "  mcp:\n    enabled: true\n    data-path: /tmp/mcp-config-ledger\n    paid-cap-minutes: 600\n"))
      (f/failed? (config/load-config (.getPath file) config/default-settings)) => false
      (spit file (str base "  mcp:\n    enabled: true\n"))
      (:code (config/load-config (.getPath file) config/default-settings)) => :invalid-config
      (spit file (str base "  mcp:\n    data-path: /tmp/mcp-config-budgets/drafts\n"))
      (:code (config/load-config (.getPath file) config/default-settings)) => :invalid-config
      (spit file (str base "  mcp:\n    timezone: Invalid/Zone\n"))
      (:code (config/load-config (.getPath file) config/default-settings)) => :invalid-config
      (finally (.delete file)))))

(fact "Identity maps refuse duplicate legacy dotted names and unsafe filenames"
  (work/owner-mapping [{:id "one" :name "Luca Pacioli"}])
  => {"one" {:owner-id "one" :person "Luca Pacioli" :person-alias "L.Pacioli"}}
  (:code (work/owner-mapping [{:id "one" :name "Luca Pacioli"} {:id "two" :name "Luigi Pacioli"}]))
  => :identity-collision
  (:code (work/owner-mapping [{:id "one" :name "Luca Pacioli"} {:id "one" :name "Other Person"}]))
  => :identity-collision
  (mapv #(-> (work/owner-mapping [{:id "one" :name %}]) :code)
        ["../outside" "a/b" "a\\b" " name" "" "bad\u0000name"])
  => [:invalid-identity :invalid-identity :invalid-identity :invalid-identity :invalid-identity :invalid-identity])
