(ns agiladmin.view-reload-test
  (:require [agiladmin.view-reload :as view-reload]
            [midje.sweet :refer :all]))

(fact "Reload page explains missing budgets git configuration"
      (let [response (view-reload/start
                      {}
                      {:agiladmin {:budgets {:path "budgets/"}}}
                      {:email "admin"})]
        (:body response) => (contains "Reload is unavailable until :agiladmin :budgets has git, path, and ssh-key configured.")))

(fact "Reload page explains when the budgets path is not a git repository"
      (with-redefs [clojure.java.io/file
                    (fn [_]
                      (proxy [java.io.File] ["budgets"]
                        (isDirectory [] true)
                        (exists [] true)))
                    agiladmin.view-reload/safe-load-repo
                    (fn [_] nil)]
        (let [response (view-reload/start
                        {}
                        {:agiladmin {:budgets {:path "budgets/"
                                               :git "git@example.org:repo.git"
                                               :ssh-key "id_rsa"}}}
                        {:email "admin"})]
          (:body response) => (contains "Budgets path exists but is not a git repository yet: budgets/"))))

(fact "Reload page renders an error when git pull fails"
      (with-redefs [clojure.java.io/file
                    (fn [_]
                      (proxy [java.io.File] ["budgets"]
                        (isDirectory [] true)
                        (exists [] true)))
                    agiladmin.view-reload/safe-load-repo
                    (fn [_] :repo)
                    clj-jgit.porcelain/with-identity
                    (fn [_ thunk]
                      (thunk))
                    clj-jgit.porcelain/git-pull
                    (fn [_]
                      (throw (ex-info "Remote origin did not advertise Ref for branch master." {})))]
        (let [response (view-reload/start
                        {}
                        {:agiladmin {:budgets {:path "budgets/"
                                               :git "git@example.org:repo.git"
                                               :ssh-key "id_rsa"}}}
                        {:email "admin"})]
          (:body response) => (contains "Error in git-pull: Remote origin did not advertise Ref for branch master."))))
