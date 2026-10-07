(ns agiladmin.mcp.credentials
  "Local administrator credentials. Only SHA-256 hashes enter durable storage."
  (:require [agiladmin.auth.core :as auth]
            [agiladmin.work-policy :as policy]
            [agiladmin.work-ledger :as ledger]
            [clojure.edn :as edn]
            [failjure.core :as f])
  (:import [java.nio.file Files Path Paths LinkOption StandardOpenOption]
           [java.nio.file.attribute PosixFilePermissions]
           [java.nio.channels FileChannel]
           [java.security SecureRandom MessageDigest]
           [java.nio.charset StandardCharsets]
           [java.time Instant]
           [java.util Base64 UUID]))

(defonce ^:private mutex (Object.))
(defn- safe! [^Path p]
  (loop [ancestor p]
    (when ancestor
      (when (Files/isSymbolicLink ancestor) (throw (ex-info "Unsafe credential storage" {})))
      (recur (.getParent ancestor))))
  p)
(defn store [data-path]
  (let [root (safe! (.resolve (.normalize (.toAbsolutePath (Paths/get data-path (make-array String 0)))) "credentials"))]
    (Files/createDirectories root (make-array java.nio.file.attribute.FileAttribute 0))
    (Files/setPosixFilePermissions root (PosixFilePermissions/fromString "rwx------"))
    {:root root}))
(defn token-hash [token]
  (apply str (map #(format "%02x" (bit-and (int %) 255))
                  (.digest (MessageDigest/getInstance "SHA-256")
                           (.getBytes ^String token StandardCharsets/UTF_8)))))
(defn resolve-owner [account-id]
  (f/attempt-all [accounts (auth/active-accounts)
                 mappings (policy/owner-mapping (filterv #(true? (:verified %)) accounts))]
    (or (get mappings account-id)
        (policy/error :inactive-owner [] "Active owner account required." "Ask the administrator to restore your account."))))
(defn- valid-record? [r]
  (and (= #{:id :owner-id :hash :expires-at :revoked?} (set (keys r)))
       (every? policy/nonblank-string? (map r [:id :owner-id :hash :expires-at]))
       (re-matches #"[0-9a-f]{64}" (:hash r)) (instance? Boolean (:revoked? r))
       (try (Instant/parse (:expires-at r)) true (catch Exception _ false))))
(defn- with-state [store change? operation]
  (locking mutex
    (let [root (:root store)
          target (safe! (.resolve ^Path root "tokens.edn"))
          lock-path (safe! (.resolve ^Path root ".lock"))]
      (with-open [channel (FileChannel/open lock-path (into-array StandardOpenOption [StandardOpenOption/CREATE StandardOpenOption/WRITE]))
                  lock (.lock channel)]
        (Files/setPosixFilePermissions lock-path (PosixFilePermissions/fromString "rw-------"))
        (let [exists (Files/exists target (make-array LinkOption 0))
              state (if exists
                      (do (when (> (Files/size target) 1048576) (throw (ex-info "Credential store too large" {})))
                          (with-open [r (java.io.PushbackReader. (Files/newBufferedReader target StandardCharsets/UTF_8))]
                            (let [opts {:eof ::eof :readers {} :default (fn [& _] (throw (ex-info "Tagged credential data" {})))}
                                  value (edn/read opts r)]
                              (when-not (= ::eof (edn/read opts r)) (throw (ex-info "Invalid credential data" {}))) value)))
                      {:version 1 :tokens []})]
          (when-not (and (= #{:version :tokens} (set (keys state))) (= 1 (:version state))
                         (vector? (:tokens state)) (every? valid-record? (:tokens state))
                         (= (count (:tokens state)) (count (set (map :id (:tokens state))))))
            (throw (ex-info "Invalid credential store" {})))
          (let [[updated result] (operation state)]
            (when change?
              (when (> (count (.getBytes (pr-str updated) StandardCharsets/UTF_8)) 1048576)
                (throw (ex-info "Credential store capacity reached" {})))
              (ledger/durable-write! root target (when exists state) updated)
              (Files/setPosixFilePermissions target (PosixFilePermissions/fromString "rw-------"))
              (when exists (Files/setPosixFilePermissions (.resolve ^Path root "tokens.edn.bak")
                                                         (PosixFilePermissions/fromString "rw-------"))))
            result))))))
(defn provision! [store owner-id expires-at]
  (f/attempt-all [owner (resolve-owner owner-id)]
    (let [expiry (Instant/parse expires-at)
          bytes (byte-array 32)]
      (when-not (.isAfter expiry (Instant/now)) (throw (ex-info "Expiry must be in the future" {})))
      (.nextBytes (SecureRandom.) bytes)
      (let [token (str "agm_" (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) bytes))
            record {:id (str (UUID/randomUUID)) :owner-id (:owner-id owner) :hash (token-hash token)
                    :expires-at (str expiry) :revoked? false}]
        (with-state store true (fn [s] [(update s :tokens conj record) (assoc (dissoc record :hash) :token token)]))))))
(defn revoke! [store id]
  (with-state store true (fn [s]
                          [(update s :tokens (fn [rs] (mapv #(if (= id (:id %)) (assoc % :revoked? true) %) rs)))
                           {:revoked-id id}])) )
(defn list-credentials [store]
  (with-state store false (fn [s] [s (mapv #(dissoc % :hash) (:tokens s))])))
(defn authenticate [store token]
  (try
    (when (and (string? token) (re-matches #"agm_[A-Za-z0-9_-]{43}" token))
      (let [hash (token-hash token)
            credential (with-state store false
                         (fn [s] [s (some #(when (and (MessageDigest/isEqual
                                                     (.getBytes hash StandardCharsets/UTF_8)
                                                     (.getBytes ^String (:hash %) StandardCharsets/UTF_8))
                                                    (not (:revoked? %))
                                                    (.isAfter (Instant/parse (:expires-at %)) (Instant/now))) %) (:tokens s))]))]
        (when credential
          (let [owner (resolve-owner (:owner-id credential))]
            (when-not (f/failed? owner) owner)))))
    (catch Exception _ nil)))
