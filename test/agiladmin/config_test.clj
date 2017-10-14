(ns agiladmin.config-test
  (:use midje.sweet)
  (:require [auxiliary.config :as aux]
            [agiladmin.config :as conf]
            [clojure.pprint :refer [pprint]]))

(fact "Configuration load"
      (let [conf (aux/yaml-read "test/assets/agiladmin.yaml")]
        (pprint "Test config:")
        (pprint conf)
        conf => {:agiladmin {:git "https://github.com/dyne/agiladmin", :update true}, :budgets {:git "ssh://dyne.org/dyne/budgets", :path "budgets", :ssh-key "id_rsa"}, :projects ["proj_uno" "proj_due" "proj_tre"]}

        (fact "Project configuration load"
              (let [proj (aux/yaml-read "test/assets/proj_uno.yaml")]
                (pprint "Test project config:")
                (pprint proj)))))
