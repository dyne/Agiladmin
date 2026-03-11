;; Local override for the legacy clj-openssh-keygen dependency.
;; Clojure 1.12 rejects its default-package :import form for OpenSSHWriter.

(ns clj-openssh-keygen.core
  (:refer-clojure :exclude [format])
  (:require [clojure.java.io :as io])
  (:import [org.apache.commons.codec.binary Base64]
           [org.bouncycastle.jce.provider BouncyCastleProvider]
           [org.bouncycastle.openssl PEMWriter]
           [java.io StringWriter]
           [java.security KeyPairGenerator Security]))

(def default-algorithm "RSA")
(def default-key-size 4096)

(Security/addProvider (BouncyCastleProvider.))

(defn- open-ssh-writer []
  (.newInstance (Class/forName "OpenSSHWriter")))

(defn encode-base64 [bindata]
  (Base64/encodeBase64 bindata))

(defn generate-key-pair [& {:keys [key-size algorithm]}]
  (let [key-pair-generator (KeyPairGenerator/getInstance
                            (or algorithm default-algorithm))]
    (.initialize key-pair-generator (or key-size default-key-size))
    (.generateKeyPair key-pair-generator)))

(defn write-key-pair [key-pair path]
  (let [pub-writer (open-ssh-writer)]
    (with-open [out (io/output-stream (str path ".pub"))]
      (.write out (.getBytes "ssh-rsa "))
      (.write out (encode-base64
                   (.encode pub-writer (.getPublic key-pair))))))

  (let [priv-writer (StringWriter.)
        pem-writer (PEMWriter. priv-writer)]
    (.writeObject pem-writer (.getPrivate key-pair))
    (.flush pem-writer)
    (spit path (.toString priv-writer))))
