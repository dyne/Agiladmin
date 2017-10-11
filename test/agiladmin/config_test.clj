
(ns agiladmin.config-test
  (:require [agiladmin.config :as conf]
            [clojure.pprint :refer [pprint]]))

(def conf (conf/load-config "agiladmin" conf/default-settings))
; (pprint (conf/load "agiladmin" conf/default-settings))
(conf/load-project conf "decode")
