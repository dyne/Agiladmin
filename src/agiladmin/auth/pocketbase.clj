(ns agiladmin.auth.pocketbase
  (:require [clj-http.client :as http]
            [clojure.string :as str]))

(defn- trim-slash
  [value]
  (str/replace value #"/+$" ""))

(defn- endpoint
  [base-url path]
  (str (trim-slash base-url) path))

(defn- auth-collection-path
  [config suffix]
  (str "/api/collections/"
       (:users-collection config)
       suffix))

(defn- session-user
  [record]
  {:id (:id record)
   :email (:email record)
   :name (:name record)
   :admin (true? (:admin record))
   :other-names []
   :verified (:verified record)})

(defn- request
  [method url options]
  (http/request
   (merge {:method method
           :url url
           :accept :json
           :as :json
           :coerce :always
           :throw-exceptions false}
          options)))

(defn- ensure-success
  [response]
  (if (<= 200 (:status response) 299)
    (:body response)
    (throw (ex-info (or (get-in response [:body :message])
                        "PocketBase request failed.")
                    {:response response}))))

(defn healthy?
  [config]
  (let [response (request :get
                          (endpoint (:base-url config) "/api/health")
                          {})]
    (<= 200 (:status response) 299)))

(defn superuser-token
  [config]
  (-> (request :post
               (endpoint (:base-url config) "/api/collections/_superusers/auth-with-password")
               {:content-type :json
                :form-params {:identity (:superuser-email config)
                              :password (:superuser-password config)}})
      ensure-success
      :token))

(defn sign-in
  [config username password _options]
  (-> (request :post
               (endpoint (:base-url config)
                         (auth-collection-path config "/auth-with-password"))
               {:content-type :json
                :form-params {:identity username
                              :password password}})
      ensure-success
      :record
      session-user))

(defn sign-up
  [config name email password _options other-names]
  (let [record (-> (request :post
                            (endpoint (:base-url config)
                                      (auth-collection-path config "/records"))
                            {:content-type :json
                             :form-params {:email email
                                           :password password
                                           :passwordConfirm password
                                           :name name}})
                   ensure-success)]
    (assoc (session-user record)
           :other-names other-names)))

(defn confirm-verification
  [config token]
  (-> (request :post
               (endpoint (:base-url config)
                         (auth-collection-path config "/confirm-verification"))
               {:content-type :json
                :form-params {:token token}})
      ensure-success))

(defn request-verification
  [config email]
  (-> (request :post
               (endpoint (:base-url config)
                         (auth-collection-path config "/request-verification"))
               {:content-type :json
                :form-params {:email email}})
      ensure-success))

(defn list-pending-users
  [config]
  (let [token (superuser-token config)
        response (request :get
                          (endpoint (:base-url config)
                                    (auth-collection-path config "/records"))
                          {:headers {"Authorization" (str "Bearer " token)}
                           :query-params {"filter" "verified = false"
                                          "sort" "email"
                                          "perPage" 200}})]
    (mapv session-user (:items (ensure-success response)))))

(defn backend
  [config]
  {:healthy? (fn [] (healthy? config))
   :sign-in (fn [username password options]
              (sign-in config username password options))
   :sign-up (fn [name email password options other-names]
              (sign-up config name email password options other-names))
   :confirm-verification (fn [_email token]
                           (confirm-verification config token))
   :request-verification (fn [email]
                           (request-verification config email))
   :list-pending-users (fn []
                         (list-pending-users config))})
