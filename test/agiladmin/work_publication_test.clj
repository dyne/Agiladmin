(ns agiladmin.work-publication-test
  (:use midje.sweet)
  (:require [agiladmin.mcp-tools-test :as fixtures]
            [agiladmin.mcp-http-test :as transport]
            [agiladmin.mcp.runtime :as runtime]
            [agiladmin.work-publication :as publication]
            [agiladmin.work-archive :as archive]
            [agiladmin.work-ports :as ports]
            [agiladmin.work-preview :as preview]
            [agiladmin.work-service :as service]
            [agiladmin.work-ledger :as ledger]
            [agiladmin.budgets-mutation :as mutation]
            [agiladmin.view-work :as view]
            [agiladmin.view-timesheet :as upload]
            [agiladmin.view-reload :as reload]
            [agiladmin.core :as core]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [failjure.core :as f])
  (:import [java.nio.file Files] [java.time Instant]))
(defn git [directory & args]
  (let [r (apply shell/sh "git" "-C" directory args)]
    (when-not (zero? (:exit r)) (throw (ex-info "Test Git failed" {:command (first args) :error (:err r)})))
    (str/trim (:out r))))
(defn env [run]
  (fixtures/fixture
   (fn [e]
     (let [remote (transport/temp-dir) root (:budgets e)]
       (git remote "init" "--bare" "-b" "master") (git root "init" "-b" "master")
       (git root "config" "user.name" "Test") (git root "config" "user.email" "test@example.test")
       (spit (io/file root "seed.txt") "seed") (git root "add" "seed.txt") (git root "commit" "-m" "Seed")
       (git root "remote" "add" "origin" remote) (git root "push" "-u" "origin" "master")
       (swap! (:config e) assoc-in [:agiladmin :budgets :git] remote)
       (let [p (publication/adapter (:runtime e) #(deref (:config e)))
             r (assoc (:runtime e) :publication p :read-approval #(publication/read-approval p %1 %2))]
         (runtime/install-publication! r {:read-baseline #(publication/read-baseline p %1 %2)
                                         :publication-status #(ports/publication-status p (:owner-id %1) %2)})
         (fixtures/save e "publish-seed" 0 [fixtures/entry fixtures/second-entry])
         (run (assoc e :runtime r :publication p :remote remote)))))))
(defn candidate [e]
  (preview/create! (get-in e [:runtime :preview-store]) (get-in e [:runtime :ledger])
                   (get-in e [:runtime :workbook]) (:owner e) ((get-in e [:runtime :deps :settings])) "2024-02"))
(defn publish [e value] (ports/publish-confirmed! (:publication e) (:owner e) value))
(defn count-commits [e] (parse-long (git (:budgets e) "rev-list" "--count" "HEAD")))
(defn file [e value] (io/file (:budgets e) (:filename value)))
(defn status [e] (ports/publication-status (:publication e) (:owner-id (:owner e)) "2024-02"))

(fact "Owner consent archives once, pushes the exact commit and preserves unrelated staged/dirty files"
  (env
   (fn [e]
     (spit (io/file (:budgets e) "staged.txt") "keep staged")
     (git (:budgets e) "add" "staged.txt")
     (spit (io/file (:budgets e) "seed.txt") "keep dirty")
     (let [v (candidate e) result (publish e v)]
       (:state result) => "pushed"
       (count-commits e) => 2
       (git (:budgets e) "diff" "--cached" "--name-only") => "staged.txt"
       (git (:budgets e) "diff" "--name-only") => "seed.txt"
       (git (:budgets e) "show" "--format=" "--name-only" "HEAD") => (:filename v)
       (git (:remote e) "rev-parse" "refs/heads/master") => (:commit-id result)
       (archive/fingerprint (.toPath (file e v))) => (:artifact-fingerprint v)
       (publish e v) => result
       (count-commits e) => 2
       (:fingerprint (publication/read-baseline (:publication e) (:owner e) "2024-02")) => (:artifact-fingerprint v)
       (:state ((get-in e [:runtime :deps :publication-status]) (:owner e) "2024-02")) => "pushed"))))

(fact "Push rejection retains the authoritative local workbook/cache update; browser retry and replay reuse its commit"
  (env
   (fn [e]
     (let [v (candidate e) push archive/push-commit! invalidations (atom 0)]
       (with-redefs [archive/push-commit! (fn [& _] (throw (ex-info "rejected" {})))
                     core/invalidate-runtime-caches! (fn [_] (swap! invalidations inc))]
         (:state (publish e v)) => "failed")
       (pos? @invalidations) => true
       (count-commits e) => 2
       (archive/fingerprint (.toPath (file e v))) => (:artifact-fingerprint v)
       (:state (status e)) => "failed"
       (:retryable (status e)) => true
       ;; A historical approved local commit is pushable after corrections. It
       ;; never writes those corrections until a fresh preview is confirmed.
       (fixtures/save e "correction" 1 [(assoc fixtures/entry :minutes 300)])
       (let [request {:session {:auth {:id (:owner-id (:owner e)) :name (:person (:owner e))}}
                      :params (view/approval-fields v)}
             response (view/confirm request @(:config e) (:runtime e) (:preview-id v))]
         (:status response) => 200
         (str/includes? (:body response) "Publication state: pushed") => true)
       (:state (publish e v)) => "pushed"
       (count-commits e) => 2))))

(fact "Actual non-fast-forward rejection is recoverable without force or another local commit"
  (env
   (fn [e]
     (let [v (candidate e) rival (transport/temp-dir)]
       (git rival "clone" (:remote e) ".") (git rival "config" "user.name" "Rival")
       (git rival "config" "user.email" "rival@example.test")
       (spit (io/file rival "external.txt") "external") (git rival "add" ".") (git rival "commit" "-m" "External")
       (git rival "push" "origin" "master")
       (let [remote-head (git (:remote e) "rev-parse" "master")]
         (:state (publish e v)) => "failed"
         (git (:remote e) "rev-parse" "master") => remote-head
         (count-commits e) => 2
         ;; Operator's explicit local test repair retains the recorded commit.
         ;; The production retry never rewrites the remote or merges external work.
         (git (:remote e) "update-ref" "refs/heads/master" (git (:budgets e) "rev-parse" "HEAD~1"))
         (:state (publish e v)) => "pushed"
         (count-commits e) => 2)))))

(fact "Crash recovery at every durable boundary finds the same workbook/commit and never duplicates publication"
  (doseq [point [:after-prepared :after-write :after-commit :after-local-state :after-push]]
    (env
     (fn [e]
       (let [v (candidate e)]
         (binding [publication/*checkpoint* (fn [p] (when (= p point) (throw (AssertionError. "simulated process death"))))]
           (try (publish e v) (catch AssertionError _ :crashed)))
         (let [replacement (publication/adapter (:runtime e) #(deref (:config e)))]
           (:state (ports/publish-confirmed! replacement (:owner e) v)) => "pushed"
           (:state (ports/publish-confirmed! replacement (:owner e) v)) => "pushed"
           (count-commits e) => 2))))))

(fact "External workbook edits, stale policies and stale records cannot replace reviewed official data"
  (env
   (fn [e]
     (let [v (candidate e)]
       (fixtures/save e "changed" 1 [(assoc fixtures/entry :minutes 300)])
       (:code (publish e v)) => :stale-snapshot
       (.exists (file e v)) => false
       (count-commits e) => 1)))
  (env
   (fn [e]
     (let [v (candidate e)]
       (swap! (:config e) assoc-in [:agiladmin :mcp :paid-cap-minutes] 300)
       (:code (publish e v)) => :stale-snapshot
       (.exists (file e v)) => false)))
  (env
   (fn [e]
     (let [v (candidate e)]
       (with-redefs [archive/push-commit! (fn [& _] (throw (ex-info "rejected" {})))] (publish e v))
       (spit (file e v) "manual work")
       (:code (publish e v)) => :publication-conflict
       (slurp (file e v)) => "manual work"
       (:state (status e)) => "conflict"
       (count-commits e) => 2))))

(fact "Different months serialize the annual workbook and preserve both managed month baselines"
  (env
   (fn [e]
     (let [r (:runtime e) owner (:owner e) settings ((get-in r [:deps :settings]))
           _ (service/upsert! (:ledger r) owner @(:catalog e) settings
                             {:month "2024-03" :expected_revision 0 :request_id "march"
                              :entries [(assoc fixtures/entry :date "2024-03-01" :external_id "march")]})
           feb (candidate e)
           march (preview/create! (:preview-store r) (:ledger r) (:workbook r) owner settings "2024-03")
           results (doall (map deref [(future (publish e feb)) (future (publish e march))]))]
       (count (filter #(= "pushed" (:state %)) results)) => 1
       (count (filter f/failed? results)) => 1
       (let [remaining (if (= "pushed" (:state (first results))) "2024-03" "2024-02")
             fresh (preview/create! (:preview-store r) (:ledger r) (:workbook r) owner settings remaining)]
         (:state (publish e fresh)) => "pushed"
         (count-commits e) => 3
         (:fingerprint (publication/read-baseline (:publication e) owner "2024-02")) => (:artifact-fingerprint fresh)
         (:fingerprint (publication/read-baseline (:publication e) owner "2024-03")) => (:artifact-fingerprint fresh)
         (f/failed? (ports/inspect-month (:workbook r) owner "2024-02")) => false
         (f/failed? (ports/inspect-month (:workbook r) owner "2024-03")) => false)))))

(fact "Draft edits wait on the same owner lock; upload and reload use the shared repository lock"
  (env
   (fn [e]
     (let [entered (promise) release (promise) finished (promise)
           worker (future (ledger/with-owner-lock (get-in e [:runtime :ledger]) (:owner-id (:owner e))
                           #(do (deliver entered true) @release))) ]
       @entered
       (future (fixtures/save e "waited" 1 [(assoc fixtures/entry :minutes 300)]) (deliver finished true))
       (deref finished 100 false) => false
       (deliver release true) @worker
       (deref finished 5000 false) => true)))
  (let [calls (atom [])]
    (with-redefs [mutation/with-repository-lock (fn [p operation] (swap! calls conj p) :serialized)]
      (upload/upload {} {:agiladmin {:budgets {:path "same-root"}}} {}) => :serialized
      (upload/commit {} {:agiladmin {:budgets {:path "same-root"}}} {}) => :serialized
      (reload/start {} {:agiladmin {:budgets {:path "same-root"}}} {}) => :serialized
      @calls => ["same-root" "same-root" "same-root"])))

(fact "Upload/reload operations wait while publication owns the budgets mutation boundary"
  (env
   (fn [e]
     (let [value (candidate e) entered (promise) release (promise) upload-ran (promise) reload-ran (promise)]
       (with-redefs-fn {#'agiladmin.view-timesheet/upload-unlocked (fn [& _] (deliver upload-ran true))
                       #'agiladmin.view-reload/start-unlocked (fn [& _] (deliver reload-ran true))}
         (fn []
           (let [publisher (future (binding [publication/*checkpoint* (fn [p] (when (= p :after-write) (deliver entered true) @release))]
                                     (publish e value)))]
             (deref entered 10000 false) => true
             (let [u (future (upload/upload {} @(:config e) {}))
                   r (future (reload/start {} @(:config e) {}))]
               (deref upload-ran 100 false) => false
               (deref reload-ran 100 false) => false
               (deliver release true)
               (:state @publisher) => "pushed"
               @u @r
               @upload-ran => true
               @reload-ran => true))))))))

(fact "Failed preparation and corrupt publication state leave workbook and unrelated index intact"
  (env
   (fn [e]
     (let [v (candidate e)]
       (with-redefs [ledger/durable-write! (fn [& _] (throw (java.io.IOException. "disk unavailable")))]
         (f/failed? (publish e v)) => true)
       (.exists (file e v)) => false
       (count-commits e) => 1)))
  (env
   (fn [e]
     (let [v (candidate e)]
       (with-redefs [archive/push-commit! (fn [& _] (throw (ex-info "offline" {})))] (publish e v))
       (let [p (first (filter #(str/ends-with? (.getName %) ".edn") (.listFiles (.toFile (:root (:publication e))))))]
         (spit p "{:version 999}"))
       (:code (publish e v)) => :publication-storage-failure
       (archive/fingerprint (.toPath (file e v))) => (:artifact-fingerprint v)
       (count-commits e) => 2))))

(fact "Expired first approval cannot write; unexpected Git commits after a crash require reconciliation"
  (env
   (fn [e]
     (let [v (with-redefs [preview/lifetime-seconds -1] (candidate e))]
       (:code (publish e v)) => :preview-expired
       (.exists (file e v)) => false
       (count-commits e) => 1)))
  (env
   (fn [e]
     (let [v (candidate e)]
       (binding [publication/*checkpoint* (fn [p] (when (= p :after-write) (throw (AssertionError. "death"))))]
         (try (publish e v) (catch AssertionError _ nil)))
       (spit (io/file (:budgets e) "manual.txt") "manual commit")
       (git (:budgets e) "add" "manual.txt") (git (:budgets e) "commit" "-m" "Operator change")
       (:code (publish e v)) => :publication-conflict
       (count-commits e) => 2
       (archive/fingerprint (.toPath (file e v))) => (:artifact-fingerprint v)))))

(fact "Actual cached report readers refresh immediately even when the remote push fails"
  (env
   (fn [e]
     (swap! (:config e) assoc-in [:agiladmin :cache] true)
     (swap! (:config e) assoc-in [:agiladmin :budgets :path] (str (:budgets e) "/"))
     (let [config @(:config e) path (get-in config [:agiladmin :budgets :path]) v (candidate e)]
       (count (core/load-all-timesheets config path #".*_timesheet_.*xlsx$")) => 0
       (with-redefs [archive/push-commit! (fn [& _] (throw (ex-info "offline" {})))]
         (:state (publish e v)) => "failed")
       (count (core/load-all-timesheets config path #".*_timesheet_.*xlsx$")) => 1
       (contains? @core/timesheet-cache path) => true
       (core/invalidate-runtime-caches! config)))))
