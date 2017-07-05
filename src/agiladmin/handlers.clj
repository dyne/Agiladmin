;; Copyright (C) 2015-2017 Dyne.org foundation

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

   [clj-jgit.porcelain :refer :all]
   [clj-jgit.querying  :refer :all]

   [incanter.core :refer :all]
   [incanter.stats :refer :all]
   [incanter.charts :refer :all]
   [incanter.datasets :refer :all]

   ;; ssh crypto
   [clj-openssh-keygen.core :refer :all]

   [agiladmin.core :refer :all]
   [agiladmin.utils :refer :all]
   [agiladmin.webpage :as web]
   [agiladmin.graphics :refer :all]
   [agiladmin.config :refer :all])
  (:import java.io.File)
  (:gen-class))

(defn readme [request]
  (conj {:session (web/check-session request)}
        (web/render
         (md/md-to-html-string
          (slurp (let [accept (:accept request)
                       readme "public/static/README-"
                       lang (:language accept)
                       locale (io/resource (str readme lang ".md"))]
                   (if (nil? locale) (io/resource "public/static/README.md")
                       locale)))))))

(defn button
  ([config url text] (button config url text [:p]))

  ([config url text field]
   (hf/form-to [:post url]
               field ;; can be an hidden key/value field (project, person, etc)
               (hf/submit-button {:class "btn btn-secondary btn-lg"} text))))

(defn select-person-month [config url text person]
  (hf/form-to [:post url]
              (hf/submit-button text)

              "Year:"  [:select "year" (hf/select-options (range 2016 2020))]
                                        ; "Month:" [:select "month" (hf/select-options (range 1 12))]
              (hf/hidden-field "person" person)
              ))

