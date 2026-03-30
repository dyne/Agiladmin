(ns build
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.tools.build.api :as b]))

(defn- git-present?
  []
  (.exists (io/file ".git")))

(defn- git-version
  []
  (when (git-present?)
    (let [{:keys [exit out]} (shell/sh "git" "describe" "--tags" "--match" "v[0-9]*.[0-9]*.[0-9]*" "--abbrev=0")]
      (when (zero? exit)
        (some-> out
                str/trim
                (str/replace #"^v" "")
                not-empty)))))

(defn- env-version
  []
  (some-> (System/getenv "AGILADMIN_VERSION")
          str/trim
          not-empty))

(def lib 'agiladmin/agiladmin)
(def version
  (or (env-version)
      (git-version)
      "DEV-SNAPSHOT"))
(def class-dir "target/classes")
(def basis (delay (b/create-basis {:project "deps.edn"})))
(def uber-file (format "target/%s-standalone.jar" version))

(defn- write-version-resource!
  []
  (let [target (io/file class-dir "agiladmin" "version.edn")]
    (.mkdirs (.getParentFile target))
    (spit target (pr-str {:version version}))))

(defn clean [_]
  (b/delete {:path "target"})
  nil)

(defn uber [_]
  (clean nil)
  (b/copy-dir {:src-dirs ["src" "resources"]
               :target-dir class-dir})
  (write-version-resource!)
  (b/compile-clj {:basis @basis
                  :src-dirs ["src"]
                  :class-dir class-dir
                  :ns-compile ['agiladmin.main]})
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis @basis
           :main 'agiladmin.main})
  (println uber-file))
