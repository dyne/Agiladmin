;; Copyright (C) 2015-2017 Dyne.org foundation

;; Sourcecode designed, written and maintained by
;; Denis Roio <jaromil@dyne.org>

;; This program is free software: you can redistribute it and/or modify
;; it under the terms of the GNU Affero General Public License as published by
;; the Free Software Foundation, either version 3 of the License, or
;; (at your option) any later version.

;; This program is distributed in the hope that it will be useful,
;; but WITHOUT ANY WARRANTY; without even the implied warranty of
;; MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
;; GNU Affero General Public License for more details.

;; You should have received a copy of the GNU Affero General Public License
;; along with this program.  If not, see <http://www.gnu.org/licenses/>.

(ns agiladmin.config
  (:require [clojure.pprint :refer [pprint]]
            [clojure.string :as str :refer [upper-case]]
            [clojure.java.io :as io]
            [clojure.walk :refer [keywordize-keys]]
            [auxiliary.core :as aux]
            [taoensso.timbre :as log]
            [failjure.core :as f]
            [schema.core :as s]
            [yaml.core :as yaml]
            [cheshire.core :refer :all]))

(s/defschema Config
  {s/Keyword
   {:budgets {:git s/Str
              :ssh-key s/Str
              :path s/Str}
    (s/optional-key :projects) [s/Str]
    (s/optional-key :webserver) {(s/optional-key :port) s/Num
                                 (s/optional-key :host) s/Str
                                 (s/optional-key :anti-forgery) s/Bool
                                 (s/optional-key :ssl-redirect) s/Bool}
    (s/optional-key :source) {:git s/Str
                              :update s/Bool}
    (s/optional-key :pocketbase) {:base-url s/Str
                                  :users-collection s/Str
                                  :superuser-email s/Str
                                  :superuser-password s/Str
                                  (s/optional-key :manage-process) s/Bool
                                  (s/optional-key :binary) s/Str
                                  (s/optional-key :dir) s/Str
                                  (s/optional-key :migrations-dir) s/Str
                                  (s/optional-key :version-file) s/Str}
    }
   :appname s/Str
   :paths [s/Str]
   :filename s/Str
   })

(s/defschema ProjectTask
  {:id s/Str
   :text s/Str
   (s/optional-key :start_date) s/Str
   (s/optional-key :duration) s/Num
   :pm s/Num})

(s/defschema ProjectEntry
  {(s/optional-key :start_date) s/Str
   (s/optional-key :duration) s/Num
   (s/optional-key :type) s/Str
   (s/optional-key :cph) s/Num
   (s/optional-key :rates) s/Any
   (s/optional-key :tasks) [ProjectTask]})

(s/defschema Project
  {s/Keyword ProjectEntry})

(def run-mode (atom :web))

(def default-settings {:budgets
                       {:git "ssh://git@my.server.org/admin-budgets"
                        :ssh-key "id_rsa"
                        :path "budgets/"}
                       :webserver
                       {:anti-forgery false
                        :ssl-redirect false}})

(def project-defaults {})

(defn- project-entry-map?
  [value]
  (and (map? value)
       (some #(contains? value %)
             [:start_date :duration :type :cph :rates :tasks])))

(defn- validation-message
  [kind path ex]
  (let [detail (or (some-> ex ex-data :error pr-str)
                   (.getMessage ex))]
    (str "Invalid " kind " at " path ": " detail)))

(defn- validate-data
  [schema value kind path]
  (try
    (s/validate schema value)
    (catch Exception ex
      (f/fail (validation-message kind path ex)))))

(defn- project-entry
  [pconf proj path]
  (cond
    (nil? pconf)
    (f/fail (str "Project configuration file is missing, empty, or invalid YAML: " path))

    (not (map? pconf))
    (f/fail (str "Project configuration must be a map: " path))

    :else
    (let [proj-name (upper-case proj)
          entries pconf
          matching-key (first
                        (for [k (keys entries)
                              :when (= proj-name (-> k name upper-case))]
                          k))]
      (cond
        matching-key
        {:project-key (keyword proj-name)
         :entry (get entries matching-key)}

        (project-entry-map? entries)
        {:project-key (keyword proj-name)
         :entry entries}

        :else
        (f/fail
         (str "Project file " path
              " does not define project " proj-name
              ". Available keys: "
              (->> (keys entries) (map name) sort vec)))))))

(defn- normalize-project-entry
  [entry]
  (let [tasks (vec (for [t (:tasks entry)]
                     (into {} (for [[k v] t]
                                (if (= k :id)
                                  [:id (upper-case v)]
                                  [k v])))))]
    (conj entry
          {:tasks tasks
           :idx (#(zipmap
                   (map (fn [id]
                          (-> (get id :id)
                              upper-case keyword)) %)
                   %)
                 (:tasks entry))})))


