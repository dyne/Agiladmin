(ns agiladmin.work-preview
  "Immutable, private preview artifacts. No official workbook or Git writes.
  Browser publication can verify the retained snapshot again under its own locks."
  (:require [agiladmin.work-ports :as ports]
            [agiladmin.work-policy :as policy]
            [agiladmin.work-service :as service]
            [agiladmin.work-workbook :as workbook]
            [agiladmin.work-ledger :as ledger]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [failjure.core :as f])
  (:import [java.nio.file Files Path Paths StandardOpenOption StandardCopyOption LinkOption]
           [java.nio.file.attribute PosixFilePermissions FileAttribute]
           [java.nio.channels FileChannel]
           [java.nio.charset StandardCharsets]
           [java.nio ByteBuffer]
           [java.time Instant]
           [java.util UUID]))

(def max-file-bytes (* 32 1024 1024))
(def lifetime-seconds 1800)
(defn- safe! [^Path root ^Path path]
  (when-not (.startsWith path root) (throw (ex-info "Outside preview root" {})))
  (loop [ancestor path]
    (when ancestor
      (when (Files/isSymbolicLink ancestor) (throw (ex-info "Unsafe preview path" {})))
      (recur (.getParent ancestor)))) path)
(defn- force-directory! [^Path path]
  (with-open [channel (FileChannel/open path (into-array StandardOpenOption [StandardOpenOption/READ]))]
    (.force channel true)))
(defn store [data-path]
  (let [root (.resolve (.normalize (.toAbsolutePath (Paths/get data-path (make-array String 0)))) "previews")]
    (safe! root root)
    (Files/createDirectories root (make-array FileAttribute 0))
    (Files/setPosixFilePermissions root (PosixFilePermissions/fromString "rwx------"))
    (force-directory! root)
    (force-directory! (.getParent root))
    {:root root}))
