(ns agiladmin.work-workbook
  "Excel adapter. Inputs are server-resolved owners and immutable snapshots;
  outputs are in-memory preview artifacts, never official files or Git effects."
  (:require [clojure.string :as str]
            [failjure.core :as f]
            [agiladmin.work-policy :as policy]
            [agiladmin.work-ports :as ports]
            [agiladmin.core :as core]
            [agiladmin.utils :as util])
  (:import [java.time YearMonth LocalDate]
           [java.sql Date]
           [java.io ByteArrayInputStream ByteArrayOutputStream]
           [java.security MessageDigest]
           [java.nio.charset StandardCharsets]
           [org.apache.poi.xssf.usermodel XSSFWorkbook]
           [org.apache.poi.ss.usermodel CellType]
           [org.apache.poi.ss.util CellReference]))

(defn sheet-name [month]
  (let [ym (YearMonth/parse month)]
    (str (.getYear ym) "-" (.getMonthValue ym))))

(defn total-row [month] (+ 11 (.lengthOfMonth (YearMonth/parse month))))

(defn cell
  "One-based coordinates, matching the documented workbook contract."
  [sheet row column]
  (let [r (or (.getRow sheet (dec row)) (.createRow sheet (dec row)))]
    (or (.getCell r (dec column)) (.createCell r (dec column)))))

(defn- text! [sheet row column value]
  (let [c (cell sheet row column)]
    (.setCellType c CellType/STRING)
    (.setCellValue c (str value))))

(defn- number! [sheet row column value]
  (let [c (cell sheet row column)]
    (.setCellType c CellType/NUMERIC)
    (.setCellValue c (double value))))

(defn- formula! [sheet row column formula]
  (.setCellFormula (cell sheet row column) formula))