(defn yaml-read [path]
  (if (.exists (io/as-file path))
    (-> path yaml/from-file keywordize-keys)))

(defn- yaml-read-safe
  [path]
  (try
    (yaml-read path)
    (catch Exception ex
      (f/fail (str "Invalid YAML at " path ": " (.getMessage ex))))))

(defn- config-file-path?
  [name]
  (and (string? name)
       (or (.endsWith name ".yaml")
           (.endsWith name ".yml"))))

(defn- explicit-config-read
  [path defaults]
  (if (.exists (io/as-file path))
    (let [yaml-data (yaml-read-safe path)]
      (if (f/failed? yaml-data)
        yaml-data
        (let [appname (or (:appname yaml-data)
                          (-> path io/file .getName (str/replace #"\.ya?ml$" "")))
              app-key (keyword appname)]
          (merge {:appname appname
                  :filename (-> path io/file .getName)
                  :paths [path]}
                 (dissoc yaml-data app-key)
                 {app-key (merge defaults
                                 (get yaml-data app-key))}))))
    (f/fail (str "Configuration file not found: " path))))

(defn- config-read
  "Read configurations from standard locations, overriding defaults or
  system-wide with user specific paths. Requires the application name
  and optionally default values."
  ([appname] (config-read appname {}))
  ([appname defaults & flags]
   (if (config-file-path? appname)
     (explicit-config-read appname defaults)
     (let [home (System/getenv "HOME")
           pwd  (System/getenv "PWD" )
           file (str appname ".yaml")
           paths [(str      "/etc/" appname "/" file)
                  (str home "/."    appname "/" file)
                  (str pwd  "/"     file)
                  ;; TODO: this should be resources
                  (str pwd "/resources/"  file)
                  (str pwd "/test-resources/" file)]]
       (loop [[p & remaining] paths
              res defaults]
         (if p
           (if (.exists (io/as-file p))
             (let [yaml-data (yaml-read-safe p)]
               (if (f/failed? yaml-data)
                 yaml-data
                 (recur remaining (merge res yaml-data))))
             (recur remaining res))
           {:appname appname
            :filename file
            :paths paths
            (keyword appname) res}))))))

(defn- spy "Print out a config structure nicely formatted"
  [edn]
  (if (log/may-log? :debug)
    (binding [*out* *err*] (pprint edn)))
  edn)

(defn q [conf path] ;; query a variable inside the config
  {:pre [(coll? path)]} 
  ;; (try ;; adds an extra check every time configuration is read
  ;;   (s/validate Config conf)
  ;;   (catch Exception ex
  ;;     (f/fail (log/spy :error ["Invalid configuration: " conf ex]))))
  (get-in conf path))

(defn- project-file?
  [conf file]
  (let [name (.getName file)
        config-filename (:filename conf)]
    (and (.isFile file)
         (not (.startsWith name "."))
         (or (.endsWith name ".yaml")
             (.endsWith name ".yml"))
         (not= name config-filename))))

(defn project-files
  [conf]
  (let [budgets-path (get-in conf [:agiladmin :budgets :path])
        dir (io/file budgets-path)]
    (if (.exists dir)
      (->> (.listFiles dir)
           (filter #(project-file? conf %))
           (reduce (fn [acc file]
                     (let [filename (.getName file)
                           project-name (-> filename
                                            (str/split #"\." 2)
                                            first
                                            upper-case)]
                       (if (contains? acc project-name)
                         acc
                         (assoc acc project-name (str (io/file budgets-path filename))))))
                   {})
           (into (sorted-map)))
      {})))

(defn project-names
  [conf]
  (if-let [projects (seq (get-in conf [:agiladmin :projects]))]
    (vec projects)
    (-> conf project-files keys vec)))

(defn load-config [name default]
  (log/info (str "Loading configuration: " name))
  (let [conf (config-read name default)
        loaded-paths (->> (:paths conf)
                          (filter #(.exists (io/as-file %)))
                          vec)
        path-label (if (seq loaded-paths)
                     (clojure.string/join ", " loaded-paths)
                     (str "search path for " name ".yaml"))]
    (if (f/failed? conf)
      conf
      (f/attempt-all
       [_ (validate-data Config conf "configuration" path-label)]
       conf
       (f/when-failed [e]
         e)))))



(defn load-project [conf proj]
  (log/debug (str "Loading project: " proj))
  (if-let [path (get (project-files conf) (upper-case proj))]
    (let [pconf (yaml-read-safe path)]
      (f/attempt-all
       [_ (if (f/failed? pconf) pconf true)
        entry-data (project-entry pconf proj path)
        _validated (validate-data ProjectEntry
                                  (:entry entry-data)
                                  "project configuration"
                                  path)]
       {(:project-key entry-data)
        (normalize-project-entry (:entry entry-data))}
       (f/when-failed [e]
         e)))
    (f/fail (str "Project not found in budgets path: " proj))))
