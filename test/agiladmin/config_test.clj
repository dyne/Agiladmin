(ns agiladmin.config-test
  (:use midje.sweet)
  (:require [agiladmin.config :as conf]
            [failjure.core :as f]
            [schema.core :as s]
            [clojure.pprint :refer [pprint]]))

(def config (yaml-read "test/assets/agiladmin.yaml"))

(fact "Configuration loading tests"

      (fact "Global configuration"

              (s/validate conf/Config config) => truthy

              config =>
              {:agiladmin {:budgets {:git "ssh://dyne.org/dyne/budgets"
                                     :path "test/assets/"
                                     :ssh-key "id_rsa"}
                           :projects ["UNO" "DUE" "TRE"]
                           :source {:git "https://github.com/dyne/agiladmin" :update true}}
               ;; the fields below are added by agiladmin on loading
               :appname "agiladmin-test"
               :filename "agiladmin.yaml"
               :paths ["test/assets/"]})

      (fact "Project configuration"
            (let [proj (f/ok->> "UNO" (conf/load-project config))]
              (f/failed? proj) => false
              ;; TODO: make a test for wrong project returning failure
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
                          :pm 12}}}})))
