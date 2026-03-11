(ns agiladmin.pocketbase-process
  (:require [agiladmin.auth.pocketbase :as pocketbase]
            [agiladmin.version :as version]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [taoensso.timbre :as log])
  (:import (java.io File)
           (java.net URI)
           (java.util.concurrent TimeUnit)))

(defonce process* (atom nil))
(defonce shutdown-hook* (atom nil))

(defn- parse-http-address
  [base-url]
  (let [uri (URI. base-url)
        host (.getHost uri)
        port (let [port (.getPort uri)]
               (if (neg? port)
                 8090
                 port))]
    (str host ":" port)))

(defn- pocketbase-command
  [config]
  (cond-> [(or (:binary config) "pocketbase")
           "serve"
           "--http" (parse-http-address (:base-url config))
           "--dir" (:dir config)]
    (:migrations-dir config)
    (conj "--migrationsDir" (:migrations-dir config))))

(defn- ensure-dir!
  [path]
  (.mkdirs (io/file path)))

(defn- version-file
  [config]
  (or (:version-file config)
      (str (io/file (:dir config) ".agiladmin-version"))))

(defn- read-version
  [path]
  (let [file (io/file path)]
    (when (.exists file)
      (-> (slurp file)
          str/trim
          not-empty))))

(defn- write-version!
  [path value]
  (spit path (str value "\n")))

(defn- needs-bootstrap?
  [config]
  (not= version/current
        (read-version (version-file config))))

(defn running?
  []
  (when-let [process @process*]
    (.isAlive ^Process process)))

(defn- stop-process!
  [process]
  (.destroy ^Process process)
  (when-not (.waitFor ^Process process 5 TimeUnit/SECONDS)
    (.destroyForcibly ^Process process)
    (.waitFor ^Process process 5 TimeUnit/SECONDS)))

(defn stop!
  []
  (when-let [process @process*]
    (try
      (when (.isAlive ^Process process)
        (log/info "Stopping managed PocketBase process.")
        (stop-process! process))
      (finally
        (reset! process* nil)))))

(defn- install-shutdown-hook!
  []
  (when-not @shutdown-hook*
    (let [hook (Thread. ^Runnable stop! "agiladmin-pocketbase-shutdown")]
      (.addShutdownHook (Runtime/getRuntime) hook)
      (reset! shutdown-hook* hook))))

(defn wait-until-healthy!
  [config]
  (loop [attempt 0]
    (cond
      (pocketbase/healthy? config) true
      (>= attempt 49)
      (throw (ex-info "Managed PocketBase failed health check."
                      {:type ::health-timeout
                       :base-url (:base-url config)}))
      :else
      (do
        (Thread/sleep 200)
        (recur (inc attempt))))))

(defn bootstrap!
  [config]
  (when (needs-bootstrap? config)
    (log/info "Applying PocketBase bootstrap for Agiladmin version" version/current)
    (pocketbase/ensure-role-field! config)
    (write-version! (version-file config) version/current)))

(defn start!
  [config]
  (when-not (running?)
    (doseq [path [(:dir config)
                  (some-> (:version-file config) io/file .getParent)
                  (:migrations-dir config)]]
      (when path
        (ensure-dir! path)))
    (let [command (pocketbase-command config)]
      (log/info "Starting managed PocketBase process:" (str/join " " command))
      (-> (ProcessBuilder. ^java.util.List command)
          (.inheritIO)
          (.start)
          (->> (reset! process*))))
    (install-shutdown-hook!))
  (wait-until-healthy! config)
  (bootstrap! config)
  true)
