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
        (get-in conf [:agiladmin :auth :backend]) => "pocketbase"
        (get-in conf [:agiladmin :auth :pocketbase :base-url]) => "http://127.0.0.1:8090"
        (get-in conf [:agiladmin :pocketbase :base-url]) => nil))

(fact "Application config loader accepts the nested auth PocketBase config"
      (let [path "/tmp/agiladmin-auth-pocketbase.yaml"
            _ (spit path
                    (str "agiladmin:\n"
                         "  budgets:\n"
                         "    git: ssh://dyne.org/dyne/budgets\n"
                         "    ssh-key: id_rsa\n"
                         "    path: test/assets/\n"
                         "  auth:\n"
                         "    backend: pocketbase\n"
                         "    pocketbase:\n"
                         "      base-url: http://127.0.0.1:8090\n"
                         "      users-collection: users\n"
                         "      superuser-email: admin@example.org\n"
                         "      superuser-password: changeme\n"))
            conf (conf/load-config path conf/default-settings)]
        (f/failed? conf) => false
        (get-in conf [:agiladmin :auth :backend]) => "pocketbase"
        (get-in conf [:agiladmin :auth :pocketbase :users-collection]) => "users"))

(fact "Application config loader accepts the nested auth Pocket ID config"
      (let [path "/tmp/agiladmin-auth-pocket-id.yaml"
            _ (spit path
                    (str "agiladmin:\n"
                         "  budgets:\n"
                         "    git: ssh://dyne.org/dyne/budgets\n"
                         "    ssh-key: id_rsa\n"
                         "    path: test/assets/\n"
                         "  auth:\n"
                         "    backend: pocket-id\n"
                         "    pocket-id:\n"
                         "      issuer-url: https://pocket-id.example.org\n"
                         "      client-id: agiladmin\n"
                         "      client-secret: secret\n"
                         "      redirect-uri: https://agiladmin.example.org/auth/pocket-id/callback\n"
                         "      admin-group: agiladmin-admin\n"
                         "      manager-group: agiladmin-manager\n"
                         "      scopes:\n"
                         "        - openid\n"
                         "        - profile\n"
                         "        - email\n"
                         "        - groups\n"))
            conf (conf/load-config path conf/default-settings)]
        (f/failed? conf) => false
        (get-in conf [:agiladmin :auth :backend]) => "pocket-id"
        (get-in conf [:agiladmin :auth :pocket-id :issuer-url]) => "https://pocket-id.example.org"))

(fact "Pocket ID config defaults scopes to the standard OIDC set"
      (let [path "/tmp/agiladmin-auth-pocket-id-default-scopes.yaml"
            _ (spit path
                    (str "agiladmin:\n"
                         "  budgets:\n"
                         "    git: ssh://dyne.org/dyne/budgets\n"
                         "    ssh-key: id_rsa\n"
                         "    path: test/assets/\n"
                         "  auth:\n"
                         "    backend: pocket-id\n"
                         "    pocket-id:\n"
                         "      issuer-url: https://pocket-id.example.org\n"
                         "      client-id: agiladmin\n"
                         "      client-secret: secret\n"
                         "      redirect-uri: https://agiladmin.example.org/auth/pocket-id/callback\n"
                         "      admin-group: agiladmin-admin\n"
                         "      manager-group: agiladmin-manager\n"))
            conf (conf/load-config path conf/default-settings)]
        (f/failed? conf) => false
        (get-in conf [:agiladmin :auth :pocket-id :scopes]) => conf/default-pocket-id-scopes))

(fact "Application config loader rejects ambiguous nested auth providers without a backend selector"
      (let [path "/tmp/agiladmin-auth-ambiguous.yaml"
            _ (spit path
                    (str "agiladmin:\n"
                         "  budgets:\n"
                         "    git: ssh://dyne.org/dyne/budgets\n"
                         "    ssh-key: id_rsa\n"
                         "    path: test/assets/\n"
                         "  auth:\n"
                         "    pocketbase:\n"
                         "      base-url: http://127.0.0.1:8090\n"
                         "      users-collection: users\n"
                         "      superuser-email: admin@example.org\n"
                         "      superuser-password: changeme\n"
                         "    pocket-id:\n"
                         "      issuer-url: https://pocket-id.example.org\n"
                         "      client-id: agiladmin\n"
                         "      client-secret: secret\n"
                         "      redirect-uri: https://agiladmin.example.org/auth/pocket-id/callback\n"
                         "      admin-group: agiladmin-admin\n"
                         "      manager-group: agiladmin-manager\n"))
            conf (conf/load-config path conf/default-settings)]
        (f/failed? conf) => true
        (f/message conf) => (contains "auth.backend is required")))

(fact "Application config loader accepts an explicit dev auth backend"
      (let [path "/tmp/agiladmin-auth-dev.yaml"
            _ (spit path
                    (str "agiladmin:\n"
                         "  budgets:\n"
                         "    git: ssh://dyne.org/dyne/budgets\n"
                         "    ssh-key: id_rsa\n"
                         "    path: test/assets/\n"
                         "  auth:\n"
                         "    backend: dev\n"))
            conf (conf/load-config path conf/default-settings)]
        (f/failed? conf) => false
        (get-in conf [:agiladmin :auth :backend]) => "dev"))

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

(fact "Application config loader currently rejects runtime-only keys missing from the schema"
      (let [conf (conf/load-config "extra-keys-config" conf/default-settings)]
        (f/failed? conf) => true
        (f/message conf) => (contains "Invalid configuration at")
        (f/message conf) => (contains "test-resources/extra-keys-config.yaml")
        (f/message conf) => (contains "disallowed-key")))
