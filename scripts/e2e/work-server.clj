(ns agiladmin.e2e.work-server
  "Isolated browser harness only. Production keeps rejecting development auth.")
(require '[agiladmin.ring :as ring]
         '[agiladmin.handlers :as handlers]
         '[agiladmin.auth.core :as auth]
         '[agiladmin.auth.dev :as dev]
         '[agiladmin.mcp.credentials :as credentials]
         '[agiladmin.work-service :as service]
         '[agiladmin.work-preview :as preview]
         '[agiladmin.core :as core]
         '[cheshire.core :as json]
         '[clojure.java.io :as io]
         '[ring.adapter.jetty :as jetty]
         '[failjure.core :as f])
(defn checked [value]
  (when (f/failed? value) (throw (ex-info (f/message value) {}))) value)
(defn seed! [runtime state-path]
  (let [owner (checked (credentials/resolve-owner "dev-admin"))
        settings ((get-in runtime [:deps :settings]))
        projects (core/load-all-projects @ring/config)
        save (fn [month id entries rev]
               (checked (service/upsert! (:ledger runtime) owner projects settings
                                        {:month month :request_id id :expected_revision rev :entries entries})))
        entry (fn [month id project minutes]
                {:external_id id :date (str month "-12") :project_id project :minutes minutes
                 :note "=literal <script>plain text</script> 😀\nComplete client note & context"})
        create (fn [month] (checked (preview/create! (:preview-store runtime) (:ledger runtime)
                                                   (:workbook runtime) owner settings month)))
        _ (save "2024-02" "seed-review" [(entry "2024-02" "design" "UNO" 360)
                                        (entry "2024-02" "delivery" "DUE" 240)] 0)
        review (create "2024-02")
        _ (save "2024-03" "seed-stale" [(entry "2024-03" "stale" "UNO" 60)] 0)
        stale (create "2024-03")
        _ (save "2024-03" "change-stale" [(entry "2024-03" "stale" "UNO" 120)] 1)
        ;; Four assignments, each split into Paid/VOL, produce eight columns.
        assignments [["UNO" "alpha"] ["UNO" "beta"] ["DUE" "alpha"] ["DUE" "beta"]]]
    (save "2024-04" "seed-overflow"
          (mapv (fn [i [project task]] (assoc (entry "2024-04" (str "overflow-" i) project 150)
                                            :task_id task)) (range 4) assignments) 0)
    (let [state (json/parse-string (slurp state-path) true)]
      (spit state-path (json/generate-string (assoc state :work {:review-id (:preview-id review)
                                                               :stale-id (:preview-id stale)
                                                               :overflow-month "2024-04"}) {:pretty true})))))
(defn -main [state-path]
  (ring/init)
  ;; Explicit test seam after init: same browser credentials and verified accounts,
  ;; but a production-shaped active-account catalog. Never used by app main.
  (auth/init! (assoc (dev/backend) :development? false
                    :active-accounts (constantly [dev/default-user dev/manager-user dev/guest-user])))
  (handlers/init-app!)
  (seed! @handlers/mcp-state state-path)
  (jetty/run-jetty handlers/app {:host "127.0.0.1" :port 18080 :join? true}))
