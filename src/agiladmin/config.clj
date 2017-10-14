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
            [clojure.java.io :as io]
            [auxiliary.config :as aux]
            [auxiliary.core :refer :all]
            [taoensso.timbre :as log]
            [schema.core :as s]
            [cheshire.core :refer :all]))

(s/defschema Config
  {s/Keyword
   {:budgets {:git s/Str
              :ssh-key s/Str
              :path s/Str}
    :source  {:git s/Str
              :update s/Bool}
    :projects [s/Str]}
   :appname s/Str
   :paths [s/Str]
   :filename s/Str})

(s/defschema Project
  {s/Keyword
   {:start_date s/Str
    :duration   s/Num
    :rates {s/Keyword s/Num}
    :tasks [{:id s/Str
             :text s/Str
             :start_date s/Str
             :duration s/Num
             :pm s/Num}]}})

(def run-mode (atom :web))

(def default-settings {})
(def project-defaults {})

(defn- spy "Print out a config structure nicely formatted"
  [edn]
  (if (log/may-log? :debug)
    (binding [*out* *err*] (pprint edn)))
  edn)

(defn load-config [name default]
  (log/info (str "Loading configuration: " name))
  (->> (aux/config-read name default)
       (s/validate Config)))


(defn load-project [conf proj]
  (log/debug (str "Loading project: " proj))
  (if (contains? (-> conf (get-in [:agiladmin :projects]) set) proj)
    (->> (str "budgets/" proj ".yaml") aux/yaml-read spy
         (s/validate Project))
    (log/error (str "Project not found: " proj))))
