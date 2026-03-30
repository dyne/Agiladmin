(ns agiladmin.version
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.java.shell :as shell]
            [clojure.string :as str]))

(defn- resource-version
  []
  (when-let [resource (io/resource "agiladmin/version.edn")]
    (some-> resource
            slurp
            edn/read-string
            :version
            not-empty)))

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

(def current
  (or (resource-version)
      (git-version)
      "DEV-SNAPSHOT"))
