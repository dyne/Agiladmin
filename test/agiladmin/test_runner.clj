(ns agiladmin.test-runner
  (:require [midje.repl :as midje]))

(doseq [ns-sym '[agiladmin.auth-test
                 agiladmin.config-test
                 agiladmin.core-test
                 agiladmin.dev-auth-test
                 agiladmin.graphics-test
                 agiladmin.handlers-test
                 agiladmin.pocketbase-integration-test
                 agiladmin.pocketbase-test
                 agiladmin.ring-test
                 agiladmin.session-test
                 agiladmin.tabular-test
                 agiladmin.timesheet-test
                 agiladmin.utils-test
                 agiladmin.version-test
                 agiladmin.view-auth-test
                 agiladmin.view-person-test
                 agiladmin.view-project-test
                 agiladmin.view-reload-test
                 agiladmin.view-timesheet-test
                 agiladmin.webpage-test
                 agiladmin.visualization-test]]
  (require ns-sym))

(defn -main [& _]
  (midje/load-facts :all)
  (let [ok? (every? true? (doall (map midje/check-one-fact
                                      (midje/fetch-facts :all))))]
    (shutdown-agents)
    (when-not ok?
      (throw (ex-info "Midje tests failed" {})))))