(defn- canonical [value]
  (cond (map? value) (into (sorted-map-by #(compare (pr-str %1) (pr-str %2)))
                           (map (fn [[k v]] [k (canonical v)]) value))
        (sequential? value) (mapv canonical value)
        :else value))
(defn- metadata-digest [metadata]
  (workbook/fingerprint (.getBytes (pr-str (canonical metadata)) StandardCharsets/UTF_8)))
(defn- refused [code]
  (policy/error code [:preview_id] "Preview is unavailable or no longer valid."
                "Create a fresh preview of your own month and follow its owner review URL."))
(defn- write-artifact! [^Path path bytes]
  (when (> (alength ^bytes bytes) max-file-bytes) (throw (ex-info "Preview too large" {})))
  (with-open [channel (FileChannel/open path (into-array StandardOpenOption [StandardOpenOption/CREATE_NEW StandardOpenOption/WRITE]))]
    (Files/setPosixFilePermissions path (PosixFilePermissions/fromString "rw-------"))
    (let [buffer (ByteBuffer/wrap bytes)] (while (.hasRemaining buffer) (.write channel buffer)))
    (.force channel true)))
(defn retain!
  "Store once in a new random directory; atomic directory rename publishes both
  metadata and workbook together. Names, bytes and baselines are server output."
  [store owner snapshot rendered]
  (if (or (f/failed? rendered) (not= (:owner-id owner) (:owner-id snapshot)))
    (if (f/failed? rendered) rendered (refused :preview-not-found))
    (try
      (let [root (:root store) id (str (UUID/randomUUID))
            target (safe! root (.resolve ^Path root id))
            temp (Files/createTempDirectory root ".preview-" (into-array FileAttribute [(PosixFilePermissions/asFileAttribute
                                                                                       (PosixFilePermissions/fromString "rwx------"))]))
            now (Instant/now)
            metadata {:preview-version 1 :preview-id id :owner-id (:owner-id owner)
                      :created-at (str now) :expires-at (str (.plusSeconds now lifetime-seconds))
                      :snapshot snapshot :filename (:filename rendered)
                      :source-inspection (:source-inspection rendered) :baseline (:baseline rendered)
                      :artifact-fingerprint (workbook/fingerprint (:bytes rendered)) :changes (:changes rendered)}
            value (assoc metadata :preview-digest (metadata-digest metadata))]
        (try
          (when (> (count (.getBytes (pr-str value) StandardCharsets/UTF_8)) max-file-bytes)
            (throw (ex-info "Preview metadata too large" {})))
          (write-artifact! (.resolve temp "workbook.xlsx") (:bytes rendered))
          (ledger/durable-write! temp (.resolve temp "preview.edn") nil value)
          (Files/setPosixFilePermissions (.resolve temp "preview.edn") (PosixFilePermissions/fromString "rw-------"))
          (Files/move temp target (into-array StandardCopyOption [StandardCopyOption/ATOMIC_MOVE]))
          (force-directory! root)
          value
          (finally
            (when (Files/exists temp (make-array LinkOption 0))
              (Files/deleteIfExists (.resolve temp "preview.edn"))
              (Files/deleteIfExists (.resolve temp "workbook.xlsx"))
              (Files/deleteIfExists temp)))))
      (catch Exception _ (refused :preview-storage-failure)))))
(defn read-preview
  "Private server interface: owner-bound immutable evidence, even after expiry.
  Expiry/live-draft checks belong to verify-preview before browser confirmation."
  [store owner id]
  (try
    (if-not (and (string? id) (re-matches #"[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}" id))
      (refused :preview-not-found)
      (let [root (:root store) directory (safe! root (.resolve ^Path root id))
            path (safe! root (.resolve directory "preview.edn"))
            artifact (safe! root (.resolve directory "workbook.xlsx"))]
        (if (or (not (Files/exists path (make-array LinkOption 0))) (> (Files/size path) max-file-bytes))
          (refused :preview-not-found)
          (with-open [reader (java.io.PushbackReader. (Files/newBufferedReader path StandardCharsets/UTF_8))]
            (let [options {:eof ::eof :readers {} :default (fn [& _] (throw (ex-info "Tagged preview data" {})))}
                  value (edn/read options reader)]
              (cond
                (or (not (map? value)) (not= (:owner-id owner) (:owner-id value))) (refused :preview-not-found)
                (not (and (= ::eof (edn/read options reader)) (= 1 (:preview-version value)) (= id (:preview-id value))
                          (= (:owner-id owner) (get-in value [:snapshot :owner-id]))
                          (= (:preview-digest value) (metadata-digest (dissoc value :preview-digest)))
                          (policy/nonblank-string? (:filename value))
                          (= (:filename value) (.getName (io/file (:filename value))))
                          (not (re-find #"[\p{Cntrl}]" (:filename value)))))
                (refused :corrupt-preview)
                :else
                (do
                  (Instant/parse (:expires-at value))
                  (when (> (Files/size artifact) max-file-bytes) (throw (ex-info "Oversized artifact" {})))
                  (if (= (:artifact-fingerprint value) (workbook/fingerprint (Files/readAllBytes artifact)))
                    value (refused :corrupt-preview)))))))))
    (catch Exception _ (refused :preview-not-found))))
(defn artifact
  "Bearer download boundary. Historical bytes remain immutable; no path input."
  [store owner id]
  (f/attempt-all [preview (read-preview store owner id)]
    (if-not (.isAfter (Instant/parse (:expires-at preview)) (Instant/now))
      (refused :preview-expired)
      (try
        (let [root (:root store) path (safe! root (.resolve (.resolve ^Path root id) "workbook.xlsx"))
              bytes (Files/readAllBytes path)]
          (if (= (:artifact-fingerprint preview) (workbook/fingerprint bytes))
            {:bytes bytes :filename (:filename preview) :preview preview} (refused :corrupt-preview)))
        (catch Exception _ (refused :preview-not-found))))))
(defn verify-preview
  "Call under publication locks too: owner, expiry, exact draft/policy and trusted
  annual fingerprint/target-month baseline must still match. No writes."
  [ledger workbook owner settings preview]
  (cond
    (not= (:owner-id owner) (:owner-id preview)) (refused :preview-not-found)
    (not (.isAfter (Instant/parse (:expires-at preview)) (Instant/now))) (refused :preview-expired)
    :else
    (f/attempt-all [current (service/check-snapshot ledger owner settings (:snapshot preview))
                   inspection (ports/inspect-month workbook owner (:month current))]
      (if (= (select-keys inspection [:fingerprint :month-baseline])
             (select-keys (:source-inspection preview) [:fingerprint :month-baseline]))
        preview
        (policy/error :workbook-conflict [] "Official workbook changed after this preview."
                      "Reconcile external edits and obtain a fresh owner review.")))))
(defn create! [store ledger workbook owner settings month]
  (f/attempt-all [snapshot (service/month-snapshot ledger owner settings month)
                 current (service/check-snapshot ledger owner settings snapshot)
                 rendered (ports/render-preview workbook current)
                 _unchanged (service/check-snapshot ledger owner settings snapshot)]
    (retain! store owner snapshot rendered)))
