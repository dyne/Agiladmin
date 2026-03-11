(ns agiladmin.pocketbase-integration-test
  (:require [agiladmin.auth.core :as auth]
            [agiladmin.auth.pocketbase :as pocketbase]
            [agiladmin.view-auth :as view-auth]
            [clj-http.client :as http]
            [clojure.string :as str]
            [midje.sweet :refer :all]))

(defn- enabled?
  []
  (#{"1" "true" "TRUE" "yes" "YES"} (System/getenv "AGILADMIN_PB_IT")))

(defn- require-env
  ([name]
   (require-env name nil))
  ([name default]
   (or (System/getenv name)
       default
       (throw (ex-info (str "Missing env var: " name) {:env name})))))

(defn- trim-slash
  [value]
  (str/replace value #"/+$" ""))

(defn- pb-config
  []
  {:base-url (trim-slash (require-env "AGILADMIN_PB_BASE_URL" "http://127.0.0.1:8090"))
   :users-collection (require-env "AGILADMIN_PB_USERS_COLLECTION" "users")
   :superuser-email (require-env "AGILADMIN_PB_SUPERUSER_EMAIL" "admin@example.org")
   :superuser-password (require-env "AGILADMIN_PB_SUPERUSER_PASSWORD" "change-me")})

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

(defn- collection-url
  [config suffix]
  (str (:base-url config) "/api/collections/" (:users-collection config) suffix))

(defn- superuser-token
  [config]
  (let [response (request :post
                          (str (:base-url config) "/api/collections/_superusers/auth-with-password")
                          {:content-type :json
                           :form-params {:identity (:superuser-email config)
                                         :password (:superuser-password config)}})]
    (-> (try
          (ensure-success response)
          (catch Exception e
            (throw (ex-info
                    (str "PocketBase superuser authentication failed. "
                         "Set AGILADMIN_PB_SUPERUSER_EMAIL and "
                         "AGILADMIN_PB_SUPERUSER_PASSWORD for your local instance.")
                    {:config (select-keys config [:base-url :users-collection :superuser-email])
                     :response (:response (ex-data e))}
                    e))))
        :token)))

(defn- auth-header
  [token]
  {"Authorization" (str "Bearer " token)})

(defn- pb-filter-quote
  [value]
  (str "'" (str/replace value "'" "\\'") "'"))

(defn- find-user-by-email
  [config token email]
  (let [body (-> (request :get
                          (collection-url config "/records")
                          {:headers (auth-header token)
                           :query-params {"filter" (str "email=" (pb-filter-quote email))
                                          "perPage" 1}})
                 ensure-success)
        items (:items body)]
    (first items)))

(defn- upsert-user!
  [config {:keys [email password name admin]}]
  (let [token (superuser-token config)
        payload {:email email
                 :password password
                 :passwordConfirm password
                 :name name
                 :admin admin
                 :verified true
                 :emailVisibility true}]
    (if-let [user (find-user-by-email config token email)]
      (-> (request :patch
                   (collection-url config (str "/records/" (:id user)))
                   {:headers (auth-header token)
                    :content-type :json
                    :form-params payload})
          ensure-success)
      (-> (request :post
                   (collection-url config "/records")
                   {:headers (auth-header token)
                    :content-type :json
                    :form-params payload})
          ensure-success))))

(defn- login-refreshes-admin?
  []
  (let [config (pb-config)
        email (or (System/getenv "AGILADMIN_PB_IT_USER_EMAIL")
                  "agiladmin-it@example.org")
        password (or (System/getenv "AGILADMIN_PB_IT_USER_PASSWORD")
                     "agiladmin-it-secret")
        name "Agiladmin Integration"
        previous-backend @auth/backend]
    (try
      (upsert-user! config {:email email
                            :password password
                            :name name
                            :admin false})
      (let [non-admin (pocketbase/sign-in config email password {})]
        (when-not (false? (:admin non-admin))
          (throw (ex-info "Expected first login to return admin=false."
                          {:user non-admin}))))
      (upsert-user! config {:email email
                            :password password
                            :name name
                            :admin true})
      (let [admin-user (pocketbase/sign-in config email password {})]
        (when-not (true? (:admin admin-user))
          (throw (ex-info "Expected second login to return admin=true."
                          {:user admin-user}))))
      (auth/init! (pocketbase/backend config))
      (let [login-response (with-redefs [agiladmin.view-auth/config {:agiladmin {:pocketbase config}}]
                             (view-auth/login-post {:params {:email email
                                                             :password password}
                                                    :remote-addr "127.0.0.1"}))]
        (true? (get-in login-response [:session :auth :admin])))
      (finally
        (auth/init! previous-backend)))))

(fact "Live PocketBase login refreshes the admin flag into the session"
      (if-not (enabled?)
        true
        (login-refreshes-admin?))
      => true)
