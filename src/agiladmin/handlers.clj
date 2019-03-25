;; Copyright (C) 2015-2018 Dyne.org foundation

;; Sourcecode designed, written and maintained by
;; Denis Roio <jaromil@dyne.org>

;; This program is free software: you can redistribute it and/or modify
;; it under the terms of the GNU Affero General Public License as published by
;; the Free Software Foundation, either version 3 of the License, or
;; (at your option) any later version.

;; This program is distributed in the hope that it will be useful,
;; but WITHOUT ANY WARRANTY; without even the implied warranty of
;; MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
;; GNU Affero General Public License for more details.

;; You should have received a copy of the GNU Affero General Public License
;; along with this program.  If not, see <http://www.gnu.org/licenses/>.

(ns agiladmin.handlers
  (:require
   [clojure.string :as str]
   [clojure.java.io :as io]
   [clojure.data.json :as json]
   [compojure.core :refer :all]
   [compojure.handler :refer :all]
   [compojure.route :as route]
   [compojure.response :as response]
   [ring.adapter.jetty :refer :all]

   [ring.middleware.session :refer :all]
   [ring.middleware.session.cookie :refer [cookie-store]]
   [ring.middleware.accept :refer [wrap-accept]]
   [ring.middleware.defaults :refer [wrap-defaults site-defaults]]

   [hiccup.form :as hf :refer [hidden-field]]

   [me.raynes.fs :as fs :refer [base-name]]
   [failjure.core :as f]

   [incanter.core :refer :all]

   [taoensso.timbre :as log]


   [just-auth.core :as auth]
   [agiladmin.core :refer :all]
   [agiladmin.ring :as ring]
   [agiladmin.graphics :refer [to-table]]
   [agiladmin.utils :as util]
   [agiladmin.view-project :as view-project]
   [agiladmin.view-timesheet :as view-timesheet]
   [agiladmin.view-reload :as view-reload]
   [agiladmin.view-person :as view-person]
   [agiladmin.view-auth :as view-auth]
   [agiladmin.webpage :as web]
   [agiladmin.session :as s])
  (:import java.io.File)
  (:gen-class))

(defroutes app-routes

  (GET "/" request (web/render web/readme))

  (GET "/projects/list" request
       (->> view-project/list-all
            (s/check request)))
  (POST "/project" request
        (->> view-project/start
             (s/check request)))
  (POST "/projects/edit" request
        (->> view-project/edit
             (s/check request)))

  (POST "/person" request
        (->> view-person/start
             (s/check request)))
  (GET "/persons/list" request
       (f/attempt-all [config (s/check-config request)
                       ;; this checks if user is logged in
                       account (s/check-account config request)]
         (cond
           ;; user is an admin, can list all personnel and activation requests
           (:admin account) (view-person/list-all request config account)
           ;; user is not an admin, redirect to own personnel page
           (-> account :admin not)
           (view-person/list-person config account (:name account) (:year (util/now)))
           :else
           (web/render [:div (web/render-error "Unauthorized access.")]))
         (f/when-failed [e]
           (web/render
            [:div
             (web/render-error "Unauthorized access.") ;; TODO: (f/message e)) reports all config?!
             web/login-form]))))
         ;; (web/render account [:div
         ;;                      (web/render-yaml account)
         ;;                      (web/render-yaml config)])))
       ;; (->> view-person/list-all
       ;;      (s/check request)))
  (POST "/persons/spreadsheet" request
        (->> view-person/download
             (s/check request)))
  (GET "/reload" request
       (->> view-reload/start
            (s/check request)))

  (GET "/timesheets" request
       (->> (fn [req conf acct]
              (web/render acct view-timesheet/upload-form))
            (s/check request)))
  (POST "/timesheets/cancel" request
        (->> view-timesheet/cancel
             (s/check request)))
  (GET "/timesheets/download/:path" [path :as request]
       (->> (fn [req conf acct]
              (let [budgets (get-in conf [:agiladmin :budgets :path])]
                (if (.exists (io/as-file (str budgets path )))
                  {:headers
                   {"Content-Type"
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"}
                   :body (io/file (str budgets path))}
                  (web/render-error-page
                   (str "Where is this file gone?! "
                        (str budgets path))))))
            (s/check request)))
  (POST "/timesheets/upload" request
        (->> view-timesheet/upload
             (s/check request)))

  (POST "/timesheets/submit" request
        (->> view-timesheet/commit
             (s/check request)))

  ;; login / logout
  (GET "/login" request (view-auth/login-get request))

  (POST "/login" request (view-auth/login-post request))

  ;; (GET "/session" request
  ;;      (-> (:session request) web/render-yaml web/render))

  (GET "/logout" request (view-auth/logout-get request))

  (GET "/signup" request (view-auth/signup-get request))

  (POST "/signup" request (view-auth/signup-post request))

  (GET "/activate/:email/:activation-id"   [email activation-id :as request]
       (view-auth/activate request email activation-id))

  (POST "/" request
        ;; generic endpoint for canceled operations
        (web/render (s/check-account @ring/config request)
                    [:div {:class (str "alert alert-danger") :role "alert"}
                     (s/param request :message)]))

  (GET "/config" request
       (->>
        (fn [req conf acct]
          (web/render
           acct
           [:div {:class "container-fluid"}
            [:div {:class "row-fluid"}
             [:h1 "SSH authentication keys"]
             [:div "Public: "
              [:pre
               (slurp
                (str
                 (get-in
                  conf
                  [:agiladmin :budgets :ssh-key]) ".pub"))]]]
            ;; [:div {:class "row-fluid"}
            ;;  [:h1 "Configuration"
            ;;   [:a {:href "/config/edit"}
            ;;    [:button {:class "btn btn-info"} "Edit"]]]
            ;;  (web/render-yaml (:session req))]
            ]))
        (s/check request)))

  (GET "/config/edit" request
       (->>
        (fn [req conf acct]
          (web/render
           acct
           [:div {:class "container-fluid"}
            [:form {:action "/config/edit"
                    :method "post"}
             [:h1 "Configuration editor"]
             (web/edit-edn conf)]]))
        (s/check request)))

  (POST "/config/edit" request
        (->>
         (fn [req conf acct]
           (web/render
            acct
            [:div {:class "container-fluid"}
             [:h1 "Saving configuration"]
             (web/highlight-yaml (get-in request [:params :editor]))]))
         (s/check request)))
  ;; TODO: validate and save
  ;; also visualise diff: https://github.com/benjamine/jsondiffpatch

  (route/resources "/")
  (route/not-found (web/render-error-page "Page Not Found"))

  ) ;; end of routes

(def app
  (-> (wrap-defaults app-routes ring/app-defaults)
      (wrap-accept {:mime ["text/html"]
                    ;; preference in language, fallback to english
                    :language ["en" :qs 0.5
                               "it" :qs 1
                               "nl" :qs 1
                               "hr" :qs 1]})
      (wrap-session
       {:store
        (cookie-store
         {:key (get-in @ring/config [:agiladmin :webserver :salt])})})))

;; for uberjar (TODO: align with configuration)
(defn -main []
  (println "Starting standalone jetty server on http://localhost:6060")
  (run-jetty app {:port 6060
                  :host "localhost"
                  :join? true}))
