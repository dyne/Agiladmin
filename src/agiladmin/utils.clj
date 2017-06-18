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
  (:require [clojure.string :refer [split]]
            [clojure.pprint :refer :all]
            [clojure.walk :refer :all]
;;            [clojure.java.io :as io]))
))

(defn strcasecmp
  "case insensitive comparison of two strings"
  [str1 str2]
  (some? (re-matches (java.util.regex.Pattern/compile
                      (str "(?i)" str1)) str2)))

(defn compress
  "Compress a collection removing empty elements"
  [coll]
  (postwalk #(if (coll? %) (into (empty %) (remove empty? %)) %) coll))

(defn namecmp
  "dotted comparison of two name strings, assuming only two names"
  [str1 str2]
  (let [toks1 (compress (split str1 #" "))
        toks2 (compress (split str2 #" "))
        dot1  (first (first toks1))
        dot2  (first (first toks2))
        min1  (str dot1 " " (second toks1))
        min2  (str dot2 " " (second toks2))]
    (strcasecmp min1 min2)))
