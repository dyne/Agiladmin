(ns agiladmin.config-test
  (:use midje.sweet)
  (:require [agiladmin.config :as conf]
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
                   :projects ["UNO" "DUE" "TRE"]
                   :source {:git "https://github.com/dyne/agiladmin" :update true}}
       :appname "agiladmin-test"
       :filename "agiladmin.yaml"
       :paths ["test/assets/"]})

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
      (let [direct-config {:agiladmin {:projects ["DIRECT"]
                                       :budgets {:path "test/assets/"}}}
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

(fact "Project loader reports a missing project key explicitly"
      (let [broken-config {:agiladmin {:projects ["BROKEN"]
                                       :budgets {:path "test/assets/"}}}
            proj (conf/load-project broken-config "BROKEN")]
        (f/failed? proj) => true
        (f/message proj) => (contains "does not define project BROKEN")))

(fact "Project loader reports field-level schema errors with the file path"
      (let [badfields-config {:agiladmin {:projects ["BADFIELDS"]
                                          :budgets {:path "test/assets/"}}}
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