(defn project-log-view [config request]
  (let [repo (load-repo "budgets")]
    (web/render [:div {:class "row-fluid"}

                 [:div {:class "projects col-lg-4"}

                  [:h2 "Projects"]
                  ;; list all projects
                  (for [f (->> (list-files-matching "budgets" #"budget.*xlsx$")
                               (map #(.getName %)))]
                    [:div {:class "row log-project"}
                     [:div {:class "col-lg-4"}
                      (button config "/project" (proj-name-from-path f)
                              (hf/hidden-field "project" f))]])

                  [:h2 "People"]
                  ;; list all people
                  (for [f (->> (list-files-matching
                                "budgets" #".*_timesheet_.*xlsx$")
                               (map #(second
                                      (re-find regex-timesheet-to-name
                                               (.getName %)))) sort distinct)]
                                        ;                               (map #(.getName %)) distinct)]
                    [:div {:class "row log-person"}
                     [:div {:class "col-lg-4"}
                      (button config "/person" f
                              (list (hf/hidden-field "person" f)
                                    (hf/hidden-field "year" 2017)))]])
                  ]

                 [:div {:class "commitlog col-lg-6"}
                  (button config "/pull" (str "Pull updates from " (:git config)))
                  (present/edn->html
                   (->> (git-log repo)
                        (map #(commit-info repo %))
                        (map #(select-keys % [:author :message :time :changed_files]))))
                  ]])))


(defroutes app-routes
  (GET "/" request (readme request))
  (GET "/log" request
       (let [config (web/check-session request)]
         (conj {:session config}
               (cond
                 (.isDirectory (io/file "budgets"))
                 ;; renders the /log webpage into this call
                 (project-log-view config request)
                 (.exists (io/file "budgets")) (web/render-error config [:h1 "Invalid budgets directory."])
                 :else (web/render [:div "Budgets not yet imported"
                                    (button config "/import" "Import")
                                    (web/show-config config)])))))

  (GET "/config" request
       (let [config (web/check-session request)]
         (web/render
          (let [conf (merge default-settings config)]
            [:div

             [:div
              [:h2 "Configuration"]
              (if (false? (:config conf))
                [:div {:class "alert alert-warning"}
                 [:strong " Warning! "] "No config file found in
                            'config.json'. Generate one with your
                            values, example:"
                 [:pre "
{
    \"git\" : \"ssh://git@gogs.dyne.org/dyne/budgets\",
    \"ssh-key\" : \"id_rsa\"
}"]]
                ;;else
                (present/edn->html conf))]

               (if (not (.exists (io/as-file (:ssh-key conf))))
                 (let [kp (generate-key-pair)]
                   (write-key-pair kp (:ssh-key conf))))
               [:div
                [:h2 "SSH authentication keys"]
                [:div "Public: " [:pre (slurp (str (:ssh-key conf) ".pub"))]]]

             ]))))

  (POST "/pull" request
        (let [config (web/check-session request)
              repo (load-repo "budgets")]
          (with-identity {:name (slurp (:ssh-key config))
                          :private (slurp (:ssh-key config))
                          :public  (slurp (str (:ssh-key config) ".pub")
                          :passphrase "")
                          :exclusive true}
            (git-pull repo))
          (conj {:session config}
                (project-log-view config request))))

  (POST "/import" request
        (let [config (web/check-session request)]
          (conj {:session config}
                (web/render [:div
                             (with-identity {:name (slurp (:ssh-key config))
                                             :private (slurp (:ssh-key config))
                                             :public  (slurp (str (:ssh-key config) ".pub")
                                                             :passphrase "")
                                             :exclusive true}
                               (git-clone (:git config) "budgets"))]))))

  (POST "/project" request
        (let [config (web/check-session request)
              projfile (get-in request [:params :project])
              projname (proj-name-from-path projfile)
              project-hours (load-all-project-hours "budgets/" projname)]

          (write-workbook-sheet (str "budgets/" projfile) "Personnel hours"
                                ($order :month :asc project-hours))

          (web/render [:div
                       [:h1 projname]
                       [:div {:class "row-fluid"}

                        ;;;;; --- CHARTS

                        ;; time series
                        (with-data
                          (->> ($rollup :sum :hours :month project-hours)
                               ($order :month :asc))
                          [:div {:class "col-lg-6"}
                           (chart-to-image (time-series-plot
                                            (date-to-ts $data :month)
                                            ($ :hours)))])

                        ;; pie chart
                        (with-data ($rollup :sum :hours :name project-hours)
                          [:div {:class "col-lg-6"}
                           (chart-to-image
                            (pie-chart ($ :name)
                                       ($ :hours)
                                       :legend true
                                       :title (str projname " hours used")))])

                        [:div {:class "project-hours-usage"}
                         [:h2 "Project hours usage"]
                         (to-table ($order :month :desc project-hours))]

                        [:div [:h2 "State of budget repository"]
                         (present/edn->html
                          (-> (load-repo "budgets") git-status))]
                        ]])))

  (POST "/person" request
        (let [config (web/check-session request)
              person (get-in request [:params :person])
              year   (get-in request [:params :year])]

          (web/render [:div
                       [:h1 (dotname person)]
                       [:h2 year]

                       (let [ts (load-timesheet
                                 (str "budgets/" year
                                      "_timesheet_" person ".xlsx"))
                             rates (load-all-project-rates "budgets/")]

                         (for [m (-> (range 1 12) vec rseq)
                               :let [worked (get-billable-month rates ts year m)
                                     mtot (->> ($ :hours worked) wrap sum)]
                               :when (> mtot 0)]
                           [:div {:class "row month-total"}
                            [:h3 (month-name m) " total bill is "
                             [:strong (->> ($ :billable worked) wrap sum)]
                             " for "
                             (keep #(when (= (:month %) (str year '- m))
                                      (:hours %)) (:sheets ts))
                             " hours worked across "
                             (keep #(when (= (:month %) (str year '- m))
                                      (:days %)) (:sheets ts))
                             " days."]

                            [:div {:class "month-detail"}
                             (to-table worked)]]))

                       [:div {:class "col-lg-2"}
                        (button config "/person" "Previous year"
                                (list
                                 (hf/hidden-field "year" (dec (Integer. year)))
                                 (hf/hidden-field "person" person)))]])))


  ;; (POST "/invoice" request
  ;;       (let [config (web/check-session request)
  ;;             person (get-in request [:params :person])
  ;;             year   (get-in request [:params :year])
  ;;             month  (get-in request [:parans :month])]
  ;;         (web/render [:div
  ;;                      [:h1 person]



  (route/resources "/")
  (route/not-found "Not Found"))

(def app-defaults
  (-> site-defaults
      (assoc-in [:cookies] false)
      (assoc-in [:security :anti-forgery] false)
      (assoc-in [:security :ssl-redirect] false)
      (assoc-in [:security :hsts] true)))

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
  (println "Starting backend on http://localhost:6060")
  (run-jetty app {:port 6060
                  :host "localhost"
                  :join? false}))
(defn stop-backend [server] (.stop server))

(defn -main []
  (println "Starting standalone jetty server on http://localhost:6060")
  (run-jetty app {:port 6060
                  :host "localhost"
                  :join? true}))
