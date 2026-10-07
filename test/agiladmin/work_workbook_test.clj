(ns agiladmin.work-workbook-test
  (:require [midje.sweet :refer :all]
            [agiladmin.work-workbook :as workbook]
            [agiladmin.work-policy :as policy]
            [agiladmin.work-ports :as ports]
            [agiladmin.core :as core]
            [agiladmin.tabular :as tab]
            [clojure.java.io :as io]
            [failjure.core :as f])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream]
           [java.time YearMonth]
           [org.apache.poi.xssf.usermodel XSSFWorkbook]
           [org.apache.poi.ss.usermodel CellType]))

(def owner {:owner-id "alice" :person "Alice Example" :person-alias "A.Example"})
(defn record [id date project minutes & {:keys [task note voluntary]}]
  {:owner-id "alice" :external_id id :date date :project_id project
   :task_id task :minutes minutes :note (or note "") :voluntary (boolean voluntary)})
(defn snapshot [month records]
  (let [allocation (policy/allocate-records policy/defaults records)]
    {:owner-id "alice" :month month :records records :allocation allocation
     :capacity (policy/month-capacity allocation "alice" month) :digest "test-snapshot"}))
(defn open-artifact [artifact] (XSSFWorkbook. (ByteArrayInputStream. (:bytes artifact))))
(defn value [sheet row col]
  (let [c (workbook/cell sheet row col)]
    (cond
      (= CellType/STRING (.getCellTypeEnum c)) (.getStringCellValue c)
      (contains? #{CellType/NUMERIC CellType/FORMULA} (.getCellTypeEnum c)) (.getNumericCellValue c))))
(defn workbook-bytes [wb]
  (with-open [out (ByteArrayOutputStream.)]
    (.write wb out) (.toByteArray out)))

(facts "Calendar maps every day and totals include the last day"
 (doseq [[month length] [["2023-02" 28] ["2024-02" 29] ["2024-04" 30] ["2024-01" 31]
                       ["2000-02" 29] ["2100-02" 28]]]
  (let [last-date (str (.atEndOfMonth (YearMonth/parse month)))
        artifact (workbook/render-workbook owner (snapshot month [(record "end" last-date "P" 61)]))]
    (with-open [wb (open-artifact artifact)]
      (let [sheet (.getSheet wb (workbook/sheet-name month))
            total (workbook/total-row month)]
          (.getNumberOfSheets wb) => 12
          (value sheet 2 2) => (workbook/sheet-name month)
          (value sheet 3 2) => "Alice Example"
          (mapv #(str (.toLocalDate (java.sql.Date. (.getTime (.getDateCellValue (workbook/cell sheet (+ 10 %) 1))))))
                (range 1 (inc length)))
          => (mapv #(str (.atDay (YearMonth/parse month) %)) (range 1 (inc length)))
          (value sheet (+ 10 length) 2) => (roughly (/ 61.0 60) 1.0e-9)
          (value sheet total 2) => (roughly (/ 61.0 60) 1.0e-9)
          (value sheet 4 2) => (roughly (/ 61.0 60) 1.0e-9)
          (value sheet 5 2) => 1.0
          (value sheet 43 1) => "Signature:"
          (re-find #"I\$?47" (.getPrintArea wb (.getSheetIndex wb sheet))) => truthy
          (value (.getSheet wb (if (= month "2024-01") "2024-2" (str (.getYear (YearMonth/parse month)) "-1"))) 4 2)
          => 0.0)))))

(facts "Empty annual generator has no sample identity or hours"
  (let [artifact (workbook/render-workbook owner (snapshot "2024-02" []))]
    (:filename artifact) => "2024_timesheet_Alice-Example.xlsx"
    (get-in artifact [:changes :annual-workbook-created]) => true
    (get-in artifact [:changes :target-sheet-created]) => true
    (get-in artifact [:changes :preserves-other-months]) => false
    (with-open [wb (open-artifact artifact)]
      (doseq [m (range 1 13)]
        (let [sheet (.getSheet wb (str "2024-" m))]
          (value sheet 3 2) => "Alice Example"
          (value sheet 4 2) => 0.0
          (value sheet 5 2) => 0.0
          (mapv #(value sheet 7 %) (range 2 9)) => ["" "" "" "" "" "" ""]))))
  (workbook/render-workbook (assoc owner :owner-id "other") (snapshot "2024-01" []))
  => (contains {:code :owner-mismatch}))

(facts "Paid/VOL and task splits use stable separate monthly columns"
  (let [records [(record "b" "2024-01-01" "B" 240 :task "T2")
                 (record "a" "2024-01-01" "A" 360 :task "T1")
                 (record "c" "2024-01-02" "A" 60 :task "T2" :voluntary true)]
        artifact (workbook/render-workbook owner (snapshot "2024-01" records))]
    (with-open [wb (open-artifact artifact)]
      (let [sheet (.getSheet wb "2024-1")]
        (mapv #(mapv (fn [r] (value sheet r %)) [7 8 9]) (range 2 7))
        => [["A" "T1" ""] ["A" "T1" "VOL"] ["A" "T2" "VOL"] ["B" "T2" ""] ["B" "T2" "VOL"]]
        (mapv #(value sheet 11 %) (range 2 7)) => [4.8 1.2 0.0 3.2 0.8]
        (value sheet 12 4) => 1.0
        (value sheet 4 2) => 11.0))))

(facts "Capacity is counted across the entire month before artifact creation"
  (let [rs (mapv #(record (str %) (format "2024-01-%02d" %) (str "P" %) 60) (range 1 9))]
    (:required-columns (:capacity (workbook/render-workbook owner (snapshot "2024-01" (pop rs))))) => 7
    (with-open [wb (open-artifact (workbook/render-workbook owner (snapshot "2024-01" (pop rs))))]
      (value (.getSheet wb "2024-1") 17 8) => 1.0
      (value (.getSheet wb "2024-1") 42 8) => 1.0)
    (workbook/render-workbook owner (snapshot "2024-01" rs) (byte-array [1 2 3]))
    => (contains {:code :column-overflow :capacity (contains {:required-columns 8})})))

(facts "Distinct full notes are deterministic literal strings, wrapped for printing"
  (let [notes ["=SUM(B1:B2)" "+cmd" "-formula" "@name" "é λ 😀\nsecond line"]
        rs (mapv #(record (str %1) "2024-02-29" "A" 1 :task "T" :note %2) (range) notes)
        rs (conj rs (assoc (first rs) :external_id "duplicate"))
        artifact (workbook/render-workbook owner (snapshot "2024-02" (reverse rs)))]
    (with-open [wb (open-artifact artifact)]
      (let [sheet (.getSheet wb "2024-2") c (workbook/cell sheet 39 9)]
        (.getCellTypeEnum c) => CellType/STRING
        (.getStringCellValue c) => (clojure.string/join "\n" (sort (map #(str "[A/T] " %) notes)))
        (.getWrapText (.getCellStyle c)) => true
        (> (.getHeightInPoints (.getRow sheet 38)) 15) => true)))
  (let [rs (mapv #(record (str %) "2024-01-01" "A" 1 :note (str % (apply str (repeat 235 "x")))) (range 140))]
    (workbook/render-workbook owner (snapshot "2024-01" rs) (byte-array [1]))
    => (contains {:code :notes-overflow})))

(facts "Rendering a copy preserves unrelated months, cells, styles and signature fields"
  (let [empty-artifact (workbook/render-workbook owner (snapshot "2024-01" []))]
    (with-open [original (open-artifact empty-artifact)]
      (let [sheet (.getSheet original "2024-1")
            other (.getSheet original "2024-2")
            custom (.createCellStyle original)]
        (.setDataFormat custom (short 4))
        (.setCellValue (workbook/cell sheet 43 10) "Owner signature")
        (.setCellValue (workbook/cell sheet 50 12) "Unrelated note")
        (.setCellStyle (workbook/cell sheet 11 2) custom)
        (.setCellFormula (workbook/cell sheet 50 13) "1+2")
        (.setCellValue (workbook/cell other 11 2) 7.0)
        (.setPrintArea original (.getSheetIndex original sheet) "A1:M60")
        (.createSheet original "Reference")
        (let [source (workbook-bytes original)
              artifact (workbook/render-workbook owner (snapshot "2024-01" [(record "1" "2024-01-01" "A" 60)]) source)]
          (get-in artifact [:changes :annual-workbook-created]) => false
          (get-in artifact [:changes :target-sheet-created]) => false
          (get-in artifact [:changes :preserves-other-months]) => true
          (with-open [rendered (open-artifact artifact)]
            (let [target (.getSheet rendered "2024-1")]
              (value target 43 10) => "Owner signature"
              (value target 50 12) => "Unrelated note"
              (.getCellFormula (workbook/cell target 50 13)) => "1+2"
              (.getDataFormat (.getCellStyle (workbook/cell target 11 2))) => (short 4)
              (.getCellFormula (workbook/cell target 42 2)) => "SUM(B11:B41)"
              (.getPrintArea rendered (.getSheetIndex rendered target)) => "'2024-1'!$A$1:$M$60"
              (value (.getSheet rendered "2024-2") 11 2) => 7.0
              (.getNumberOfSheets rendered) => 13))
          (with-open [unchanged (XSSFWorkbook. (ByteArrayInputStream. source))]
            (value (.getSheet unchanged "2024-1") 11 2) => 0.0))))))

(defn change-workbook [artifact change!]
  (with-open [wb (open-artifact artifact)] (change! wb) (workbook-bytes wb)))

(facts "Round-trip cached reports reconcile every month length, split and costs"
  (doseq [month ["2023-02" "2024-02" "2024-04" "2024-01"]]
    (let [last-date (str (.atEndOfMonth (YearMonth/parse month)))
          rs [(record "a" last-date "A" 360 :task "T")
              (record "b" last-date "B" 240)
              (record "v" (str (.atDay (YearMonth/parse month) 1)) "A" 1 :task "T" :voluntary true)]
          artifact (workbook/render-workbook owner (snapshot month rs))
          path (.toFile (java.nio.file.Files/createTempFile "agiladmin-roundtrip-" ".xlsx"
                                                         (make-array java.nio.file.attribute.FileAttribute 0)))]
      (try
        (with-open [out (io/output-stream path)] (.write out ^bytes (:bytes artifact)))
        (let [ts (core/load-timesheet (.getPath path))
              hours (core/load-monthly-hours ts (workbook/sheet-name month) (constantly true))
              costs (:rows (core/derive-costs (tab/dataset hours) {}
                                             {:A {:rates {:A.Example 40}} :B {:rates {:A.Example 30}}}))]
          (:year ts) => (subs month 0 4)
          (:name ts) => "A.Example"
          (reduce + (map :hours hours)) => (roughly (/ 601.0 60) workbook/hours-tolerance)
          (mapv :cost (filter #(= "VOL" (:tag %)) costs)) => [0 0]
          (mapv :cost (filter #(= "" (:tag %)) costs)) => [192.0 96.0]
          (:recorded-minutes (:report artifact)) => 601
          (.close (:xls ts)))
        (finally (.delete path))))))

(facts "Populated legacy sheets and stale managed workbooks cannot be overwritten"
  (let [draft (snapshot "2024-01" [(record "1" "2024-01-01" "A" 60)])
        published (workbook/render-workbook owner draft)
        inspection (workbook/inspect-workbook owner "2024-01" (:bytes published))
        changed (snapshot "2024-01" [(record "1" "2024-01-01" "A" 120)])]
    (workbook/render-workbook owner changed (:bytes published)) => (contains {:code :existing-month-unmanaged})
    (:empty? inspection) => false
    (f/failed? (workbook/render-workbook owner changed (:bytes published) (:baseline published))) => false
    (:recorded-minutes (:report (workbook/render-workbook owner changed (:bytes published) (:baseline published)))) => 120
    (workbook/render-workbook owner changed (:bytes published) (assoc (:baseline published) :month-baseline "altered"))
    => (contains {:code :month-conflict :next-action string?})
    (workbook/render-workbook owner changed (:bytes published) (assoc (:baseline published) :owner-id "bob"))
    => (contains {:code :invalid-workbook-baseline})
    (workbook/render-workbook owner changed nil (:baseline published)) => (contains {:code :workbook-conflict})
    (doseq [edit [(fn [wb] (.setCellValue (workbook/cell (.getSheet wb "2024-1") 11 2) 2.0))
                  (fn [wb] (.setCellValue (workbook/cell (.getSheet wb "2024-1") 11 9) "manual note"))
                  (fn [wb] (.setCellValue (workbook/cell (.getSheet wb "2024-2") 11 2) 3.0))
                  (fn [wb] (.setCellValue (workbook/cell (.getSheet wb "2024-1") 50 12) "external"))]]
      (workbook/render-workbook owner changed (change-workbook published edit) (:baseline published))
      => (contains {:code :workbook-conflict :next-action string?}))))

(facts "Empty months adopt while preserving other months; headers and formula reports are checked"
  (let [empty (workbook/render-workbook owner (snapshot "2024-01" []))
        draft (snapshot "2024-02" [(record "1" "2024-02-29" "A" 1)])
        other-month (change-workbook empty (fn [wb] (.setCellValue (workbook/cell (.getSheet wb "2024-1") 11 2) 5.0)))
        adopted (workbook/render-workbook owner draft other-month)]
    (:empty? (workbook/inspect-workbook owner "2024-02" other-month)) => true
    (f/failed? adopted) => false
    (with-open [wb (open-artifact adopted)]
      (value (.getSheet wb "2024-1") 11 2) => 5.0
      (value (.getSheet wb "2024-2") 39 2) => (roughly (/ 1.0 60) workbook/hours-tolerance))
    (doseq [r [4 11 40]]
      (workbook/render-workbook owner draft
                               (change-workbook empty (fn [wb]
                                                        (let [c (workbook/cell (.getSheet wb "2024-2") r 2)]
                                                          (.setCellType c CellType/NUMERIC)
                                                          (.setCellValue c 1.0)))))
      => (contains {:code :existing-month-unmanaged}))
    (workbook/render-workbook owner draft
                             (change-workbook empty (fn [wb] (.setCellFormula (workbook/cell (.getSheet wb "2024-2") 11 2) "1+2"))))
    => (contains {:code :existing-month-unmanaged})
    (workbook/render-workbook owner draft
                             (change-workbook empty (fn [wb] (.setCellValue (workbook/cell (.getSheet wb "2024-2") 11 9) "legacy note"))))
    => (contains {:code :existing-month-unmanaged})
    (workbook/render-workbook owner draft
                             (change-workbook empty (fn [wb] (.setCellValue (workbook/cell (.getSheet wb "2024-2") 3 2) "Bob Example"))))
    => (contains {:code :workbook-owner-mismatch})
    (workbook/render-workbook owner (snapshot "2025-02" []) (:bytes empty))
    => (contains {:code :workbook-year-mismatch})
    (workbook/render-workbook owner draft
                             (change-workbook empty (fn [wb] (.setCellValue (workbook/cell (.getSheet wb "2024-2") 2 2) "2024-3"))))
    => (contains {:code :workbook-year-mismatch})
    (workbook/render-workbook owner draft
                             (change-workbook empty (fn [wb] (.setCellFormula (workbook/cell (.getSheet wb "2024-2") 40 2) "0"))))
    => (contains {:code :workbook-reconciliation-failed})
    (workbook/render-workbook owner draft
                             (change-workbook empty (fn [wb] (.setCellFormula (workbook/cell (.getSheet wb "2024-2") 39 1) "DATE(2024,2,28)"))))
    => (contains {:code :workbook-reconciliation-failed})
    (workbook/render-workbook owner (assoc draft :records [])) => (contains {:code :invalid-snapshot})
    (workbook/render-workbook owner (assoc-in draft [:records 0 :project_id] "OTHER"))
    => (contains {:code :invalid-snapshot})))

(facts "Minimal empty month gets required date mapping and missing formulas"
  (with-open [wb (XSSFWorkbook.)]
    (let [sheet (.createSheet wb "2024-2")]
      (.setCellValue (workbook/cell sheet 2 2) "2024-2")
      (.setCellValue (workbook/cell sheet 3 2) "Alice Example")
      (let [artifact (workbook/render-workbook owner (snapshot "2024-02" [(record "1" "2024-02-29" "A" 60)]) (workbook-bytes wb))]
        (f/failed? artifact) => false
        (with-open [rendered (open-artifact artifact)]
          (value (.getSheet rendered "2024-2") 4 2) => 1.0
          (.getDataFormatString (.getCellStyle (workbook/cell (.getSheet rendered "2024-2") 39 1))) => "ddd d"
          (.getDateCellValue (workbook/cell (.getSheet rendered "2024-2") 39 1)) => (java.sql.Date/valueOf "2024-02-29"))))))

(facts "The WorkWorkbook port resolves server identity and trusted baseline without file writes"
  (let [draft (snapshot "2024-01" [(record "1" "2024-01-01" "A" 60)])
        published (workbook/render-workbook owner draft)
        path (.toFile (java.nio.file.Files/createTempFile "agiladmin-source-" ".xlsx"
                                                       (make-array java.nio.file.attribute.FileAttribute 0)))
        baseline (atom nil)
        reads (atom [])]
    (try
      (with-open [out (io/output-stream path)] (.write out ^bytes (:bytes published)))
      (let [before (java.nio.file.Files/readAllBytes (.toPath path))
            adapter (workbook/workbook-adapter
                     {:resolve-owner (fn [id] (get {"alice" owner} id))
                      :read-workbook (fn [mapped year] (swap! reads conj [mapped year])
                                       (java.nio.file.Files/readAllBytes (.toPath path)))
                      :read-baseline (fn [_ _] @baseline)})]
        (ports/render-preview adapter draft) => (contains {:code :existing-month-unmanaged})
        (ports/inspect-month adapter (assoc owner :person "Bob Example") "2024-01")
        => (contains {:code :existing-month-unmanaged})
        (reset! baseline (:baseline published))
        (:managed? (ports/inspect-month adapter (assoc owner :person "Bob Example") "2024-01")) => true
        (f/failed? (ports/render-preview adapter draft)) => false
        (every? #(= [owner "2024"] %) @reads) => true
        (ports/render-preview adapter (assoc draft :owner-id "bob")) => (contains {:code :owner-required})
        (seq before) => (seq (java.nio.file.Files/readAllBytes (.toPath path))))
      (finally (.delete path)))))

(facts "Original fixture is read-only, compatible, and cannot be adopted as a managed daily month"
  (let [path "test/assets/2016_timesheet_Luca-Pacioli.xlsx"
        source (java.nio.file.Files/readAllBytes (.toPath (io/file path)))
        fixture-owner {:owner-id "luca" :person "Luca Pacioli" :person-alias "L.Pacioli"}
        draft {:owner-id "luca" :month "2016-01" :records [] :allocation {:days []}}]
    (workbook/render-workbook fixture-owner draft source) => (contains {:code :existing-month-unmanaged})
    (with-open [wb (XSSFWorkbook. (ByteArrayInputStream. source))]
      (:hours (first (core/load-monthly-hours {:xls wb :name "L.Pacioli"} "2016-1" (constantly true)))) => 54.0)
    (seq source) => (seq (java.nio.file.Files/readAllBytes (.toPath (io/file path))))))
