(ns agiladmin.config-test
  (:use midje.sweet)
  (:require [agiladmin.config :as conf]
            [agiladmin.core :as core]
            [failjure.core :as f]
            [schema.core :as s]))

(def config (conf/yaml-read "test/assets/agiladmin.yaml"))

(fact "Global configuration validates"
      (s/validate conf/Config config) => truthy
      config =>
      {:agiladmin {:budgets {:git "ssh://dyne.org/dyne/budgets"
                             :path "test/assets/"
                             :ssh-key "id_rsa"}
                   :pocketbase {:base-url "http://127.0.0.1:8090"
                                :users-collection "users"
                                :superuser-email "admin@example.org"
                                :superuser-password "changeme"}
                   :source {:git "https://github.com/dyne/agiladmin" :update true}}
       :appname "agiladmin-test"
       :filename "agiladmin.yaml"
       :paths ["test/assets/"]})

(fact "Project names can be discovered from yaml files in the budgets path"
      (conf/project-names {:agiladmin {:budgets {:path "test/assets/"}}
                           :filename "agiladmin.yaml"})
      => ["BADFIELDS" "BROKEN" "DIRECT" "DUE" "INVALIDYAML" "TRE" "UNO"])

(fact "Project discovery only considers top-level yaml files in the budgets path"
      (let [base "/tmp/project-discovery"
            _ (.mkdirs (java.io.File. (str base "/archived")))
            _ (spit (str base "/ACTIVE.yaml") "ACTIVE:\n  duration: 1\n")
            _ (spit (str base "/archived/OLD.yaml") "OLD:\n  duration: 1\n")]
        (conf/project-names {:agiladmin {:budgets {:path base}}
                             :filename "agiladmin.yaml"})
        => ["ACTIVE"]))

(fact "Named project configuration validates and normalizes task ids"
      (let [proj (f/ok->> "UNO" (conf/load-project config))]
        (f/failed? proj) => false
        proj => {:UNO
                 {:start_date "01-01-2016",
                  :duration 12,
                  :cph 45,
                  :rates {:L.Pacioli 40},
                  :tasks
                  [{:id "ALPHA",
                    :start_date "01-01-2016",
                    :text "Management and coordination",
                    :duration 36,
                    :pm 1}
                   {:id "BETA",
                    :start_date "01-01-2016",
                    :text "Social Dynamics",
                    :duration 14,
                    :pm 6}
                   {:id "GAMMA",
                    :text "Public design and technological implementation",
                    :start_date "01-07-2016",
                    :duration 36,
                    :pm 12}],
                  :idx
                  {:ALPHA
                   {:id "alpha",
                    :start_date "01-01-2016",
                    :text "Management and coordination",
                    :duration 36,
                    :pm 1},
                   :BETA
                   {:id "beta",
                    :start_date "01-01-2016",
                    :text "Social Dynamics",
                    :duration 14,
                    :pm 6},
                   :GAMMA
                   {:id "gamma",
                    :text "Public design and technological implementation",
                    :start_date "01-07-2016",
                    :duration 36,
                    :pm 12}}}}))

(fact "Direct-entry project files are accepted and keyed by filename"
      (let [direct-config {:agiladmin {:budgets {:path "test/assets/"}}
                           :filename "agiladmin.yaml"}
            proj (conf/load-project direct-config "DIRECT")]
        (f/failed? proj) => false
        proj => {:DIRECT
                 {:start_date "01-01-2016",
                  :duration 12,
                  :cph 25,
                  :tasks [{:id "ALPHA"
                           :start_date "01-01-2016"
                           :text "Direct project format"
                           :duration 1
                           :pm 1}],
                  :idx {:ALPHA {:id "alpha"
                                :start_date "01-01-2016"
                                :text "Direct project format"
                                :duration 1
                                :pm 1}}}}))

(fact "Project tasks may omit per-task schedule fields"
      (let [path "/tmp/PARTIAL.yaml"
            _ (spit path
                    (str "PARTIAL:\n"
                         "  start_date: 01-01-2026\n"
                         "  duration: 12\n"
                         "  tasks:\n"
                         "    - id: T1\n"
                         "      text: Coordination\n"
                         "      pm: 1\n"))
            partial-config {:agiladmin {:budgets {:path "/tmp/"}}
                            :filename "agiladmin.yaml"}
            proj (conf/load-project partial-config "PARTIAL")]
        (f/failed? proj) => false
        (get-in proj [:PARTIAL :tasks 0]) => {:id "T1"
                                              :text "Coordination"
                                              :pm 1}
        (get-in proj [:PARTIAL :idx :T1]) => {:id "T1"
                                              :text "Coordination"
                                              :pm 1}))

(fact "Project loader reports a missing project key explicitly"
      (let [broken-config {:agiladmin {:budgets {:path "test/assets/"}}
                           :filename "agiladmin.yaml"}
            proj (conf/load-project broken-config "BROKEN")]
        (f/failed? proj) => true
        (f/message proj) => (contains "does not define project BROKEN")))

(fact "Project loader reports field-level schema errors with the file path"
      (let [badfields-config {:agiladmin {:budgets {:path "test/assets/"}}
                              :filename "agiladmin.yaml"}
            proj (conf/load-project badfields-config "BADFIELDS")]
        (f/failed? proj) => true
        (f/message proj) => (contains "Invalid project configuration at test/assets/BADFIELDS.yaml")
        (f/message proj) => (contains ":duration")))

