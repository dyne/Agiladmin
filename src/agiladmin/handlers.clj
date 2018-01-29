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

   [hiccup.page :as page]
   [hiccup.form :as hf]
   [hiccup.element :as ele]
   [hiccup.middleware :refer [wrap-base-url]]
   [json-html.core :as present]
   [markdown.core :as md]
   [yaml.core :as yaml]

   [me.raynes.fs :as fs]
   [failjure.core :as f]

   [agiladmin.config :as conf]
   [agiladmin.graphics :refer [to-table]]

   [clj-jgit.porcelain :as git
    :refer [with-identity load-repo git-clone git-pull]]

   [incanter.core :refer :all]

   [taoensso.timbre :as log]
   [taoensso.nippy :as nippy]

   ;; ssh crypto
   [clj-openssh-keygen.core :refer :all]

   [agiladmin.core :refer :all]
   [agiladmin.utils :as util]
   [agiladmin.views :as views]
   [agiladmin.view-timesheet :as view-timesheet]
   [agiladmin.webpage :as web]
   [agiladmin.graphics :refer :all]
   [agiladmin.config :refer :all])
  (:import java.io.File)
  (:gen-class))

(defn readme [request]
  (conj {:session (web/check-session request)}
        (web/render
         [:div {:class "container-fluid"}
         (slurp (let [accept (:accept request)
                      readme "public/static/README-"
                      lang (:language accept)
                      locale (io/resource (str readme lang ".html"))]
                  (if (nil? locale) (io/resource "public/static/README.html")
                      locale)))])))

(defn select-person-month [config url text person]
  (hf/form-to [:post url]
              (hf/submit-button text)

              "Year:"  [:select "year" (hf/select-options (range 2016 2020))]
                                        ; "Month:" [:select "month" (hf/select-options (range 1 12))]
              (hf/hidden-field "person" person)
              ))


