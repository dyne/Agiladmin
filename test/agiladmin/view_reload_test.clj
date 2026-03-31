(ns agiladmin.view-reload-test
  (:require [clojure.string :as str]
            [agiladmin.view-reload :as view-reload]
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
                        (exists [] true)
                        (listFiles [] (into-array java.io.File [(java.io.File. "placeholder")]))))
                    agiladmin.view-reload/safe-load-repo
                    (fn [_] nil)]
        (let [response (view-reload/start
                        {}
                        {:agiladmin {:budgets {:path "budgets/"
                                               :git "git@example.org:repo.git"
                                               :ssh-key "id_rsa"}}}
                        {:email "admin"})]
          (:body response) => (contains "Budgets path exists but is not a git repository yet: budgets/"))))

(fact "Reload page clones the budgets repository when the path is missing"
      (let [clone-calls (atom [])
            invalidations (atom [])]
        (with-redefs [clojure.java.io/file
                      (fn [path]
                        (proxy [java.io.File] [path]
                          (isDirectory [] false)
                          (exists [] (str/ends-with? path ".pub"))))
                      agiladmin.core/invalidate-runtime-caches!
                      (fn [config]
                        (swap! invalidations conj config))
                      agiladmin.view-reload/safe-load-repo
                      (fn [_] :repo)
                      clj-jgit.porcelain/with-identity
                      (fn [_ thunk]
                        (thunk))
                      clj-jgit.porcelain/git-clone
                      (fn [uri path]
                        (swap! clone-calls conj [uri path])
                        :cloned)
                      clj-jgit.porcelain/git-status
                      (fn [_] {:clean true})
                      agiladmin.webpage/render-git-log
                      (fn [_] [:div "log"])]
          (let [response (view-reload/start
                          {}
                          {:agiladmin {:budgets {:path "budgets/"
                                                 :git "git@example.org:repo.git"
                                                 :ssh-key "id_rsa"}}}
                          {:email "admin"})]
            @clone-calls => [["git@example.org:repo.git" "budgets/"]]
            (count @invalidations) => 1
            (:body response) => (contains "Cloned successfully from git@example.org:repo.git")))))

(fact "Reload page clones the budgets repository when the directory is empty"
      (let [clone-calls (atom [])
            repo-calls (atom 0)
            invalidations (atom [])]
        (with-redefs [clojure.java.io/file
                      (fn [path]
                        (proxy [java.io.File] [path]
                          (isDirectory [] (not (str/ends-with? path ".pub")))
                          (exists [] true)
                          (listFiles [] (into-array java.io.File []))))
                      agiladmin.core/invalidate-runtime-caches!
                      (fn [config]
                        (swap! invalidations conj config))
                      clj-jgit.porcelain/with-identity
                      (fn [_ thunk]
                        (thunk))
                      clj-jgit.porcelain/git-clone
                      (fn [uri path]
                        (swap! clone-calls conj [uri path])
                        :cloned)
                      clj-jgit.porcelain/git-status
                      (fn [_] {:clean true})
                      agiladmin.webpage/render-git-log
                      (fn [_] [:div "log"])
                      agiladmin.view-reload/safe-load-repo
                      (fn [_]
                        (swap! repo-calls inc)
                        (when (> @repo-calls 1)
                          :repo))]
          (let [response (view-reload/start
                          {}
                          {:agiladmin {:budgets {:path "budgets/"
                                                 :git "git@example.org:repo.git"
                                                 :ssh-key "id_rsa"}}}
                          {:email "admin"})]
            @clone-calls => [["git@example.org:repo.git" "budgets/"]]
            (count @invalidations) => 1
            (:body response) => (contains "Cloned successfully from git@example.org:repo.git")))))

(fact "Reload page invalidates all runtime caches after a successful git pull"
      (let [invalidations (atom [])]
        (with-redefs [clojure.java.io/file
                      (fn [_]
                        (proxy [java.io.File] ["budgets"]
                          (isDirectory [] true)
                          (exists [] true)
                          (listFiles [] (into-array java.io.File [(java.io.File. "placeholder")]))))
                      agiladmin.core/invalidate-runtime-caches!
                      (fn [config]
                        (swap! invalidations conj config))
                      agiladmin.view-reload/safe-load-repo
                      (fn [_] :repo)
                      clj-jgit.porcelain/with-identity
                      (fn [_ thunk]
                        (thunk))
                      clj-jgit.porcelain/git-pull
                      (fn [_] :pulled)
                      clj-jgit.porcelain/git-status
                      (fn [_] {:clean true})
                      agiladmin.webpage/render-git-log
                      (fn [_] [:div "log"])]
          (let [response (view-reload/start
                          {}
                          {:agiladmin {:budgets {:path "budgets/"
                                                 :git "git@example.org:repo.git"
                                                 :ssh-key "id_rsa"}}}
                          {:email "admin"})]
            (count @invalidations) => 1
            (:body response) => (contains "Reload completed.")))))

(fact "Reload page renders an error when git pull fails"
      (let [invalidations (atom [])]
        (with-redefs [clojure.java.io/file
                      (fn [_]
                        (proxy [java.io.File] ["budgets"]
                          (isDirectory [] true)
                          (exists [] true)))
                      agiladmin.core/invalidate-runtime-caches!
                      (fn [config]
                        (swap! invalidations conj config))
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
            @invalidations => []
            (:body response) => (contains "Error in git-pull: Remote origin did not advertise Ref for branch master.")))))
