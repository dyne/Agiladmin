(ns agiladmin.handlers-test
  (:require [agiladmin.handlers :as handlers]
            [failjure.core]
            [midje.sweet :refer :all]
            [ring.mock.request :as mock]))

(def admin-config
  {:agiladmin {:budgets {:path "test/assets/"}}})

(def user-session
  {:config admin-config
   :auth {:email "user@example.org"
          :name "User Name"
          :role nil}})

(def admin-session
  {:config admin-config
   :auth {:email "admin@example.org"
          :name "Admin User"
          :role "admin"}})

(fact "Root route renders the bundled readme"
      (let [response (handlers/app-routes (mock/request :get "/"))]
        (:status response) => 200
        (:body response) => (contains "Welcome to Agiladmin!")))

(fact "Protected timesheet route falls back to the login form for guests"
      (let [response (handlers/app-routes (mock/request :get "/timesheets"))]
        (:status response) => 200
        (:body response) => (contains "Login into Agiladmin")))

(fact "Protected config route falls back to the login form for guests"
      (let [response (handlers/app-routes (mock/request :get "/config"))]
        (:status response) => 200
        (:body response) => (contains "Login into Agiladmin")))

(fact "Personnel list route sends non-admin users to their own person page"
      (let [calls (atom [])]
        (with-redefs [agiladmin.utils/now (fn [] {:year 2026})
                      agiladmin.view-person/list-person
                      (fn [config account person year]
                        (swap! calls conj [config account person year])
                        {:status 200 :body "person page"})]
          (let [response (handlers/app-routes
                          (assoc (mock/request :get "/persons/list")
                                 :session user-session))]
            (:status response) => 200
            (:body response) => "person page"
            @calls => [[admin-config
                        {:email "user@example.org"
                         :name "User Name"
                         :role nil}
                        "User Name"
                        2026]]))))

(fact "Personnel list route sends admins to the admin listing"
      (let [calls (atom [])]
        (with-redefs [agiladmin.view-person/list-all
                      (fn [request config account]
                        (swap! calls conj [config account])
                        {:status 200 :body "all persons"})]
          (let [response (handlers/app-routes
                          (assoc (mock/request :get "/persons/list")
                                 :session admin-session))]
            (:status response) => 200
            (:body response) => "all persons"
            @calls => [[admin-config
                        {:email "admin@example.org"
                         :name "Admin User"
                         :role "admin"}]]))))

(fact "Unknown routes return the not found page"
      (let [response (handlers/app-routes (mock/request :get "/missing"))]
        (:status response) => 404
        (:body response) => (contains "Page Not Found")))

(fact "Login post routes credentials to the auth view"
      (let [calls (atom [])]
        (with-redefs [agiladmin.view-auth/login-post
                      (fn [request]
                        (swap! calls conj (select-keys (:params request) [:email :password]))
                        {:status 200 :body "logged in"})]
          (let [response (handlers/app-routes
                          (assoc (mock/request :post "/login")
                                 :params {:email "user@example.org"
                                          :password "secret"}))]
            (:status response) => 200
            (:body response) => "logged in"
            @calls => [{:email "user@example.org"
                        :password "secret"}]))))

(fact "Signup post routes submitted fields to the auth view"
      (let [calls (atom [])]
        (with-redefs [agiladmin.view-auth/signup-post
                      (fn [request]
                        (swap! calls conj (select-keys (:params request) [:name :email]))
                        {:status 200 :body "signed up"})]
          (let [response (handlers/app-routes
                          (assoc (mock/request :post "/signup")
                                 :params {:name "User Name"
                                          :email "user@example.org"
                                          :password "secret"
                                          :repeat-password "secret"}))]
            (:status response) => 200
            (:body response) => "signed up"
            @calls => [{:name "User Name"
                        :email "user@example.org"}]))))

(fact "Logout get routes to the auth view"
      (with-redefs [agiladmin.view-auth/logout-get
                    (fn [_]
                      {:status 200 :body "logged out"})]
        (let [response (handlers/app-routes (mock/request :get "/logout"))]
          (:status response) => 200
          (:body response) => "logged out")))

(fact "Timesheet upload route requires authentication"
      (let [response (handlers/app-routes
                      (assoc (mock/request :post "/timesheets/upload")
                             :params {}))]
        (:status response) => 200
        (:body response) => (contains "Login into Agiladmin")))

(fact "Timesheet upload route delegates to the upload view for authenticated users"
      (let [calls (atom [])]
        (with-redefs [agiladmin.view-timesheet/upload
                      (fn [request config account]
                        (swap! calls conj [config account])
                        {:status 200 :body "uploaded"})]
          (let [response (handlers/app-routes
                          (assoc (mock/request :post "/timesheets/upload")
                                 :params {}
                                 :session admin-session))]
            (:status response) => 200
            (:body response) => "uploaded"
            @calls => [[admin-config
                        {:email "admin@example.org"
                         :name "Admin User"
                         :role "admin"}]]))))

(fact "Project route keeps the authenticated shell when project loading fails"
      (with-redefs [agiladmin.config/load-project
                    (fn [_ _]
                      (failjure.core/fail
                       "Project configuration file is missing, empty, or invalid YAML: budgets/CODE.yaml"))]
        (let [response (handlers/app-routes
                        (assoc (mock/request :post "/project")
                               :params {:project "CODE"}
                               :session admin-session))]
          (:status response) => 200
          (:body response) => (contains "Project configuration file is missing, empty, or invalid YAML: budgets/CODE.yaml")
          (:body response) => (contains "Personnel")
          (:body response) =not=> (contains "Login into Agiladmin"))))

(fact "Timesheet download returns the spreadsheet file when present"
      (with-redefs [clojure.java.io/as-file
                    (fn [_]
                      (proxy [java.io.File] ["test/assets/2026_timesheet_User.xlsx"]
                        (exists [] true)))
                    clojure.java.io/file
                    (fn [path]
                      path)]
        (let [response (handlers/app-routes
                        (assoc (mock/request :get "/timesheets/download/2026_timesheet_User.xlsx")
                               :session admin-session))]
          (get-in response [:headers "Content-Type"])
          => "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
          (:body response) => "test/assets/2026_timesheet_User.xlsx")))

(fact "Timesheet download reports a missing spreadsheet"
      (with-redefs [clojure.java.io/as-file
                    (fn [_]
                      (proxy [java.io.File] ["missing.xlsx"]
                        (exists [] false)))]
        (let [response (handlers/app-routes
                        (assoc (mock/request :get "/timesheets/download/missing.xlsx")
                               :session admin-session))]
          (:body response) => (contains "Where is this file gone?! test/assets/missing.xlsx"))))

(fact "Generic post root renders the passed cancellation message for authenticated users"
      (with-redefs [agiladmin.ring/config (atom admin-config)]
        (let [response (handlers/app-routes
                        (assoc (mock/request :post "/")
                               :params {:message "Operation canceled"}
                               :session admin-session))]
          (:status response) => 200
          (:body response) => (contains "Operation canceled"))))
