(ns agiladmin.budgets-mutation
  "One process-wide repository mutation boundary, shared by publication, legacy
  archive and reload. Callers must take owner/annual locks before this lock."
  (:import [java.io File]))
(defonce ^:private locks (atom {}))
(defn with-repository-lock [path operation]
  (let [key (if (seq path) (.getCanonicalPath (File. ^String path)) "unconfigured-budgets")
        monitor (get (swap! locks #(if (contains? % key) % (assoc % key (Object.)))) key)]
    (locking monitor (operation))))
