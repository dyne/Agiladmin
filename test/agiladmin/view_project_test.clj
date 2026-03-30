(ns agiladmin.view-project-test
  (:require [agiladmin.view-project :as view-project]
            [failjure.core]
            [midje.sweet :refer :all]))

(fact "Project start dispatches infra projects to the infra view"
      (let [calls (atom [])
            load-calls (atom 0)]
        (with-redefs [agiladmin.config/load-project
                      (fn [_ _]
                        (swap! load-calls inc)
                        {:CORE {:type "infra"}})
                      agiladmin.view-project/infra
                      (fn [config account projname project-conf project]
                        (swap! calls conj [config account projname project-conf project])
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
            @load-calls => 1
            @calls => [[{:agiladmin {}}
                        {:email "admin@example.org"}
                        "CORE"
                        {:CORE {:type "infra"}}
                        {:type "infra"}]]))))

(fact "Project list renders a compact filterable project list"
      (with-redefs [agiladmin.config/project-names
                    (fn [_] ["CORE" "ALPHA" "BETA"])]
        (let [response (view-project/list-all
                        {}
                        {}
                        {:email "admin@example.org"})]
          (:body response) => (contains "data-text-filter=\"projects-list\"")
          (:body response) => (contains "Filter projects")
          (:body response) => (contains "Clear Projects filter")
          (:body response) => (contains "data-text-filter-value=\"ALPHA\"")
          (:body response) => (contains "inline-flex max-w-full w-full"))))

(fact "Project start dispatches rolling projects to the rolling view"
      (let [load-calls (atom 0)]
        (with-redefs [agiladmin.config/load-project
                      (fn [_ _]
                        (swap! load-calls inc)
                        {:CORE {:type "rolling"}})
                    agiladmin.view-project/rolling
                    (fn [config account projname project-conf project]
                      {:status 200 :body (str "rolling:" projname)})
                    agiladmin.view-project/infra
                    (fn [& _] (throw (ex-info "unexpected infra call" {})))
                    agiladmin.view-project/h2020
                    (fn [& _] (throw (ex-info "unexpected h2020 call" {})))]
          (let [response (view-project/start
                          {:params {:project "CORE"}}
                          {}
                          {:email "admin@example.org"})]
            @load-calls => 1
            (:body response) => "rolling:CORE"))))

(fact "Project start dispatches default projects to the h2020 view"
      (let [load-calls (atom 0)]
        (with-redefs [agiladmin.config/load-project
                      (fn [_ _]
                        (swap! load-calls inc)
                        {:CORE {:type "h2020"}})
                    agiladmin.view-project/h2020
                    (fn [request config account projname project-conf project]
                      {:status 200
                       :body (str "h2020:" projname)})
                    agiladmin.view-project/infra
                    (fn [& _] (throw (ex-info "unexpected infra call" {})))
                    agiladmin.view-project/rolling
                    (fn [& _] (throw (ex-info "unexpected rolling call" {})))]
          (let [response (view-project/start
                          {:params {:project "CORE"}}
                          {}
                          {:email "admin@example.org"})]
            @load-calls => 1
            (:body response) => "h2020:CORE"))))

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

(fact "Manager project view omits edit controls and cost output"
      (with-redefs [agiladmin.config/load-project
                    (fn [_ _]
                      {:CORE {:type "h2020"
                              :duration 12
                              :tasks []}})
                    agiladmin.config/q (fn [_ _] "ignored/")
                    agiladmin.core/load-all-timesheets (fn [& _] [])
                    agiladmin.core/load-project-monthly-hours
                    (fn [_ _]
                      {:column-names [:month :name :project :task :tag :hours]
                       :rows [{:month "2026-01"
                               :name "Manager User"
                               :project "CORE"
                               :task "TASK-1"
                               :tag ""
                               :hours 15}]})
                    agiladmin.core/current-proj-month (fn [_] 3)
                    agiladmin.core/derive-empty-tasks (fn [_ _] {:rows []})
                    agiladmin.utils/now (fn [] {:year 2026 :month 1 :day 10})]
        (let [response (view-project/h2020
                        {:params {:project "CORE"}}
                        {:agiladmin {:budgets {:path "ignored/"}}}
                        {:email "manager@example.org"
                         :name "Manager User"
                         :role "manager"})]
          (:body response) => (contains "Task/Person totals")
          (:body response) =not=> (contains "Edit project configuration")
          (:body response) =not=> (contains ":cost")
          (:body response) =not=> (contains ">cost<"))))

(fact "Admin project monthly details retain cost output"
      (with-redefs [agiladmin.config/load-project
                    (fn [_ _]
                      {:CORE {:type "h2020"
                              :duration 12
                              :cph 100
                              :tasks []}})
                    agiladmin.config/q (fn [_ _] "ignored/")
                    agiladmin.core/load-all-timesheets (fn [& _] [])
                    agiladmin.core/load-project-monthly-hours
                    (fn [_ _]
                      {:column-names [:month :name :project :task :tag :hours]
                       :rows [{:month "2026-01"
                               :name "Admin User"
                               :project "CORE"
                               :task "TASK-1"
                               :tag ""
                               :hours 15}]})
                    agiladmin.core/derive-costs
                    (fn [hours _ _]
                      {:column-names [:month :name :project :task :tag :hours :cost]
                       :rows [{:month "2026-01"
                               :name "Admin User"
                               :project "CORE"
                               :task "TASK-1"
                               :tag ""
                               :hours 15
                               :cost 1500}]})
                    agiladmin.core/current-proj-month (fn [_] 3)
                    agiladmin.core/derive-empty-tasks (fn [_ _] {:rows []})
                    agiladmin.utils/now (fn [] {:year 2026 :month 1 :day 10})]
        (let [response (view-project/h2020
                        {:params {:project "CORE"}}
                        {:agiladmin {:budgets {:path "ignored/"}}}
                        {:email "admin@example.org"
                         :name "Admin User"
                         :role "admin"})]
          (:body response) => (contains ">cost<")
          (:body response) => (contains ">1500<"))))
