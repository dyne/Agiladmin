(ns agiladmin.work-publication
  "Durable owner consent and recoverable archive orchestration. Lock order is
  ledger owner -> annual publication state -> shared budgets repository."
  (:require [agiladmin.work-ports :as ports]
            [agiladmin.work-policy :as policy]
            [agiladmin.work-ledger :as ledger]
            [agiladmin.work-preview :as preview]
            [agiladmin.work-service :as service]
            [agiladmin.work-archive :as archive]
            [agiladmin.mcp.credentials :as credentials]
            [agiladmin.budgets-mutation :as budgets]
            [agiladmin.core :as core]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [failjure.core :as f])
  (:import [java.nio.file Files Path LinkOption]
           [java.nio.file.attribute PosixFilePermissions FileAttribute]
           [java.nio.charset StandardCharsets] [java.time Instant]))

;; Injection point for crash tests only; production is a no-op.
(def ^:dynamic *checkpoint* (fn [_] nil))
(defn- refused [code]
  (policy/error code [] "Publication cannot proceed safely. Your draft and reviewed evidence are retained."
                "Review publication status; reconcile external changes or ask the operator to restore storage, then retry."))
(defn- state-file [adapter owner year]
  (archive/safe! (.resolve ^Path (:root adapter) (str (credentials/token-hash owner) "-" year ".edn"))))
