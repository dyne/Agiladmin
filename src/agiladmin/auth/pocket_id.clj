(ns agiladmin.auth.pocket-id
  (:require [agiladmin.auth.user :as auth-user]
            [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.string :as str]
            [failjure.core :as f]
            [ring.util.codec :as codec])
  (:import (java.math BigInteger)
           (java.nio.charset StandardCharsets)
           (java.security KeyFactory MessageDigest SecureRandom Signature)
           (java.security.interfaces RSAPublicKey)
           (java.security.spec RSAPublicKeySpec)
           (java.time Instant)
           (java.util Base64)))

(def ^:private default-timeout-ms 2000)

(defn- trim-slash
  "Remove trailing slashes from an URL."
  [value]
  (str/replace value #"/+$" ""))

(defn- endpoint
  "Build an endpoint URL from a base URL and path."
  [base-url path]
  (str (trim-slash base-url) path))

(defn- request
  "Perform an HTTP request with shared defaults."
  [method url config options]
  (http/request
   (merge {:method method
           :url url
           :accept :json
           :as :json
           :coerce :always
           :throw-exceptions false
           :conn-timeout (or (:connect-timeout-ms config)
                             default-timeout-ms)
           :socket-timeout (or (:socket-timeout-ms config)
                               default-timeout-ms)}
          options)))

(defn- ensure-success
  "Return the parsed response body or throw with the provider message."
  [response]
  (if (<= 200 (:status response) 299)
    (:body response)
    (throw (ex-info (or (get-in response [:body :error_description])
                        (get-in response [:body :message])
                        "Pocket ID request failed.")
                    {:response response}))))

(defn- random-token
  "Generate a random URL-safe token."
  []
  (let [bytes (byte-array 32)
        encoder (.withoutPadding (Base64/getUrlEncoder))]
    (.nextBytes (SecureRandom.) bytes)
    (.encodeToString encoder bytes)))

(defn- sha256
  "Hash a string with SHA-256."
  [value]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (.digest digest (.getBytes value StandardCharsets/US_ASCII))))

(defn- encode-query
  "Encode a map as a query string."
  [params]
  (->> params
       (remove (comp nil? val))
       (map (fn [[k v]]
              (str (codec/url-encode (name k))
                   "="
                   (codec/url-encode (str v)))))
       (str/join "&")))

(defn- auth-flow
  "Build the auth flow state stored in the Ring session."
  []
  (let [verifier (random-token)]
    {:provider "pocket-id"
     :state (random-token)
     :nonce (random-token)
     :code-verifier verifier
     :code-challenge (.encodeToString (.withoutPadding (Base64/getUrlEncoder))
                                      (sha256 verifier))}))

(defn- discovery-url
  "Return the OIDC discovery URL for the issuer."
  [config]
  (endpoint (:issuer-url config) "/.well-known/openid-configuration"))

(defn discovery
  "Fetch the provider discovery document."
  [config]
  (-> (request :get (discovery-url config) config {})
      ensure-success))

(defn- authorization-url
  "Build the browser redirect URL for login."
  [config discovery-doc flow]
  (str (:authorization_endpoint discovery-doc)
       "?"
       (encode-query
        {:response_type "code"
         :client_id (:client-id config)
         :redirect_uri (:redirect-uri config)
         :scope (str/join " " (:scopes config))
         :state (:state flow)
         :nonce (:nonce flow)
         :code_challenge (:code-challenge flow)
         :code_challenge_method "S256"})))

(defn- token-response
  "Exchange the authorization code for tokens."
  [config discovery-doc code flow]
  (-> (request :post
               (:token_endpoint discovery-doc)
               config
               {:form-params {:grant_type "authorization_code"
                              :code code
                              :redirect_uri (:redirect-uri config)
                              :client_id (:client-id config)
                              :client_secret (:client-secret config)
                              :code_verifier (:code-verifier flow)}})
      ensure-success))

(defn- base64-url-decode
  "Decode a URL-safe base64 string."
  [value]
  (.decode (Base64/getUrlDecoder) value))

(defn- parse-jwt
  "Split a compact JWT into header, claims, and signature."
  [token]
  (let [[header payload signature :as parts] (str/split token #"\.")]
    (when-not (= 3 (count parts))
      (throw (ex-info "Pocket ID returned an invalid ID token."
                      {:token token})))
    {:header-json (String. (base64-url-decode header) StandardCharsets/UTF_8)
     :payload-json (String. (base64-url-decode payload) StandardCharsets/UTF_8)
     :header-segment header
     :payload-segment payload
     :signature-bytes (base64-url-decode signature)}))

(defn- rsa-public-key
  "Build an RSA public key from JWK parameters."
  [{:strs [n e]}]
  (let [modulus (BigInteger. 1 (base64-url-decode n))
        exponent (BigInteger. 1 (base64-url-decode e))
        spec (RSAPublicKeySpec. modulus exponent)]
    (.generatePublic (KeyFactory/getInstance "RSA") spec)))

(defn- verify-rs256
  "Verify an RS256 signature."
  [jwt jwk]
  (let [signature (doto (Signature/getInstance "SHA256withRSA")
                    (.initVerify ^RSAPublicKey (rsa-public-key jwk))
                    (.update (.getBytes (str (:header-segment jwt)
                                             "."
                                             (:payload-segment jwt))
                                        StandardCharsets/US_ASCII)))]
    (.verify signature (:signature-bytes jwt))))

(defn- matching-jwk
  "Return the JWK referenced by the JWT header."
  [jwks header]
  (let [kid (get header "kid")
        candidates (:keys jwks)]
    (or (some #(when (= kid (get % "kid")) %) candidates)
        (first candidates))))

(defn jwks
  "Fetch the provider JWKS."
  [config discovery-doc]
  (-> (request :get (:jwks_uri discovery-doc) config {})
      ensure-success))

(defn- current-epoch-seconds
  "Return the current time in epoch seconds."
  []
  (.getEpochSecond (Instant/now)))

(defn- validate-claims
  "Validate ID token claims against config and the auth flow."
  [config claims flow]
  (let [aud (:aud claims)
        audiences (if (sequential? aud) aud [aud])]
    (cond
      (not= (:issuer-url config) (:iss claims))
      (f/fail "Pocket ID issuer mismatch.")

      (not (some #(= (:client-id config) %) audiences))
      (f/fail "Pocket ID audience mismatch.")

      (not= (:nonce flow) (:nonce claims))
      (f/fail "Pocket ID nonce mismatch.")

      (<= (or (:exp claims) 0) (current-epoch-seconds))
      (f/fail "Pocket ID ID token has expired.")

      :else
      claims)))

(defn- userinfo
  "Fetch userinfo when claims are not sufficient in the ID token."
  [config discovery-doc access-token]
  (if-let [userinfo-endpoint (:userinfo_endpoint discovery-doc)]
    (-> (request :get
                 userinfo-endpoint
                 config
                 {:headers {"Authorization" (str "Bearer " access-token)}})
        ensure-success)
    {}))

(defn- normalize-session-user
  "Convert OIDC claims into the Agiladmin session user map."
  [config claims]
  (let [groups (or (:groups claims) [])
        email (:email claims)
        name (or (:name claims)
                 (:preferred_username claims)
                 email)]
    (cond
      (str/blank? email)
      (f/fail "Pocket ID did not return an email address.")

      :else
      {:id (:sub claims)
       :email email
       :name name
       :role (auth-user/role-from-groups groups config)
       :other-names []
       :verified (not (false? (:email_verified claims)))})))

(defn begin-login
  "Start the Pocket ID login flow and return a redirect response."
  [config request]
  (let [flow (auth-flow)
        discovery-doc (discovery config)
        redirect-url (authorization-url config discovery-doc flow)
        session (assoc (:session request) :auth-flow flow)]
    {:status 302
     :headers {"Location" redirect-url}
     :session session
     :body ""}))

(defn complete-login
  "Complete the callback by validating state and the ID token."
  [config request]
  (let [flow (get-in request [:session :auth-flow])
        params (:params request)
        state (or (:state params) (get params "state"))
        code (or (:code params) (get params "code"))
        error (or (:error params) (get params "error"))]
    (cond
      error
      (f/fail (str "Pocket ID login failed: " error))

      (nil? flow)
      (f/fail "Pocket ID login flow is missing from the session.")

      (not= "pocket-id" (:provider flow))
      (f/fail "Pocket ID login flow is invalid.")

      (not= (:state flow) state)
      (f/fail "Pocket ID state mismatch.")

      (str/blank? code)
      (f/fail "Pocket ID callback is missing the authorization code.")

      :else
      (let [discovery-doc (discovery config)
            token-body (token-response config discovery-doc code flow)
            jwt (parse-jwt (:id_token token-body))
            header (json/parse-string (:header-json jwt))
            claims (json/parse-string (:payload-json jwt) true)
            _alg (when-not (= "RS256" (get header "alg"))
                   (throw (ex-info "Unsupported Pocket ID ID token algorithm."
                                   {:alg (get header "alg")})))
            jwk (matching-jwk (jwks config discovery-doc) header)
            _verified (when-not (verify-rs256 jwt jwk)
                        (throw (ex-info "Pocket ID ID token signature verification failed."
                                        {:kid (get header "kid")})))
            validated-claims (validate-claims config claims flow)
            merged-claims (merge validated-claims
                                 (when-let [access-token (:access_token token-body)]
                                   (userinfo config discovery-doc access-token)))]
        (normalize-session-user config merged-claims)))))

(defn healthy?
  "Return true when discovery succeeds."
  [config]
  (try
    (boolean (discovery config))
    (catch Exception _
      false)))

(defn backend
  "Return the Pocket ID auth backend."
  [config]
  {:kind :pocket-id
   :healthy? (fn []
               (healthy? config))
   :login-entry-response (fn [_request]
                           nil)
   :begin-login (fn [request]
                  (begin-login config request))
   :complete-login (fn [request]
                     (complete-login config request))})
