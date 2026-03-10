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
            [clojure.string :refer [upper-case]]
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
    :projects [s/Str]
    (s/optional-key :webserver) {:port s/Num
                                 :host s/Str
                                 :anti-forgery s/Bool
                                 :ssl-redirect s/Bool}
    (s/optional-key :source) {:git s/Str
                              :update s/Bool}
    (s/optional-key :pocketbase) {:base-url s/Str
                                  :users-collection s/Str
                                  :superuser-email s/Str
                                  :superuser-password s/Str}
    }
   :appname s/Str
   :paths [s/Str]
   :filename s/Str
   })

(s/defschema ProjectTask
  {:id s/Str
   :text s/Str
   :start_date s/Str
   :duration s/Num
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

(def project-schemas
  {1 ProjectEntry})

(def reserved-project-keys
  #{:schema-version})

(def run-mode (atom :web))

(def default-settings {:budgets
                       {:git "ssh://git@my.server.org/admin-budgets"
                        :ssh-key "id_rsa"
                        :path "budgets/"}
                       :projects [ "TEST" "ADMIN" "CODE" ]
                       :webserver
                       {:anti-forgery false
                        :ssl-redirect false}})

(def project-defaults {})

(defn- project-entry-map?
  [value]
  (and (map? value)
       (some #(contains? value %)
             [:start_date :duration :type :cph :rates :tasks])))

(defn- project-schema
  [version]
  (if-let [schema (get project-schemas version)]
    schema
    (f/fail (str "Unsupported project schema version: " version))))

(defn- project-map
  [pconf]
  (apply dissoc pconf reserved-project-keys))

(defn- project-entry
  [pconf proj path]
  (cond
    (nil? pconf)
    (f/fail (str "Project configuration file is missing, empty, or invalid YAML: " path))

    (not (map? pconf))
    (f/fail (str "Project configuration must be a map: " path))

    :else
    (let [proj-name (upper-case proj)
          entries (project-map pconf)
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


(defn- config-read
  "Read configurations from standard locations, overriding defaults or
  system-wide with user specific paths. Requires the application name
  and optionally default values."
  ([appname] (config-read appname {}))
  ([appname defaults & flags]
   (let [home (System/getenv "HOME")
         pwd  (System/getenv "PWD" )
         file (str appname ".yaml")
         conf (-> {:appname appname
                   :filename file
                   :paths [(str      "/etc/" appname "/" file)
                           (str home "/."    appname "/" file)
                           (str pwd  "/"     file)
                           ;; TODO: this should be resources
                           (str pwd "/resources/"  file)
                           (str pwd "/test-resources/" file)]}
                  (conj defaults))]
     (loop [[p & paths] (:paths conf)
            res defaults]
       (let [res (merge res
                        (if (.exists (io/as-file p))
                          (conj res (yaml-read p))))]
         (if (empty? paths) (conj conf {(keyword appname) res})
             (recur paths res)))))))

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

(defn load-config [name default]
  (log/info (str "Loading configuration: " name))
  (->> (config-read name default)))



(defn load-project [conf proj]
  (log/debug (str "Loading project: " proj))
  (if (contains? (-> conf (get-in [:agiladmin :projects]) set) proj)
    (let [path  (str (get-in conf [:agiladmin :budgets :path]) proj ".yaml")
          pconf (yaml-read path)]
      (f/attempt-all
       [schema-version (or (:schema-version pconf) 1)
        schema (project-schema schema-version)
        entry-data (project-entry pconf proj path)
        _validated (try
                     (s/validate schema (:entry entry-data))
                     (catch Exception ex
                       (f/fail
                        (log/spy :error
                                 ["Invalid project configuration: " proj path ex]))))]
       {(:project-key entry-data)
        (normalize-project-entry (:entry entry-data))}
       (f/when-failed [e]
         e)))
    (log/error (str "Project not found: " proj))))
