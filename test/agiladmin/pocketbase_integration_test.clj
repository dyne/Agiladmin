(ns agiladmin.pocketbase-integration-test
  (:require [agiladmin.auth.pocketbase :as pocketbase]
            [agiladmin.view-auth :as view-auth]
            [clj-http.client :as http]
            [clojure.string :as str]
            [midje.sweet :refer :all]))

(defn- enabled?
  []
  (#{"1" "true" "TRUE" "yes" "YES"} (System/getenv "AGILADMIN_PB_IT")))

(defn- require-env
  [name]
  (or (System/getenv name)
      (throw (ex-info (str "Missing env var: " name) {:env name}))))

(defn- trim-slash
  [value]
  (str/replace value #"/+$" ""))

(defn- pb-config
  []
  {:base-url (trim-slash (require-env "AGILADMIN_PB_BASE_URL"))
   :users-collection (or (System/getenv "AGILADMIN_PB_USERS_COLLECTION") "users")
   :superuser-email (require-env "AGILADMIN_PB_SUPERUSER_EMAIL")
   :superuser-password (require-env "AGILADMIN_PB_SUPERUSER_PASSWORD")})

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
  (-> (request :post
               (str (:base-url config) "/api/collections/_superusers/auth-with-password")
               {:content-type :json
                :form-params {:identity (:superuser-email config)
                              :password (:superuser-password config)}})
      ensure-success
      :token))

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

(fact "Live PocketBase login refreshes the admin flag into the session"
      (if-not (enabled?)
        true => true
        (let [config (pb-config)
              email (or (System/getenv "AGILADMIN_PB_IT_USER_EMAIL")
                        "agiladmin-it@example.org")
              password (or (System/getenv "AGILADMIN_PB_IT_USER_PASSWORD")
                           "agiladmin-it-secret")
              name "Agiladmin Integration"
              _ (upsert-user! config {:email email
                                      :password password
                                      :name name
                                      :admin false})
              non-admin (pocketbase/sign-in config email password {})
              _ (upsert-user! config {:email email
                                      :password password
                                      :name name
                                      :admin true})
              admin-user (pocketbase/sign-in config email password {})
              login-response (with-redefs [agiladmin.view-auth/config {:agiladmin {:pocketbase config}}]
                               (view-auth/login-post {:params {:email email
                                                               :password password}
                                                      :remote-addr "127.0.0.1"}))]
          (:admin non-admin) => false
          (:admin admin-user) => true
          (get-in login-response [:session :auth :admin]) => true)))
