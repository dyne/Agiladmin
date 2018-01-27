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

(fact "Nippy load and save on filesystem a complex EDN"
      (let [conf (aux/yaml-read "test/assets/agiladmin.yaml")]
        (with-open [w (io/output-stream tmp)]
          (nippy/freeze-to-out! (DataOutputStream. w) conf))
        (with-open [r (io/input-stream tmp)]
          (nippy/thaw-from-in! (DataInputStream. r))) => conf))
