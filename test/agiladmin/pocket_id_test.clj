(ns agiladmin.pocket-id-test
  (:require [agiladmin.auth.pocket-id :as pocket-id]
            [agiladmin.config :as conf]
            [cheshire.core :as json]
            [failjure.core :as f]
            [midje.sweet :refer :all])
  (:import (java.security KeyPairGenerator Signature)
           (java.security.interfaces RSAPublicKey)
           (java.time Instant)
           (java.util Base64)))

(def config
  {:issuer-url "https://pocket-id.example.org"
   :client-id "agiladmin"
   :client-secret "secret"
   :redirect-uri "https://agiladmin.example.org/auth/pocket-id/callback"
   :admin-group "agiladmin-admin"
   :manager-group "agiladmin-manager"
   :scopes conf/default-pocket-id-scopes})

(defn- encoder
  []
  (.withoutPadding (Base64/getUrlEncoder)))

(defn- base64-url
  [value]
  (.encodeToString (encoder) value))

(defn- jwt-segment
  [value]
  (base64-url (.getBytes (json/generate-string value) "UTF-8")))

(defn- jwk-from-public-key
  [kid ^RSAPublicKey public-key]
  {"kid" kid
   "kty" "RSA"
   "alg" "RS256"
   "use" "sig"
   "n" (base64-url (.toByteArray (.getModulus public-key)))
   "e" (base64-url (.toByteArray (.getPublicExponent public-key)))})

(defn- sign-jwt
  [private-key header claims]
  (let [header-segment (jwt-segment header)
        payload-segment (jwt-segment claims)
        signing-input (str header-segment "." payload-segment)
        signature (doto (Signature/getInstance "SHA256withRSA")
                    (.initSign private-key)
                    (.update (.getBytes signing-input "UTF-8")))]
    (str signing-input "." (base64-url (.sign signature)))))

(defn- key-pair
  []
  (let [generator (KeyPairGenerator/getInstance "RSA")]
    (.initialize generator 2048)
    (.generateKeyPair generator)))

(fact "Pocket ID begin-login stores state and redirects to the authorization endpoint"
      (with-redefs [agiladmin.auth.pocket-id/discovery
                    (fn [_]
                      {:authorization_endpoint "https://pocket-id.example.org/authorize"})]
        (let [response (pocket-id/begin-login config {:session {:config :present}})
              location (get-in response [:headers "Location"])]
          (:status response) => 302
          location => (contains "https://pocket-id.example.org/authorize")
          location => (contains "code_challenge_method=S256")
          (get-in response [:session :auth-flow :provider]) => "pocket-id"
          (get-in response [:session :config]) => :present)))

(fact "Pocket ID complete-login validates the callback and maps groups to roles"
      (let [pair (key-pair)
            flow {:provider "pocket-id"
                  :state "state-1"
                  :nonce "nonce-1"
                  :code-verifier "verifier-1"}
            claims {:iss "https://pocket-id.example.org"
                    :sub "user-1"
                    :aud "agiladmin"
                    :nonce "nonce-1"
                    :email "user@example.org"
                    :name "User Name"
                    :email_verified true
                    :groups ["agiladmin-admin"]
                    :exp (+ 300 (.getEpochSecond (Instant/now)))}
            token (sign-jwt (.getPrivate pair) {"alg" "RS256" "kid" "kid-1"} claims)]
        (with-redefs [agiladmin.auth.pocket-id/discovery
                      (fn [_]
                        {:token_endpoint "https://pocket-id.example.org/token"
                         :jwks_uri "https://pocket-id.example.org/jwks"
                         :userinfo_endpoint "https://pocket-id.example.org/userinfo"})
                      agiladmin.auth.pocket-id/token-response
                      (fn [_ _ code flow-arg]
                        code => "code-1"
                        flow-arg => flow
                        {:id_token token
                         :access_token "access-1"})
                      agiladmin.auth.pocket-id/jwks
                      (fn [_ _]
                        {:keys [(jwk-from-public-key "kid-1" (.getPublic pair))]})
                      agiladmin.auth.pocket-id/userinfo
                      (fn [& _]
                        {})]
          (pocket-id/complete-login
           config
           {:session {:auth-flow flow}
            :params {:state "state-1"
                     :code "code-1"}})
          => {:id "user-1"
              :email "user@example.org"
              :name "User Name"
              :role "admin"
              :other-names []
              :verified true})))

(fact "Pocket ID complete-login can pull missing groups from userinfo"
      (let [pair (key-pair)
            flow {:provider "pocket-id"
                  :state "state-2"
                  :nonce "nonce-2"
                  :code-verifier "verifier-2"}
            claims {:iss "https://pocket-id.example.org"
                    :sub "user-2"
                    :aud "agiladmin"
                    :nonce "nonce-2"
                    :email "manager@example.org"
                    :name "Manager User"
                    :exp (+ 300 (.getEpochSecond (Instant/now)))}
            token (sign-jwt (.getPrivate pair) {"alg" "RS256" "kid" "kid-2"} claims)]
        (with-redefs [agiladmin.auth.pocket-id/discovery
                      (fn [_]
                        {:token_endpoint "https://pocket-id.example.org/token"
                         :jwks_uri "https://pocket-id.example.org/jwks"
                         :userinfo_endpoint "https://pocket-id.example.org/userinfo"})
                      agiladmin.auth.pocket-id/token-response
                      (fn [& _]
                        {:id_token token
                         :access_token "access-2"})
                      agiladmin.auth.pocket-id/jwks
                      (fn [_ _]
                        {:keys [(jwk-from-public-key "kid-2" (.getPublic pair))]})
                      agiladmin.auth.pocket-id/userinfo
                      (fn [& _]
                        {:groups ["agiladmin-manager"]})]
          (:role (pocket-id/complete-login
                  config
                  {:session {:auth-flow flow}
                   :params {:state "state-2"
                            :code "code-2"}}))
          => "manager")))

(fact "Pocket ID complete-login rejects a state mismatch"
      (let [result (pocket-id/complete-login
                    config
                    {:session {:auth-flow {:provider "pocket-id"
                                           :state "expected"
                                           :nonce "nonce"
                                           :code-verifier "verifier"}}
                     :params {:state "actual"
                              :code "code-1"}})]
        (f/failed? result) => true
        (f/message result) => "Pocket ID state mismatch."))
