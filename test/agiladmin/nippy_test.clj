(ns agiladmin.nippy-test
  (:use midje.sweet)
  (:require
   [clojure.java.io :as io]
   [agiladmin.core :refer :all]
   [auxiliary.config :as aux]
   [failjure.core :as f]
   [taoensso.timbre :as log]
   [taoensso.nippy :as nippy])
  (:import [java.io DataInputStream DataOutputStream]))

(def tmp "/tmp/agiladmin-nippy-test.dat")
(def budgets "test/assets/")
(def conf (aux/yaml-read "test/assets/agiladmin.yaml"))

(fact "Nippy load and save on filesystem a configuration EDN"
        (with-open [w (io/output-stream tmp)]
          (nippy/freeze-to-out! (DataOutputStream. w) conf))
        (with-open [r (io/input-stream tmp)]
          (nippy/thaw-from-in! (DataInputStream. r))) => conf)

(fact "Nippy load and save on filesystem a parsed timesheet EDN"
      (let [all-pjs (load-all-projects conf)
            ts (load-timesheet
                "test/assets/2016_timesheet_Luca-Pacioli.xlsx")
            ;; map-timesheets always takes a list
            hours (map-timesheets [ts])
            costs (derive-costs hours conf all-pjs)]

        (with-open [w (io/output-stream tmp)]
          (nippy/freeze-to-out! (DataOutputStream. w) costs))
        (with-open [r (io/input-stream tmp)]
          (nippy/thaw-from-in! (DataInputStream. r))) => costs))
