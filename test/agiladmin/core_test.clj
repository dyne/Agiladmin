(ns agiladmin.core-test
  (:require [midje.sweet :refer :all]
            [agiladmin.ring :as ring]
            [failjure.core :as f]))

(fact "Make sure the ring server starts"
      (f/ok? (ring/init)) => truthy)