(fact "Application config loader reports field-level schema errors with the file path"
      (let [conf (conf/load-config "invalid-config" conf/default-settings)]
        (f/failed? conf) => true
        (f/message conf) => (contains "Invalid configuration at")
        (f/message conf) => (contains "test-resources/invalid-config.yaml")
        (f/message conf) => (contains ":path")))

(fact "Application config loader accepts an explicit yaml file path"
      (let [conf (conf/load-config "doc/agiladmin.pocketbase.yaml" conf/default-settings)]
        (f/failed? conf) => false
        (:filename conf) => "agiladmin.pocketbase.yaml"
        (:paths conf) => ["doc/agiladmin.pocketbase.yaml"]
        (get-in conf [:agiladmin :pocketbase :base-url]) => "http://127.0.0.1:8090"))

(fact "Application config loader reports an explicit missing file"
      (let [conf (conf/load-config "/tmp/does-not-exist-agiladmin.yaml" conf/default-settings)]
        (f/failed? conf) => true
        (f/message conf) => (contains "Configuration file not found: /tmp/does-not-exist-agiladmin.yaml")))

(fact "Project loader reports invalid YAML in the project file"
      (let [broken-config {:agiladmin {:budgets {:path "test/assets/"}}
                           :filename "agiladmin.yaml"}
            proj (conf/load-project broken-config "INVALIDYAML")]
        (f/failed? proj) => true
        (f/message proj) => (contains "Invalid YAML at test/assets/INVALIDYAML.yaml")))

(fact "Bulk project loads reuse one project file scan"
      (let [calls (atom 0)
            original-project-files @#'agiladmin.config/project-files
            counting-config {:agiladmin {:budgets {:path "test/assets/"}}
                             :filename "agiladmin.yaml"}]
        (core/invalidate-project-cache! counting-config)
        (with-redefs [agiladmin.config/project-files
                      (fn [cfg]
                        (swap! calls inc)
                        (original-project-files cfg))]
          (set (keys (core/load-all-projects counting-config)))
          => #{:DIRECT :DUE :TRE :UNO}
          @calls => 1)))

(fact "Bulk project loads keep valid projects and skip invalid ones"
      (let [projects (core/load-all-projects {:agiladmin {:budgets {:path "test/assets/"}}
                                              :filename "agiladmin.yaml"})]
        (set (keys projects)) => #{:DIRECT :DUE :TRE :UNO}
        (get-in projects [:DIRECT :cph]) => 25
        (get-in projects [:UNO :cph]) => 45
        (contains? projects :BADFIELDS) => false
        (contains? projects :BROKEN) => false
        (contains? projects :INVALIDYAML) => false))

(fact "Bulk project loads reuse cached projects for the same budgets path"
      (let [calls (atom 0)
            original-project-files @#'agiladmin.config/project-files
            counting-config {:agiladmin {:budgets {:path "test/assets/"}}
                             :filename "agiladmin.yaml"}]
        (core/invalidate-project-cache! counting-config)
        (with-redefs [agiladmin.config/project-files
                      (fn [cfg]
                        (swap! calls inc)
                        (original-project-files cfg))]
          (core/load-all-projects counting-config)
          (core/load-all-projects counting-config)
          @calls => 1)))

(fact "Bulk project loads keep cache entries separated by budgets path"
      (let [dir-a "/tmp/agiladmin-project-cache-a/"
            dir-b "/tmp/agiladmin-project-cache-b/"
            _ (.mkdirs (java.io.File. dir-a))
            _ (.mkdirs (java.io.File. dir-b))
            _ (spit (str dir-a "ALPHA.yaml") "ALPHA:\n  duration: 1\n")
            _ (spit (str dir-b "BETA.yaml") "BETA:\n  duration: 2\n")
            conf-a {:agiladmin {:budgets {:path dir-a}} :filename "agiladmin.yaml"}
            conf-b {:agiladmin {:budgets {:path dir-b}} :filename "agiladmin.yaml"}]
        (core/invalidate-project-cache! conf-a)
        (core/invalidate-project-cache! conf-b)
        (set (keys (core/load-all-projects conf-a))) => #{:ALPHA}
        (set (keys (core/load-all-projects conf-b))) => #{:BETA}))

(fact "Bulk project loads refresh after explicit cache invalidation"
      (let [calls (atom 0)
            original-project-files @#'agiladmin.config/project-files
            counting-config {:agiladmin {:budgets {:path "test/assets/"}}
                             :filename "agiladmin.yaml"}]
        (core/invalidate-project-cache! counting-config)
        (with-redefs [agiladmin.config/project-files
                      (fn [cfg]
                        (swap! calls inc)
                        (original-project-files cfg))]
          (core/load-all-projects counting-config)
          (core/invalidate-project-cache! counting-config)
          (core/load-all-projects counting-config)
          @calls => 2)))

(fact "Application config loader currently rejects runtime-only keys missing from the schema"
      (let [conf (conf/load-config "extra-keys-config" conf/default-settings)]
        (f/failed? conf) => true
        (f/message conf) => (contains "Invalid configuration at")
        (f/message conf) => (contains "test-resources/extra-keys-config.yaml")
        (f/message conf) => (contains "disallowed-key")))
