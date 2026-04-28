(ns agiladmin.e2e.generate-manager-fixture
  (:require [dk.ative.docjure.spreadsheet :as xls]
            [clojure.string :as str]))

(defn- fail! [msg]
  (binding [*out* *err*]
    (println msg))
  (System/exit 1))

(defn -main [& args]
  (let [[src dst owner] args
        owner-name (or owner "Manager")]
    (when (str/blank? src)
      (fail! "Missing src path"))
    (when (str/blank? dst)
      (fail! "Missing dst path"))
    (let [workbook (xls/load-workbook src)
          sheet (first (xls/sheet-seq workbook))]
      (when (nil? sheet)
        (fail! "Workbook has no sheets"))
      (xls/set-cell! (xls/select-cell "B3" sheet) owner-name)
      (xls/save-workbook! dst workbook))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