(defroutes app-routes

  (GET "/" request
       (let [config  (web/check-session request)
             conf    (merge default-settings config)
             keypath (get-in conf [:agiladmin :budgets :ssh-key])]
         (if-not (.exists (io/as-file keypath))
           (let [kp (generate-key-pair)]
             (log/info "Generating SSH keypair...")
             (clojure.pprint/pprint kp)
             (write-key-pair kp keypath)))
         (cond
           (false? (:config conf))
           (->> ["No config file found. Generate one with your values2, example:"
                 [:pre "
{
    \"git\" : \"ssh://git@gogs.dyne.org/dyne/budgets\",
    \"ssh-key\" : \"id_rsa\"
}"]]
                (log/spy :error)
                web/render-error-page)
           :else (readme request))))

  (POST "/" request
        ;; generic endpoint for canceled operations
        (let [config (web/check-session request)]
          (web/render
           [:div {:class (str "alert alert-danger") :role "alert"}
            (get-in request [:params :message])])))

  (GET "/config" request
       (let [config (web/check-session request)]
         (web/render
          (let [conf (merge default-settings config)]
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
              (web/render-yaml conf)]]))))

  (GET "/config/edit" request
       (let [config (web/check-session request)]
         (web/render
          (let [conf (merge default-settings config)]
            [:div {:class "container-fluid"}
             [:form {:action "/config/edit"
                     :method "post"}
              [:h1 "Configuration editor"]
              (web/edit-edn conf)]]))))

  (POST "/config/edit" request
        (if-let [config (web/check-session request)]
          (web/render
           [:div {:class "container-fluid"}
            [:h1 "Saving configuration"]
            (web/highlight-yaml (get-in request [:params :editor]))])))
  ;; TODO: validate and save
  ;; also visualise diff: https://github.com/benjamine/jsondiffpatch

  (POST "/project" request
        (if-let [config (web/check-session request)]
          (views/project-view config request)))

  (POST "/person" request
        (if-let [config (web/check-session request)]
          (let [person (get-in request [:params :person])
                year   (get-in request [:params :year])
                ts-path (get-in config [:agiladmin :budgets :path])]
            ;; check if current year's timesheet exists, else point to previous
            (if (.exists (io/as-file (str ts-path year "_timesheet_" person ".xlsx")))
              (views/person-view config request)
              ;; else
              (web/render
               [:div {:class "container-fluid"}
                (web/render-error
                 (log/spy
                  :warn
                  (str "No timesheet found for " person " on year " year)))
                (if-let [ymn (- (Integer/parseInt (re-find #"\A-?\d+" year)) 1)]
                  (web/button "/person" (str "Try previous year " ymn)
                              (list (hf/hidden-field "person" person)
                                    (hf/hidden-field "year" ymn))))])))))

  ;;TODO: NEW API
  (GET "/persons/list" request
       (let [config (web/check-session request)]
         (web/render [:div {:class "container-fluid"}
                      (views/persons-list config)])))

  (POST "/persons/spreadsheet" request
        (let [config (web/check-session request)
              format (get-in request [:params :format2])
              costs-json (get-in request [:params :costs])]
          (cond
            (= "excel" (get-in request [:params :format1]))
            (web/render "TODO")

            (= "json"  (get-in request [:params :format2]))
            {:headers {"Content-Type"
                       "text/json; charset=utf-8"}
             :body costs-json} ;; its already a json

            (= "csv"   (get-in request [:params :format3]))
            (-> costs-json json/read-str web/download-csv)

            (= "html"   (get-in request [:params :format4]))
            (-> costs-json json/read-str web/render-html web/render)

            )))

  (GET "/projects/list" request
       (let [config (web/check-session request)]
         (web/render [:div {:class "container-fluid"}
                      (views/projects-list config)])))

  (POST "/projects/edit" request
        (if-let [config (web/check-session request)]
          (views/project-edit config request)))

  (GET "/timesheets" request
       (let [config (web/check-session request)]
         (view-timesheet/start)))
  (POST "/timesheets/cancel" request
       (let [config (web/check-session request)
             tempfile (get-in request [:params :tempfile])]
         (if-not (str/blank? tempfile) (io/delete-file tempfile))         
         (web/render
          [:div {:class (str "alert alert-danger") :role "alert"}
           (str "Canceled upload of timesheet: " tempfile)])))

  (POST "/timesheets/upload" request
        (let [config (web/check-session request)
              tempfile (get-in request [:params :file :tempfile])
              filename (get-in request [:params :file :filename])
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
              (view-timesheet/upload config path)))))

  (POST "/timesheets/submit" request
        (let [config (web/check-session request)
              path (get-in request [:params :path])]
          (if (.exists (io/file path))
            (let [repo (conf/q config [:agiladmin :budgets :path])
                  dst (str repo (fs/base-name path))]
              (web/render
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

  (GET "/home" request
       (let [config (web/check-session request)
             path (io/file (get-in config [:agiladmin :budgets :path]))]
         (conj {:session config}
               (web/render [:div {:class "container-fluid"}
                            [:div {:class "col-lg-4"}
                             (views/persons-list config)]
                            [:div {:class "col-lg-8"}
                             (views/projects-list config)]]))))

  (GET "/error" request
       (let [config (web/check-session request)]
         (web/render-error-page request "Testing the error message")))

  (GET "/reload" request
       (let [config (web/check-session request)
             budgets (conf/q config [:agiladmin :budgets])
             keypath (:ssh-key budgets)
             gitpath (:git budgets)
             path (io/file (:path budgets))]
         ;; overwrite existing config
         (conj {:session (conf/load-config "agiladmin" conf/default-settings)}
               (cond
                 (.isDirectory path)
                 ;; the directory exists (we assume also is a correct
                 ;; one) TODO: analyse contents of path, detect git
                 ;; repo and correct agiladmin environment, detect
                 ;; errors and report them
                 (let [repo (try (git/load-repo (:path budgets))
                                 (catch Exception ex
                                   (log/error [:p "Error in git/load-repo: " ex])))]
                   (log/info
                    (str "Path is a directory, trying to pull in: "
                         (:path budgets)))

                   (git/with-identity {:name (slurp (:ssh-key budgets))
                                       ;;:private (slurp (:ssh-key budgets))
                                       ;; :public  (slurp (str (:ssh-key budgets) ".pub"))
                                       :passphrase ""
                                       :exclusive true})
                   (web/render
                    [:div {:class "container-fluid"}
                     (try (git/git-pull repo)
                          (catch Exception ex
                            (web/render-error
                             (log/spy :error [:p "Error in git-pull: " ex]))))

                     [:div [:h1 "Git status"]
                      (web/render-yaml (git/git-status repo))]
                     [:div [:h1 "Log (last 20 changes)"]
                      (web/render-git-log repo)]
                     [:div [:h1 "Config"] (web/render-yaml config)]
                     ]))


                 (.exists path)
                 ;; exists but is not a directory
                 (web/render-error-page
                  config
                  (log/spy
                   :error
                   (str "Invalid budgets directory: " (:path budgets))))

                 :else
                 ;; doesn't exists at all
                 (web/render
                  [:div {:class "container-fluid"}
                   (git/with-identity {:name (slurp keypath)
                                       ;; :private (slurp keypath)
                                       ;; :public  (slurp (str keypath ".pub")
                                       :passphrase ""
                                       :exclusive true}

                     (try (git/git-clone (:path budgets) "budgets")
                          (catch Exception ex
                            (web/render-error
                             (log/spy
                              :error
                              [:p "Error cloning git repo" ex
                               "Add your public key to the repository to access it:"
                               (-> (str keypath ".pub") slurp str)]))))

                     (let [repo (git/load-repo (:path budgets))]
                       [:div {:class "row"}
                        [:div {:class "col-lg-4"}
                         (web/render-yaml config)]
                        [:div {:class "col-lg-8"}
                         (web/render-git-log repo)]]))])
                 ;; end of POST /reload
                 ))))

  (route/resources "/")
  (route/not-found (web/render-error-page "Page Not Found"))

  ;; end of routes
  )


(log/merge-config! {:level :debug
                    ;; #{:trace :debug :info :warn :error :fatal :report}

                    ;; Control log filtering by
                    ;; namespaces/patterns. Useful for turning off
                    ;; logging in noisy libraries, etc.:
                    :ns-whitelist  ["agiladmin.*"]
                    :ns-blacklist  ["org.eclipse.jetty.*"]})

(def app-defaults
  (let [config (conf/load-config "agiladmin" {})]
    (-> site-defaults
        (assoc-in [:cookies] true)
        (assoc-in [:security :anti-forgery]
                  (get-in config [:webserver :anti-forgery]))
        (assoc-in [:security :ssl-redirect]
                  (get-in config [:webserver :ssl-redirect]))
        (assoc-in [:security :hsts] true))))

(def app
  (-> (wrap-defaults app-routes app-defaults)
      (wrap-session)
      (wrap-accept {:mime ["text/html"]
                    ;; preference in language, fallback to english
                    :language ["en" :qs 0.5
                               "it" :qs 1
                               "nl" :qs 1
                               "hr" :qs 1]})))
(defn start-backend []
  (let [config (conf/load-config "agiladmin" {})]
    (log/info "Starting backend")
    (run-jetty app {})))

(defn stop-backend [server] (.stop server))

(defn -main []
  (println "Starting standalone jetty server on http://localhost:6060")
  (run-jetty app {:port 6060
                  :host "localhost"
                  :join? true}))
