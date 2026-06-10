(ns agiladmin.logging-test
  (:require [midje.sweet :refer :all])
  (:import [org.eclipse.jetty.util.log Log]
           [org.slf4j LoggerFactory]))

(fact "Jetty logging does not depend on the Clojure SLF4J adapter"
      (Class/forName "com.github.fzakaria.slf4j.timbre.TimbreLoggerAdapter")
      => (throws ClassNotFoundException)

      (.getName (class (LoggerFactory/getLogger "agiladmin.logging-test")))
      => "org.slf4j.impl.SimpleLogger"

      (let [result (promise)
            worker (Thread.
                    (fn []
                      (try
                        (.warn (Log/getLogger "agiladmin.logging-test")
                               "Worker failure"
                               (RuntimeException. "expected test exception"))
                        (deliver result :logged)
                        (catch Throwable error
                          (deliver result error)))))]
        (.start worker)
        (.join worker)
        @result)
      => :logged)
