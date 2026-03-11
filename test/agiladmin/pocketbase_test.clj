(ns agiladmin.pocketbase-test
  (:require [agiladmin.auth.pocketbase :as pocketbase]
            [midje.sweet :refer :all]))

(def config {:base-url "http://127.0.0.1:8090/"
             :users-collection "users"
             :superuser-email "admin@example.org"
             :superuser-password "secret"})

(fact "PocketBase auth endpoints are built from config"
      (let [calls (atom [])]
        (with-redefs [clj-http.client/request
                      (fn [request]
                        (swap! calls conj request)
                        (case (:url request)
                          "http://127.0.0.1:8090/api/health"
                          {:status 200 :body {:code 200}}
                          "http://127.0.0.1:8090/api/collections/users/auth-with-password"
                          {:status 200
                           :body {:token "user-token"
                                  :record {:id "user-1"
                                           :email "user@example.org"
                                           :name "User Name"
                                           :verified true}}}
                          "http://127.0.0.1:8090/api/collections/users/auth-refresh"
                          {:status 200
                           :body {:token "refreshed-user-token"
                                  :record {:id "user-1"
                                           :email "user@example.org"
                                           :name "User Name"
                                           :admin true
                                           :verified true}}}
                          "http://127.0.0.1:8090/api/collections/users/records"
                          (if (= :post (:method request))
                            {:status 200
                             :body {:id "user-3"
                                    :email "user@example.org"
                                    :name "User Name"
                                    :admin false
                                    :verified false}}
                            {:status 200
                             :body {:items [{:id "user-2"
                                             :email "pending@example.org"
                                             :name "Pending User"
                                             :admin false
                                             :verified false}]}})
                          "http://127.0.0.1:8090/api/collections/users/confirm-verification"
                          {:status 200 :body {:token "verified-token"}}
                          "http://127.0.0.1:8090/api/collections/users/request-verification"
                          {:status 200 :body {:sent true}}
                          "http://127.0.0.1:8090/api/collections/_superusers/auth-with-password"
                          {:status 200 :body {:token "admin-token"}}))]
          (pocketbase/healthy? config) => true
          (pocketbase/sign-in config "user@example.org" "pw" {}) =>
          {:id "user-1"
           :email "user@example.org"
           :name "User Name"
           :admin true
           :other-names []
           :verified true}
          (pocketbase/sign-up config "User Name" "user@example.org" "pw" {} ["Alias"]) =>
          {:id "user-3"
           :email "user@example.org"
           :name "User Name"
           :admin false
           :other-names ["Alias"]
           :verified false}
          (pocketbase/confirm-verification config "token") => {:token "verified-token"}
          (pocketbase/request-verification config "user@example.org") => {:sent true}
          (pocketbase/list-pending-users config) =>
          [{:id "user-2"
            :email "pending@example.org"
            :name "Pending User"
            :admin false
            :other-names []
            :verified false}]
          (map #(select-keys % [:method :url :query-params :form-params :headers]) @calls) =>
          [{:method :get
            :url "http://127.0.0.1:8090/api/health"}
           {:method :post
            :url "http://127.0.0.1:8090/api/collections/users/auth-with-password"
            :form-params {:identity "user@example.org"
                          :password "pw"}}
           {:method :post
            :url "http://127.0.0.1:8090/api/collections/users/auth-refresh"
            :headers {"Authorization" "Bearer user-token"}}
           {:method :post
            :url "http://127.0.0.1:8090/api/collections/users/records"
            :form-params {:email "user@example.org"
                          :password "pw"
                          :passwordConfirm "pw"
                          :name "User Name"}}
           {:method :post
            :url "http://127.0.0.1:8090/api/collections/users/confirm-verification"
            :form-params {:token "token"}}
           {:method :post
            :url "http://127.0.0.1:8090/api/collections/users/request-verification"
            :form-params {:email "user@example.org"}}
           {:method :post
            :url "http://127.0.0.1:8090/api/collections/_superusers/auth-with-password"
            :form-params {:identity "admin@example.org"
                          :password "secret"}}
           {:method :get
            :url "http://127.0.0.1:8090/api/collections/users/records"
            :headers {"Authorization" "Bearer admin-token"}
            :query-params {"filter" "verified = false"
                           "sort" "email"
                           "perPage" 200}}])))

(fact "PocketBase sign-in falls back to the auth response when user lookup fails"
      (with-redefs [clj-http.client/request
                    (fn [request]
                      (case (:url request)
                        "http://127.0.0.1:8090/api/collections/users/auth-with-password"
                        {:status 200
                         :body {:token "user-token"
                                :record {:id "user-1"
                                         :email "user@example.org"
                                         :name "User Name"
                                         :admin false
                                         :verified true}}}
                        "http://127.0.0.1:8090/api/collections/users/auth-refresh"
                        {:status 403
                         :body {:message "Forbidden."}}))]
        (pocketbase/sign-in config "user@example.org" "pw" {}) =>
        {:id "user-1"
         :email "user@example.org"
         :name "User Name"
         :admin false
         :other-names []
         :verified true}))

(fact "PocketBase errors surface the API message"
      (with-redefs [clj-http.client/request
                    (fn [_]
                      {:status 400
                       :body {:message "Failed to authenticate."}})]
        (try
          (pocketbase/sign-in config "user@example.org" "pw" {})
          false => true
          (catch clojure.lang.ExceptionInfo ex
            (.getMessage ex) => "Failed to authenticate."))))
