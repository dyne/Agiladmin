(ns agiladmin.handlers-test
  (:require [agiladmin.handlers :as handlers]
            [midje.sweet :refer :all]
            [ring.mock.request :as mock]))

(def admin-config
  {:agiladmin {:admins ["admin@example.org"]
               :budgets {:path "test/assets/"}}})

(def user-session
  {:config admin-config
   :auth {:email "user@example.org"
          :name "User Name"}})

(def admin-session
  {:config admin-config
   :auth {:email "admin@example.org"
          :name "Admin User"}})

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
                         :admin false}
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
                         :admin true}]]))))

(fact "Unknown routes return the not found page"
      (let [response (handlers/app-routes (mock/request :get "/missing"))]
        (:status response) => 404
        (:body response) => (contains "Page Not Found")))
