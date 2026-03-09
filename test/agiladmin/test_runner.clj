(ns agiladmin.test-runner
  (:require [midje.repl :as midje]))

(defn -main [& _]
  (midje/load-facts :all)
  (let [ok? (every? true? (doall (map midje/check-one-fact
                                      (midje/fetch-facts :all))))]
    (shutdown-agents)
    (when-not ok?
      (throw (ex-info "Midje tests failed" {})))))