(defn- state [adapter owner year]
  (let [p (state-file adapter owner year)]
    (if-not (Files/exists p (make-array LinkOption 0))
      {:version 1 :owner-id owner :year year :baselines {} :publications {} :latest {}}
      (do
        (when (> (Files/size p) preview/max-file-bytes) (throw (ex-info "Publication state too large" {})))
        (with-open [r (java.io.PushbackReader. (Files/newBufferedReader p StandardCharsets/UTF_8))]
          (let [opts {:eof ::eof :readers {} :default (fn [& _] (throw (ex-info "Tagged publication data" {})))}
                s (edn/read opts r)]
            (when-not (and (= ::eof (edn/read opts r)) (= 1 (:version s)) (= owner (:owner-id s)) (= year (:year s))
                           (map? (:baselines s)) (map? (:publications s)) (map? (:latest s))
                           (every? (fn [[id e]] (and (= id (:preview-id e)) (= owner (:owner-id e))
                                                    (contains? #{"prepared" "local-committed" "pushed" "conflict" "failed"} (:state e))
                                                    (string? (:preview-digest e)) (string? (:snapshot-digest e))
                                                    (string? (:artifact-fingerprint e)) (string? (:filename e)))) (:publications s)))
              (throw (ex-info "Invalid publication state" {}))) s))))))
(defn- save! [adapter previous next]
  (let [p (state-file adapter (:owner-id next) (:year next))]
    (when (> (count (.getBytes (pr-str next) StandardCharsets/UTF_8)) preview/max-file-bytes)
      (throw (ex-info "Publication state too large" {})))
    (ledger/durable-write! (:root adapter) p previous next)
    (Files/setPosixFilePermissions p (PosixFilePermissions/fromString "rw-------")) next))
(defn- annual-lock [adapter owner year]
  (let [key [owner year]] (get (swap! (:locks adapter) #(if (contains? % key) % (assoc % key (Object.)))) key)))
(defn- with-annual [adapter owner year operation]
  (locking (annual-lock adapter owner year) (operation)))
(defn- guidance [entry]
  (cond
    (= "pushed" (:state entry)) "The reviewed workbook is archived and pushed. Draft corrections require a fresh preview and owner confirmation."
    (= "conflict" (:state entry)) "External workbook or Git changes need operator reconciliation. No external work has been overwritten."
    (:commit-id entry) "The workbook is official locally, but remote publication is pending. Retry this approved publication; the existing commit will be reused."
    :else "Retry this approved publication to recover an interrupted archive. If the draft changed or the review expired before any workbook was archived, create a fresh review."))
(defn public-status [entry]
  (if entry
    (merge (select-keys entry [:state :revision :preview-id :preview-digest :commit-id])
           {:next-action (guidance entry) :retryable (contains? #{"prepared" "local-committed" "failed"} (:state entry))})
    {:state "draft" :next-action "Create a preview and confirm in your own browser."}))
(defn read-approval [adapter owner value]
  (try
    (with-annual adapter (:owner-id owner) (subs (get-in value [:snapshot :month]) 0 4)
      #(let [e (get-in (state adapter (:owner-id owner) (subs (get-in value [:snapshot :month]) 0 4))
                      [:publications (:preview-id value)])]
         (when (= (:preview-digest value) (:preview-digest e)) (public-status e))))
    (catch Exception _ (refused :publication-storage-failure))))
(defn read-baseline [adapter owner month]
  (try
    (with-annual adapter (:owner-id owner) (subs month 0 4)
      #(let [s (state adapter (:owner-id owner) (subs month 0 4))]
         (when-let [baseline (get-in s [:baselines month])]
           (assoc baseline :fingerprint (:annual-fingerprint s)))))
    (catch Exception _ (refused :publication-storage-failure))))
(defn- phase! [adapter s entry phase extra]
  (let [e (merge entry extra {:state phase :updated-at (str (Instant/now))})]
    (save! adapter s (assoc-in s [:publications (:preview-id e)] e))))
(defn- mark-local! [adapter s entry value]
  (save! adapter s (-> s
                      (assoc :annual-fingerprint (:artifact-fingerprint entry))
                      (assoc-in [:baselines (get-in value [:snapshot :month])] (:baseline value)))))
(defn- fail-state! [adapter owner year id phase]
  (try (let [s (state adapter owner year) e (get-in s [:publications id])]
         (when e (phase! adapter s e phase {})))
       (catch Exception _ nil)))
(defn- publication-step! [adapter owner value]
  (let [id (:preview-id value) month (get-in value [:snapshot :month]) year (subs month 0 4)
        config ((:config-provider adapter))
        target (archive/target config owner year)
        filename (str (.getFileName target))
        s (state adapter (:owner-id owner) year)
        previous (get-in s [:publications id])]
    (cond
      (not= filename (:filename value)) (refused :owner-mapping-changed)
      (and previous (not= (:preview-digest previous) (:preview-digest value))) (refused :approval-conflict)
      ;; A completed receipt is an immutable historical acknowledgement. Replay
      ;; never restores its old bytes over a newer month or later correction.
      (= "pushed" (:state previous)) (public-status previous)
      (= "conflict" (:state previous)) (refused :publication-conflict)
      (and (nil? previous)
           (some #(and (not (contains? #{"pushed" "conflict"} (:state %)))
                       (or (:commit-id %) (= (:artifact-fingerprint %) (archive/fingerprint target))))
                 (vals (:publications s))))
      (policy/error :publication-pending [] "Another approved month in this annual workbook needs recovery first."
                    "Retry its approved publication before confirming another month.")
      :else
      (with-open [git (archive/open-repository config)]
        (let [destination (archive/destination git)
              mapped (credentials/resolve-owner (:owner-id owner))
              settings ((get-in adapter [:runtime :deps :settings]))
              fingerprint (archive/fingerprint target)]
          (cond
            (or (f/failed? mapped) (not= (:person owner) (:person mapped))) (refused :owner-mapping-changed)
            (f/failed? settings) settings
            (and previous (not= (select-keys previous [:remote :remote-url :ref :local-ref :repository-root])
                                (assoc (select-keys destination [:remote :remote-url :ref :local-ref]) :repository-root (str (archive/root config)))))
            (do (fail-state! adapter (:owner-id owner) year id "conflict") (refused :publication-conflict))
            (and previous (:commit-id previous))
            (if (and (= fingerprint (:artifact-fingerprint previous))
                     (= fingerprint (archive/committed-fingerprint git (:commit-id previous) filename))
                     (archive/ancestor? git (:commit-id previous)))
              (do
                ;; Establish durability/cache freshness again after an uncertain
                ;; write acknowledgement or a process restart.
                (archive/sync-directory! (archive/root config))
                (core/invalidate-runtime-caches! config)
                (archive/push-commit! git config previous)
                (*checkpoint* :after-push)
                (let [next (phase! adapter s previous "pushed" {})]
                  (public-status (get-in next [:publications id]))))
              (do (fail-state! adapter (:owner-id owner) year id "conflict") (refused :publication-conflict)))
            :else
            (let [already-written? (and previous (= fingerprint (:artifact-fingerprint previous)))
                  valid (if already-written? value
                            (preview/verify-preview (:ledger (:runtime adapter)) (:workbook (:runtime adapter)) owner settings value))]
              (cond
                (f/failed? valid) valid
                (and (not already-written?) (not (archive/target-clean? git filename))) (refused :dirty-workbook)
                (and previous (not already-written?) (not= fingerprint (:source-fingerprint previous)))
                (do (fail-state! adapter (:owner-id owner) year id "conflict") (refused :publication-conflict))
                :else
                (let [entry (or previous
                                (merge destination {:preview-id id :preview-digest (:preview-digest value) :owner-id (:owner-id owner)
                                                    :snapshot-digest (get-in value [:snapshot :digest])
                                                    :revision (get-in value [:snapshot :revision])
                                                    :policy-hash (get-in value [:snapshot :allocation :policy-hash])
                                                    :month month :filename filename :state "prepared"
                                                    :artifact-fingerprint (:artifact-fingerprint value)
                                                    :source-fingerprint (get-in value [:source-inspection :fingerprint])
                                                    :index-fingerprint (archive/index-fingerprint git filename)
                                                    :parent-head (archive/head git) :repository-root (str (archive/root config))
                                                    :message (str "Publish work " month " " (:person owner) "\n\nAgiladmin-Preview: " (:preview-digest value))
                                                    :approved-at (str (Instant/now))}))
                      prepared (if previous s
                                   (save! adapter s (-> s (assoc-in [:publications id] entry) (assoc-in [:latest month] id))))]
                  (*checkpoint* :after-prepared)
                  (let [recovered (when already-written? (archive/recover-commit git entry))]
                    (if (and (not recovered)
                             (or (not= (:parent-head entry) (archive/head git))
                                 (not (contains? #{(:index-fingerprint entry) (:artifact-fingerprint entry)}
                                                 (archive/index-fingerprint git filename)))))
                      (do (fail-state! adapter (:owner-id owner) year id "conflict") (refused :publication-conflict))
                      (do
                        (when-not already-written?
                          ;; Retained artifact is verified independently of expiry:
                          ;; expiry was checked above before a first file mutation.
                          (let [artifact (preview/artifact (:preview-store (:runtime adapter)) owner id)]
                            (when (f/failed? artifact) (throw (ex-info "Artifact unavailable" {})))
                            (let [live-settings ((get-in adapter [:runtime :deps :settings]))
                                  checked (if (f/failed? live-settings) live-settings
                                              (service/check-snapshot (:ledger (:runtime adapter)) owner live-settings (:snapshot value)))]
                              (when (or (f/failed? checked)
                                        (not= config ((:config-provider adapter)))
                                        (not= (:source-fingerprint entry) (archive/fingerprint target)))
                                (throw (ex-info "Approval evidence changed before replacement" {}))))
                            (try (archive/replace-workbook! target (:bytes artifact))
                                 (finally
                                   ;; rename may have succeeded before fsync threw.
                                   (when (= (:artifact-fingerprint entry) (archive/fingerprint target))
                                     (core/invalidate-runtime-caches! config))))))
                        (archive/sync-directory! (archive/root config))
                        (core/invalidate-runtime-caches! config)
                        (*checkpoint* :after-write)
                        (let [local (mark-local! adapter prepared entry value)
                              commit (or recovered
                                         (if (= (:artifact-fingerprint entry)
                                                (archive/committed-fingerprint git (archive/head git) filename))
                                           (archive/head git)
                                           (archive/commit-workbook! git filename (:message entry) owner)))]
                          (*checkpoint* :after-commit)
                          (let [committed (phase! adapter local entry "local-committed" {:commit-id commit})
                                e (get-in committed [:publications id])]
                            (*checkpoint* :after-local-state)
                            (archive/push-commit! git config e)
                            (*checkpoint* :after-push)
                            (let [pushed (phase! adapter committed e "pushed" {})]
                              (public-status (get-in pushed [:publications id])))))))))))))))))
(defrecord Publication [root locks runtime config-provider]
  ports/WorkPublication
  (publication-status [this owner month]
    (try
      (with-annual this owner (subs month 0 4)
        #(let [s (state this owner (subs month 0 4))] (public-status (get-in s [:publications (get-in s [:latest month])]))))
      (catch Exception _ (refused :publication-storage-failure))))
  (publish-confirmed! [this owner approval]
    (let [month (get-in approval [:snapshot :month]) year (subs month 0 4)]
      (ledger/with-owner-lock (:ledger runtime) (:owner-id owner)
        #(with-annual this (:owner-id owner) year
           (fn [] (budgets/with-repository-lock (get-in (config-provider) [:agiladmin :budgets :path])
                    (fn []
                      (try
                        (let [retained (preview/read-preview (:preview-store runtime) owner (:preview-id approval))]
                          (if (or (f/failed? retained) (not= retained approval)) (refused :approval-conflict)
                              (publication-step! this owner retained)))
                        (catch Exception _
                          (fail-state! this (:owner-id owner) year (:preview-id approval) "failed")
                          (try
                            (if-let [e (get-in (state this (:owner-id owner) year) [:publications (:preview-id approval)])]
                              (public-status e) (refused :publication-failure))
                            (catch Exception _ (refused :publication-storage-failure)))))))))))))
(defn adapter [runtime config-provider]
  (let [root (archive/safe! (.resolve ^Path (:root (:ledger runtime)) "publications"))]
    (Files/createDirectories root (make-array FileAttribute 0))
    (Files/setPosixFilePermissions root (PosixFilePermissions/fromString "rwx------"))
    (archive/sync-directory! root) (archive/sync-directory! (.getParent root))
    (->Publication root (atom {}) runtime config-provider)))
