(ns agiladmin.config-test
  (:use midje.sweet)
  (:require [auxiliary.config :as aux]
            [agiladmin.config :as conf]
            [clojure.pprint :refer [pprint]]))

(fact "Configuration load"
      (let [conf (aux/yaml-read "test/assets/agiladmin.yaml")]
        (pprint "Test config:")
        (pprint conf)
        conf => {:agiladmin {:budgets {:git "ssh://dyne.org/dyne/budgets", :path "test/assets/", :ssh-key "id_rsa"}, :projects ["UNO" "DUE" "TRE"], :source {:git "https://github.com/dyne/agiladmin", :update true}}}

        (fact "Project configuration load"
              (let [proj (aux/yaml-read "test/assets/proj_uno.yaml")]
                (pprint "Test project config:")
                (pprint proj)))))
