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
;;            [clojure.walk :refer :all]
;;            [clojure.java.io :as io]))
))

(defn strcasecmp
  "case insensitive comparison of two strings"
  [str1 str2]
  (some? (re-matches (java.util.regex.Pattern/compile
                      (str "(?i)" str1)) str2)))

(defn namecmp
  "compare two strings to be matching names (with or without
  punctuation of first name, case insensitive"
  [name1 name2]
  (if (strcasecmp name1 name2) true
      (let [firstdot (str (first (str name1)) ".")
            namedot  (str firstdot " " (second (split name1 #" ")))]
        ;;        [namedot name2])))
        (strcasecmp namedot name2))))