(defn- expand-print-area! [wb sheet]
  (let [index (.getSheetIndex wb sheet)
        addresses (re-seq #"\$?([A-Z]+)\$?(\d+)" (or (.getPrintArea wb index) ""))
        right (reduce max 8 (map #(CellReference/convertColStringToIndex (second %)) addresses))
        bottom (reduce max 46 (map #(dec (Integer/parseInt (nth % 2))) addresses))]
    (.setPrintArea wb index 0 right 0 bottom)))

(defn fingerprint [bytes]
  (if bytes
    (apply str (map #(format "%02x" (bit-and (int %) 255))
                    (.digest (MessageDigest/getInstance "SHA-256") ^bytes bytes)))
    "absent"))

(defn- existing-cell [sheet row column]
  (when-let [r (.getRow sheet (dec row))] (.getCell r (dec column))))

(defn- cell-value [c]
  (when c
    (let [type (if (= CellType/FORMULA (.getCellTypeEnum c))
                 (.getCachedFormulaResultTypeEnum c) (.getCellTypeEnum c))]
      (cond
        (= type CellType/STRING) (.getStringCellValue c)
        (= type CellType/NUMERIC) (.getNumericCellValue c)
        (= type CellType/BOOLEAN) (.getBooleanCellValue c)
        (= type CellType/ERROR) [:error (.getErrorCellValue c)]))))

(defn- month-baseline [sheet]
  (fingerprint
   (.getBytes
    (pr-str (when sheet
              (for [row (iterator-seq (.rowIterator sheet))
                    c (iterator-seq (.cellIterator row))]
                [(.getRowNum row) (.getColumnIndex c) (str (.getCellTypeEnum c))
                 (when (= CellType/FORMULA (.getCellTypeEnum c)) (.getCellFormula c))
                 (cell-value c) (.getIndex (.getCellStyle c))])))
    StandardCharsets/UTF_8)))

(defn- populated? [sheet month]
  (let [nonempty? #(and (some? %) (not= 0 %) (not= 0.0 %) (not= "" %))]
    (boolean
     (and sheet
          (or (nonempty? (cell-value (existing-cell sheet 4 2)))
              (some nonempty? (for [r (range 11 44) c (range 2 9)]
                                (cell-value (existing-cell sheet r c))))
              (some nonempty? (for [r (range 11 (total-row month))]
                                (cell-value (existing-cell sheet r 9)))))))))

(defn- inspect-open [wb owner month source-fingerprint]
  (let [year (subs month 0 4)
        first-sheet (.getSheetAt wb 0)
        metadata (cell-value (existing-cell first-sheet 2 2))
        identity? (fn [sheet]
                    (= (:person-alias owner)
                       (some-> (cell-value (existing-cell sheet 3 2)) str util/dotname)))
        monthly (filter #(re-matches #"\d{4}-\d{1,2}" (.getSheetName %))
                        (map #(.getSheetAt wb %) (range (.getNumberOfSheets wb))))]
    (cond
      (not (and (string? metadata) (re-matches #"\d{4}-\d{1,2}" metadata)
                (= year (first (str/split metadata #"-")))
                (every? #(and (re-matches #"\d{4}-(?:[1-9]|1[0-2])" (.getSheetName %))
                              (= year (first (str/split (.getSheetName %) #"-")))
                              (= (.getSheetName %) (cell-value (existing-cell % 2 2)))) monthly)))
      (policy/error :workbook-year-mismatch [] "Workbook month/year identity differs from the requested year."
                    "Reconcile workbook headers before creating another preview.")
      (not (and (identity? first-sheet) (every? identity? monthly)))
      (policy/error :workbook-owner-mismatch [] "Workbook belongs to another person."
                    "Resolve the server account mapping and reconcile the workbook; do not overwrite it.")
      :else
      (let [sheet (.getSheet wb (sheet-name month))]
        (.evaluateAll (.createFormulaEvaluator (.getCreationHelper wb)))
        {:baseline-version 1 :owner-id (:owner-id owner) :month month
         :fingerprint source-fingerprint :month-baseline (month-baseline sheet)
         :empty? (not (populated? sheet month))}))))

(defn inspect-workbook
  "Read-only inspection of trusted server-mapped workbook bytes. No path input."
  [owner month existing-bytes]
  (f/attempt-all
   [_month (policy/month-capacity {:days []} (:owner-id owner) month)]
   (if-not existing-bytes
     {:baseline-version 1 :owner-id (:owner-id owner) :month month :fingerprint "absent"
      :month-baseline (month-baseline nil) :empty? true}
     (try
       (with-open [wb (XSSFWorkbook. (ByteArrayInputStream. existing-bytes))]
         (inspect-open wb owner month (fingerprint existing-bytes)))
       (catch Exception _
         (policy/error :workbook-error [] "Workbook cannot be inspected safely."
                       "Repair the workbook before creating another preview."))))))

(defn- adoption-gate [inspection baseline]
  (cond
    (and baseline (not (and (= 1 (:baseline-version baseline))
                           (= (:owner-id inspection) (:owner-id baseline))
                           (= (:month inspection) (:month baseline))
                           (string? (:fingerprint baseline)) (string? (:month-baseline baseline)))))
    (policy/error :invalid-workbook-baseline [] "Managed-month evidence does not identify this owner/month."
                  "Restore the trusted publication baseline before reconciling this month.")
    (and baseline (not= (:fingerprint baseline) (:fingerprint inspection)))
    (policy/error :workbook-conflict [] "Workbook changed outside the managed baseline."
                  "Reconcile external edits and obtain a fresh owner review; never overwrite them.")
    (and baseline (not= (:month-baseline baseline) (:month-baseline inspection)))
    (policy/error :month-conflict [] "Target month differs from its daily managed baseline."
                  "Reconcile the target month's daily cells before obtaining a fresh review.")
    (and (nil? baseline) (not (:empty? inspection)))
    (policy/error :existing-month-unmanaged [] "Target month is populated without a daily managed baseline."
                  "Keep the draft. Daily import/reconciliation of legacy months is required before replacement.")
    :else inspection))

(defn- notes-for-day [records]
  (->> records
       (keep (fn [{:keys [project_id task_id voluntary note]}]
               (when (and (string? note) (not (empty? note)))
                 (str "[" project_id (when task_id (str "/" task_id))
                      (when voluntary " VOL") "] " note))))
       distinct sort (str/join "\n")))

(defn- allocation-conserves-records? [records days]
  (let [record-totals (reduce (fn [acc r]
                                (update acc [(:date r) (:project_id r) (or (:task_id r) "")]
                                        (fnil + 0) (:minutes r))) {} records)
        assignment-totals (reduce (fn [acc [date a]]
                                    (update acc [date (:project_id a) (or (:task_id a) "")]
                                            (fnil + 0) (:recorded-minutes a)))
                                  {} (for [d days a (:assignments d)] [(:date d) a]))]
    (and (= record-totals assignment-totals)
         (every? (fn [a] (= (:recorded-minutes a) (+ (:paid-minutes a) (:vol-minutes a))))
                 (mapcat :assignments days)))))

(defn- render-model [owner snapshot]
  (cond
    (not= (:owner-id owner) (:owner-id snapshot))
    (policy/error :owner-mismatch [] "Snapshot belongs to another owner." "Read your own month.")
    (not (and (policy/nonblank-string? (:person owner))
              (not (re-find #"[/\\\p{Cntrl}]|\.\." (:person owner)))
              (= (:person-alias owner) (util/dotname (:person owner)))))
    (policy/error :invalid-identity [] "Missing safe server-resolved person name." "Repair the account mapping.")
    :else
    (f/attempt-all
     [capacity (policy/month-capacity (:allocation snapshot) (:owner-id owner) (:month snapshot))]
     (let [records (:records snapshot)
           days (get-in snapshot [:allocation :days])
           notes (into (sorted-map) (map (fn [[date rs]] [date (notes-for-day rs)])
                                         (group-by :date records)))]
       (cond
         (not (:exportable? capacity))
         (assoc (policy/error :column-overflow [] "Month requires more than seven assignment columns."
                              "Ask the owner to explicitly consolidate assignments.") :capacity capacity)
         (or (some #(or (not= (:owner-id owner) (:owner-id %))
                        (not (str/starts-with? (:date %) (str (:month snapshot) "-")))) records)
             (some #(or (not= (:owner-id owner) (:owner-id %))
                        (not (str/starts-with? (:date %) (str (:month snapshot) "-")))) days))
         (policy/error :invalid-snapshot [] "Snapshot contains another owner or month." "Create a fresh month snapshot.")
         (not (allocation-conserves-records? records days))
         (policy/error :invalid-snapshot [] "Daily allocations do not conserve the exact record assignments."
                       "Create a fresh authoritative month snapshot; do not consolidate silently.")
         (some #(> (.length ^String %) 32767) (vals notes))
         (policy/error :notes-overflow [:records :note] "Combined daily notes exceed Excel's cell limit."
                       "Shorten notes explicitly before creating another preview.")
         :else {:capacity capacity :notes notes :days days})))))

(defn- clean-sheet! [wb owner month]
  (let [sheet (.createSheet wb (sheet-name month))
        days (.lengthOfMonth (YearMonth/parse month))
        total (total-row month)
        date-style (.createCellStyle wb)
        header-style (.createCellStyle wb)
        font (.createFont wb)]
    (.setDataFormat date-style (.getFormat (.createDataFormat wb) "ddd d"))
    (.setBold font true)
    (.setFont header-style font)
    (doseq [[r label] [[2 "Month:"] [3 "Name:"] [4 "Hours:"] [5 "Days:"]
                      [7 "Project"] [8 "Task"] [9 "Tag"] [43 "Signature:"]
                      [44 "Date:"] [45 "Free text space:"]]]
      (text! sheet r 1 label))
    (text! sheet 2 2 (sheet-name month))
    (text! sheet 3 2 (:person owner))
    (text! sheet 10 9 "Daily notes")
    (doseq [column (range 2 9)]
      (.setColumnWidth sheet (dec column) (* 14 256))
      (doseq [r [7 8 9]]
        (text! sheet r column "")
        (.setCellStyle (cell sheet r column) header-style))
      (formula! sheet total column
                (str "SUM(" (char (+ 64 column)) "11:" (char (+ 64 column)) (dec total) ")")))
    (.setColumnWidth sheet 0 (* 20 256))
    (.setColumnWidth sheet 8 (* 65 256))
    (doseq [day (range 1 (inc days))]
      (let [c (cell sheet (+ 10 day) 1)]
        (.setCellValue c (Date/valueOf (.atDay (YearMonth/parse month) day)))
        (.setCellStyle c date-style)))
    (formula! sheet 4 2 (str "SUM(B" total ":H" total ")"))
    (formula! sheet 5 2
              (str "SUM(" (str/join "," (for [r (range 11 total)]
                                          (str "IF(SUM(B" r ":H" r ")>0,1,0)"))) ")"))
    (.setPrintArea wb (.getSheetIndex wb sheet) "A1:I47")
    (.setFitToPage sheet true)
    (.setFitWidth (.getPrintSetup sheet) (short 1))
    (.setFitHeight (.getPrintSetup sheet) (short 0))
    sheet))

(defn- write-month! [wb sheet month {:keys [capacity notes days]}]
  (let [total (total-row month)
        columns (into {} (map-indexed (fn [i a] [[(:project_id a) (or (:task_id a) "") (:tag a)] (+ 2 i)])
                                     (:assignments capacity)))
        note-styles (atom {})
        date-styles (atom {})]
    (doseq [col (range 2 9)]
      (let [assignment (nth (:assignments capacity) (- col 2) nil)]
        (doseq [[r value] [[7 (:project_id assignment)] [8 (:task_id assignment)] [9 (:tag assignment)]]]
          (text! sheet r col (or value "")))
        (doseq [r (range 11 total)] (number! sheet r col 0))))
    (doseq [{:keys [date assignments]} days
            a assignments
            [field tag] [[:paid-minutes ""] [:vol-minutes "VOL"]]
            :let [minutes (get a field)]
            :when (pos? minutes)]
      (number! sheet (+ 10 (.getDayOfMonth (LocalDate/parse date)))
               (get columns [(:project_id a) (or (:task_id a) "") tag]) (/ minutes 60.0)))
    (doseq [day (range 1 (inc (.lengthOfMonth (YearMonth/parse month))))]
      (let [r (+ 10 day) date (str (.atDay (YearMonth/parse month) day))
            note (get notes date "") c (cell sheet r 9)
            old-style (.getCellStyle c) idx (.getIndex old-style)
            style (or (get @note-styles idx)
                      (let [s (.createCellStyle wb)]
                        (.cloneStyleFrom s old-style) (.setWrapText s true)
                        (swap! note-styles assoc idx s) s))]
        (text! sheet r 9 note)
        (.setCellStyle c style)
        (when (seq note)
          (let [row (.getRow sheet (dec r))
                lines (reduce + (map #(max 1 (long (Math/ceil (/ (count %) 65.0))))
                                     (str/split note #"\n" -1)))]
            (.setHeightInPoints row (float (min 409 (max (.getHeightInPoints row) (* 15 lines)))))))))
    (doseq [day (range 1 (inc (.lengthOfMonth (YearMonth/parse month))))]
      (let [c (cell sheet (+ 10 day) 1)
            original (.getCellStyle c)
            index (.getIndex original)
            style (if (= "ddd d" (.getDataFormatString original)) original
                      (or (get @date-styles index)
                          (let [s (.createCellStyle wb)]
                            (.cloneStyleFrom s original)
                            (.setDataFormat s (.getFormat (.createDataFormat wb) "ddd d"))
                            (swap! date-styles assoc index s) s)))]
        (.setCellValue c (Date/valueOf (.atDay (YearMonth/parse month) day)))
        (.setCellStyle c style)))
    ;; Preserve pre-existing formulas and their styles. New sheets already have
    ;; the complete total formulas; empty adopted sheets receive missing ones.
    (doseq [col (range 2 9)]
      (when-not (= CellType/FORMULA (.getCellTypeEnum (cell sheet total col)))
        (formula! sheet total col (str "SUM(" (char (+ 64 col)) "11:" (char (+ 64 col)) (dec total) ")"))))
    (when-not (= CellType/FORMULA (.getCellTypeEnum (cell sheet 4 2)))
      (formula! sheet 4 2 (str "SUM(B" total ":H" total ")")))
    (when-not (= CellType/FORMULA (.getCellTypeEnum (cell sheet 5 2)))
      (formula! sheet 5 2
                (str "SUM(" (str/join "," (for [r (range 11 total)]
                                            (str "IF(SUM(B" r ":H" r ")>0,1,0)"))) ")")))
    (expand-print-area! wb sheet)))

(def hours-tolerance 1.0e-9)
(defn- hours-match? [a b]
  (and (number? a) (number? b) (<= (Math/abs (- (double a) (double b))) hours-tolerance)))

(defn- reconcile-report [wb owner snapshot capacity]
  (try
    (let [month (sheet-name (:month snapshot))
          sheet (.getSheet wb month)
          actual (core/load-monthly-hours {:xls wb :name (:person-alias owner)} month (constantly true))
          key-fn (juxt :project :task :tag)
          actual-hours (into {} (map (juxt key-fn :hours) actual))
          expected (into {} (map (fn [a] [[(:project_id a) (or (:task_id a) "") (:tag a)]
                                          (/ (:minutes a) 60.0)]) (:assignments capacity)))
          recorded (reduce + 0 (map :minutes (:records snapshot)))
          allocated (reduce + 0 (map :minutes (:assignments capacity)))
          ym (YearMonth/parse (:month snapshot))
          calendar-matches? (every? (fn [day]
                                     (= (.atDay ym day)
                                        (.toLocalDate (Date. (.getTime (.getDateCellValue
                                                                      (existing-cell sheet (+ 10 day) 1)))))))
                                   (range 1 (inc (.lengthOfMonth ym))))
          active-days (count (filter #(pos? (:recorded-minutes %)) (get-in snapshot [:allocation :days])))]
      (if (and calendar-matches? (= recorded allocated)
               (= (count actual) (count expected)) (= (set (keys expected)) (set (keys actual-hours)))
               (every? (fn [[k h]] (hours-match? h (get actual-hours k))) expected)
               (hours-match? (/ recorded 60.0) (cell-value (existing-cell sheet 4 2)))
               (hours-match? active-days (cell-value (existing-cell sheet 5 2))))
        {:hours actual :recorded-minutes recorded :hours-tolerance hours-tolerance}
        (policy/error :workbook-reconciliation-failed [] "Generated workbook reports do not match the daily draft."
                      "Reconcile workbook formulas and draft allocation before obtaining owner review.")))
    (catch Exception _
      (policy/error :workbook-reconciliation-failed [] "Generated workbook cannot be read by official reports."
                    "Reconcile workbook cells/formulas before obtaining owner review."))))

(defn render-workbook
  "Render a preview from optional existing workbook bytes. Does not write files.
  Callers supply identity from owner-mapping, never a caller filesystem path.
  Managed baselines are trusted publication evidence, never client input.
  Existing bytes always pass adoption/conflict gates, including this low-level API."
  ([owner snapshot] (render-workbook owner snapshot nil))
  ([owner snapshot existing-bytes] (render-workbook owner snapshot existing-bytes nil))
  ([owner snapshot existing-bytes baseline]
   (f/attempt-all
    [model (render-model owner snapshot)
     inspection (inspect-workbook owner (:month snapshot) existing-bytes)
     _adoption (adoption-gate inspection baseline)]
    (try
      (with-open [wb (if existing-bytes (XSSFWorkbook. (ByteArrayInputStream. existing-bytes)) (XSSFWorkbook.))
                  out (ByteArrayOutputStream.)]
        (let [year (.getYear (YearMonth/parse (:month snapshot)))]
          (when-not existing-bytes
            (doseq [m (range 1 13)] (clean-sheet! wb owner (format "%04d-%02d" year m))))
          (let [sheet (or (.getSheet wb (sheet-name (:month snapshot)))
                          (clean-sheet! wb owner (:month snapshot)))]
            (write-month! wb sheet (:month snapshot) model)
            (.evaluateAll (.createFormulaEvaluator (.getCreationHelper wb)))
            (.write wb out)
            (let [output-bytes (.toByteArray out)]
              (with-open [reopened (XSSFWorkbook. (ByteArrayInputStream. output-bytes))]
                (f/attempt-all
                 [report (reconcile-report reopened owner snapshot (:capacity model))
                  output-inspection (inspect-open reopened owner (:month snapshot) (fingerprint output-bytes))]
                 {:bytes output-bytes
                  :filename (str year "_timesheet_" (str/replace (:person owner) #"\s+" "-") ".xlsx")
                  :capacity (:capacity model) :snapshot-digest (:digest snapshot)
                  :source-inspection inspection
                  :baseline (dissoc output-inspection :empty?) :report report}))))))
      (catch Exception _
        (policy/error :workbook-error [] "Workbook could not be rendered." "Reconcile the workbook and create a new preview."))))))

(defrecord WorkbookAdapter [resolve-owner read-workbook read-baseline]
  ports/WorkWorkbook
  (inspect-month [_ owner month]
    (f/attempt-all
     [mapped (or (resolve-owner (:owner-id owner))
                 (policy/error :owner-required [] "Active owner mapping is required." "Authenticate again."))
      _month (policy/month-capacity {:days []} (:owner-id mapped) month)
      bytes (read-workbook mapped (subs month 0 4))
      baseline (read-baseline mapped month)
      inspection (inspect-workbook mapped month bytes)
      _gate (adoption-gate inspection baseline)]
     (assoc inspection :managed? (boolean baseline))))
  (render-preview [_ snapshot]
    (f/attempt-all
     [mapped (or (resolve-owner (:owner-id snapshot))
                 (policy/error :owner-required [] "Active owner mapping is required." "Authenticate again."))
      _month (policy/month-capacity {:days []} (:owner-id mapped) (:month snapshot))
      bytes (read-workbook mapped (subs (:month snapshot) 0 4))
      baseline (read-baseline mapped (:month snapshot))]
     (render-workbook mapped snapshot bytes baseline))))

(defn workbook-adapter
  "Wire trusted server-owned readers. This adapter performs no writes; later
  publication owns locking, durable baseline updates and official file changes."
  [{:keys [resolve-owner read-workbook read-baseline]}]
  (if (every? fn? [resolve-owner read-workbook read-baseline])
    (->WorkbookAdapter resolve-owner read-workbook read-baseline)
    (policy/error :invalid-workbook-adapter [] "Trusted workbook readers and owner resolver are required."
                  "Configure the workbook boundary before enabling previews.")))
