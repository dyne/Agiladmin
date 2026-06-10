(ns agiladmin.e2e.generate-manager-fixture
  (:require [dk.ative.docjure.spreadsheet :as xls]
            [clojure.string :as str]))

(defn- fail! [msg]
  (binding [*out* *err*]
    (println msg))
  (System/exit 1))

(defn -main [& args]
  (let [[src dst owner blank-project-col] args
        owner-name (or owner "Manager")]
    (when (str/blank? src)
      (fail! "Missing src path"))
    (when (str/blank? dst)
      (fail! "Missing dst path"))
    (let [workbook (xls/load-workbook src)
          sheets (xls/sheet-seq workbook)
          sheet (first sheets)]
      (when (nil? sheet)
        (fail! "Workbook has no sheets"))
      (doseq [current-sheet sheets]
        (xls/set-cell! (xls/select-cell "B3" current-sheet) owner-name)
        (when-not (str/blank? blank-project-col)
          (xls/set-cell! (xls/select-cell (str (str/upper-case blank-project-col) "7") current-sheet) "")))
      (xls/save-workbook! dst workbook))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
