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
   [ring.middleware.accept :refer [wrap-accept]]
   [ring.middleware.defaults :refer [wrap-defaults site-defaults]]

   [hiccup.form :as hf :refer [hidden-field]]

   [me.raynes.fs :as fs :refer [base-name]]
   [failjure.core :as f]

   [clj-jgit.porcelain :as git]

   [incanter.core :refer :all]

   [taoensso.timbre :as log]


   [just-auth.core :as auth]
   [agiladmin.core :refer :all]
   [agiladmin.config :as conf]
   [agiladmin.ring :as ring]
   [agiladmin.graphics :refer [to-table]]
   [agiladmin.utils :as util]
   [agiladmin.view-project :as view-project]
   [agiladmin.view-timesheet :as view-timesheet]
   [agiladmin.view-reload :as view-reload]
   [agiladmin.view-person :as view-person]
   [agiladmin.webpage :as web]
   [agiladmin.session :as s])
  (:import java.io.File)
  (:gen-class))

(defonce config (conf/load-config "agiladmin" conf/default-settings))

(defroutes app-routes

  (GET "/" request (web/render web/readme))

  ;; login / logout
  (GET "/login" request
       (f/attempt-all
        [acct (s/check-account request)]
        (web/render acct
                    [:div
                     [:h1 (str "Already logged in with account: "
                               (:email acct))]
                     (web/button "/logout" "Logout")])
        (f/when-failed [e]
          (web/render web/login-form))))

  (POST "/login" request
        (f/attempt-all
         [username (s/param request :username)
          password (s/param request :password)
          logged (auth/sign-in
                  @ring/auth username password)]
         (let [session {:session {:config config
                                  :auth logged}}]
           (conj session
                 (web/render
                  logged
                  [:div
                   [:h1 "Logged in: " username]
                   (web/render-yaml session)])))
         (f/when-failed [e]
           (web/render-error-page
            (str "Login failed: " (f/message e))))))
  (GET "/session" request
       (-> (:session request) web/render-yaml web/render))
  (GET "/logout" request
       (conj {:session {:config config}}
             (web/render [:h1 "Logged out."])))

  (GET "/signin" request
       (web/render web/signin-form))
  (POST "/signin" request
        (f/attempt-all
         [name (s/param request :name)
          email (s/param request :email)
          password (s/param request :password)
          activation {:activation-uri
                      (get-in request [:headers "host"])}]
         (web/render
          [:div
           (if-not (auth/exists? @ring/auth email)
             (f/if-let-failed?
                 [signup (auth/sign-up @ring/auth name email
                                       password activation nil)]
               (web/render-error
                (str "Failure creating account: "
                     (f/message signup))))
             [:p (str "Account created: "
                      name " &lt;" email "&gt;")])
           (f/if-let-failed?
               [sent  (auth/send-activation-message
                       @ring/auth email activation)]
             (web/render-error
              (str "Failure sending activation email - "
                   (f/message sent)))
             [:p "Pending activation..."])
           [:h1 (str "Confirmation email sent - " email)]])
         (f/when-failed [e]
           (web/render-error-page
            (str "Sign-in failure: " (f/message e))))))

  (GET "/activate/:email/:activation-id"
       [email activation-id :as request]
       (let [activation-uri
             (str "http://"
                  (get-in request [:headers "host"])
                  "/activate/" email "/" activation-id)]
         (web/render
          [:div
           (f/if-let-failed?
               [act (auth/activate-account
                     @ring/auth email
                     {:activation-link activation-uri})]
             (web/render-error
              [:div
               [:h1 "Failure activating account"]
               [:h2 (f/message act)]
               [:p (str "Email: " email " activation-id: " activation-id)]])
             [:h1 (str "Account activated - " email)])])))

  (POST "/" request
        ;; generic endpoint for canceled operations
        (web/render (s/check-account request)
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
            [:div {:class "row-fluid"}
             [:h1 "Configuration"
              [:a {:href "/config/edit"}
               [:button {:class "btn btn-info"} "Edit"]]]
             (web/render-yaml (:session req))]]))
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
       (->> view-person/list-all
            (s/check request)))
  (POST "/persons/spreadsheet" request
        (->> view-person/download
         (s/check request)))

  (GET "/timesheets" request
       (->>
        (fn [req conf acct]
          (view-timesheet/start))
        (s/check request)))
  (POST "/timesheets/cancel" request
        (->>
         (fn [req conf acct]
           (let [tempfile (s/param req :tempfile)]
             (if-not (str/blank? tempfile) (io/delete-file tempfile))
             (web/render
              acct
              [:div {:class (str "alert alert-danger") :role "alert"}
               (str "Canceled upload of timesheet: " tempfile)])))
         (s/check request)))
  (POST "/timesheets/upload" request
        (->>
         (fn [request config acct]
           (f/attempt-all
            [tempfile (s/param request [:file :tempfile])
             filename (s/param request [:file :filename])
             params   (:params request)]
            (cond
              (> (get-in params [:file :size]) 500000)
              ;; max upload size in bytes
              ;; TODO: put in config
              (web/render-error-page params "File too big in upload.")
              :else
              (let [file (io/copy tempfile (io/file "/tmp" filename))
                    path (str "/tmp/" filename)]
                (io/delete-file tempfile)
                (view-timesheet/upload config path)))
            (f/when-failed [e]
              (web/render-error-page (f/message e)))))
         (s/check request)))

  (GET "/timesheets/download/:path" [path :as request]
       (->>
        (fn [req conf acct]
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

  (POST "/timesheets/submit" request
        (->>
         (fn [req conf acct]
           (let [path (s/param req :path)]
             (if (.exists (io/file path))
               (let [repo (conf/q conf [:agiladmin :budgets :path])
                     dst (str repo (fs/base-name path))]
                 (web/render
                  acct
                  [:div {:class "container-fluid"}
                   [:h1 dst ]
                   (io/copy (io/file path) (io/file dst))
                   (io/delete-file path)
                   (f/attempt-all
                    [gitrepo  (git/load-repo repo)
                     dircache (git/git-add gitrepo (fs/base-name dst))
                     gitstatus (git/git-status gitrepo)
                     gitcommit (git/git-commit
                                gitrepo
                             (str "Updated timesheet "
                                  (fs/base-name path))
                             ;; {"Agiladmin" "agiladmin@dyne.org"}
                             )]
                    [:div
                     (web/render-yaml gitstatus)
                     [:p "Timesheet was succesfully archived"]
                     (web/render-git-log gitrepo)]
                    ;; TODO: add link to the person page here
                    (f/when-failed [e]
                      (web/render-error
                       (log/spy :error ["Failure committing to git: " e]))))]))
               ;; else
               (web/render-error-page
                (str "Where is this file gone?! " path)))))
         (s/check request)))

  (GET "/reload" request
       (->>
        (fn [req conf acct]
          (view-reload/start conf))
        (s/check request)))

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
      (wrap-session)))

;; for uberjar (TODO: align with configuration)
(defn -main []
  (println "Starting standalone jetty server on http://localhost:6060")
  (run-jetty app {:port 6060
                  :host "localhost"
                  :join? true}))
