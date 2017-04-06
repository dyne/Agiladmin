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
  (:require [clojure.java.io :as io]
            [cheshire.core :refer :all]))

(declare config-read)


(def default-settings
  {:git "https://github.com/dyne/tomb"
   :ssh-pub  "ssh.key.pub"
   :ssh-priv "ssh.key"
   :ssh-pass ""})

(defn config-read
  "read configurations from standard locations, overriding defaults or
  system-wide with user specific paths."
  ([] (config-read default-settings))
  ([default]
   (let [home (System/getenv "HOME")
         pwd  (System/getenv "PWD")]
     (loop [[p & paths] ["/etc/agiladmin/config.json" 
                         (str home "/.agiladmin/config.json")
                         "config.json"]
            res default ]
       (let [res (merge res
                        (if (.exists (io/as-file p))
                          (conj {:config p} (parse-stream (io/reader p) true))))]
         (if (empty? paths) (conj {:config false} res)
             (recur paths res)))))))

(defn config-write
  "write configurations to file"
  [conf file]
  (generate-stream conf (io/writer file)
                   {:pretty true}))

