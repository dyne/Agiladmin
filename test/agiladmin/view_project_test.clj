(ns agiladmin.view-project-test
  (:require [agiladmin.view-project :as view-project]
            [failjure.core]
            [midje.sweet :refer :all]))

(fact "Project start dispatches infra projects to the infra view"
      (let [calls (atom [])]
        (with-redefs [agiladmin.config/load-project
                      (fn [_ _]
                        {:CORE {:type "infra"}})
                      agiladmin.view-project/infra
                      (fn [config account projname]
                        (swap! calls conj [config account projname])
                        {:status 200 :body "infra"})
                      agiladmin.view-project/rolling
                      (fn [& _] (throw (ex-info "unexpected rolling call" {})))
                      agiladmin.view-project/h2020
                      (fn [& _] (throw (ex-info "unexpected h2020 call" {})))]
          (let [response (view-project/start
                          {:params {:project " CORE "}}
                          {:agiladmin {}}
                          {:email "admin@example.org"})]
            (:status response) => 200
            (:body response) => "infra"
            @calls => [[{:agiladmin {}}
                        {:email "admin@example.org"}
                        "CORE"]]))))

(fact "Project start dispatches rolling projects to the rolling view"
      (with-redefs [agiladmin.config/load-project
                    (fn [_ _]
                      {:CORE {:type "rolling"}})
                    agiladmin.view-project/rolling
                    (fn [config account projname]
                      {:status 200 :body (str "rolling:" projname)})
                    agiladmin.view-project/infra
                    (fn [& _] (throw (ex-info "unexpected infra call" {})))
                    agiladmin.view-project/h2020
                    (fn [& _] (throw (ex-info "unexpected h2020 call" {})))]
        (let [response (view-project/start
                        {:params {:project "CORE"}}
                        {}
                        {:email "admin@example.org"})]
          (:body response) => "rolling:CORE")))

(fact "Project start dispatches default projects to the h2020 view"
      (with-redefs [agiladmin.config/load-project
                    (fn [_ _]
                      {:CORE {:type "h2020"}})
                    agiladmin.view-project/h2020
                    (fn [request config account]
                      {:status 200
                       :body (str "h2020:" (get-in request [:params :project]))})
                    agiladmin.view-project/infra
                    (fn [& _] (throw (ex-info "unexpected infra call" {})))
                    agiladmin.view-project/rolling
                    (fn [& _] (throw (ex-info "unexpected rolling call" {})))]
        (let [response (view-project/start
                        {:params {:project "CORE"}}
                        {}
                        {:email "admin@example.org"})]
          (:body response) => "h2020:CORE")))

(fact "Project edit renders an editor form when no edited yaml is submitted"
      (with-redefs [agiladmin.config/load-project
                    (fn [_ _]
                      {:CORE {:start_date "01-01-2026"
                              :duration 12
                              :tasks []}})]
        (let [response (view-project/edit
                        {:params {:project "CORE"}}
                        {}
                        {:email "admin@example.org"})]
          (:body response) => (contains "Project CORE: edit configuration")
          (:body response) => (contains "textarea")
          (:body response) => (contains "start_date"))))

(fact "Project edit renders the submitted yaml preview"
      (with-redefs [agiladmin.config/load-project
                    (fn [_ _]
                      {:CORE {:start_date "01-01-2026"
                              :duration 12
                              :tasks []}})]
        (let [response (view-project/edit
                        {:params {:project "CORE"
                                  :editor "CORE:\n  duration: 18\n"}}
                        {}
                        {:email "admin@example.org"})]
          (:body response) => (contains "CORE: apply project configuration")
          (:body response) => (contains "duration: 18"))))

(fact "Project edit surfaces project loading failures"
      (with-redefs [agiladmin.config/load-project
                    (fn [_ _]
                      (failjure.core/fail "Project configuration missing."))]
        (let [response (view-project/edit
                        {:params {:project "CORE"}}
                        {}
                        {:email "admin@example.org"})]
          (:body response) => (contains "Project configuration missing."))))
