;; Agiladmin - spreadsheet based time and budget administration

;; Copyright (C) 2016-2017 Dyne.org foundation

;; Sourcecode written and maintained by Denis Roio <jaromil@dyne.org>
;; designed in cooperation with Manuela Annibali <manuela@dyne.org>

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

(ns agiladmin.utils
  (:require [clojure.string :refer [split blank? lower-case upper-case]]
            [clojure.pprint :refer :all]
            [clojure.java.io :as io]
            [clojure.walk :refer :all]))

(defn strcasecmp
  "case insensitive comparison of two strings"
  [str1 str2]
  (some? (re-matches (java.util.regex.Pattern/compile
                      (str "(?i)" str1)) str2)))

(defn compress
  "Compress a collection removing empty elements"
  [coll]
  (postwalk #(if (coll? %) (into (empty %) (remove blank? %)) %) coll))

(defn dotname
  "Shorten up a name and surname tuple into initial and surname format"
  [inname]
  (let [toks (compress (split inname #" "))
        dot  (first (first toks))]
    (str dot " " (second toks))))

(defn namecmp
  "dotted comparison of two name strings, assuming only two names"
  [str1 str2]
  (strcasecmp (dotname str1) (dotname str2)))

(defn list-files-matching
  "returns a sequence of files found in a directory whose names match
  a regexp"
  [directory regex]
  (let [dir   (io/file directory)
        files (file-seq dir)]
    (remove nil?
            (map #(let [f (lower-case (.getName %))]
                    (if (re-find regex f) %)) files))))

(defn proj-name-from-path
  "get a project name from path"
  [path]
  (let [filename (last   (split path #"/"))
        projext  (second (split filename #"_"))
        projname (first  (split projext #"\."))]
    projname))
                         
