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

   [taoensso.timbre :as log]

   ;; ssh crypto
   [clj-openssh-keygen.core :refer :all]

   [agiladmin.core :refer :all]
   [agiladmin.utils :refer :all]
   [agiladmin.views :as views]
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

(defn select-person-month [config url text person]
  (hf/form-to [:post url]
              (hf/submit-button text)

              "Year:"  [:select "year" (hf/select-options (range 2016 2020))]
                                        ; "Month:" [:select "month" (hf/select-options (range 1 12))]
              (hf/hidden-field "person" person)
              ))


(defroutes app-routes
  (GET "/" request

       (let [config (web/check-session request)
             conf (merge default-settings config)]

         (if (not (.exists (-> conf (get-in [:agiladmin :budgets :ssh-key]) io/as-file)))
           (let [kp (generate-key-pair)]
             (log/info "Generating SSH keypair...")
             (write-key-pair kp (:ssh-key conf))))

          (cond

            (false? (:config conf))
            (->> ["No config file found in 'config.json'. Generate one with your values, example:"
                  [:pre "
{
    \"git\" : \"ssh://git@gogs.dyne.org/dyne/budgets\",
    \"ssh-key\" : \"id_rsa\"
}"]]
                 (log/spy :error)
                 web/render-error web/render)

            :else (readme request))))

  (GET "/log" request
       (let [config (web/check-session request)]
         (conj {:session config}
               (cond
                 (.isDirectory (io/file "budgets"))
                 ;; renders the /log webpage into this call
                 (views/index-log-view config request)

                 (.exists (io/file "budgets"))
                 (web/render-error
                  config
                  [:h1 (log/spy :error "Invalid budgets directory.")])

                 :else (web/render [:div "Budgets not yet imported"
                                    (web/button config "/import" "Import")
                                    (web/show-config config)])))))

  (GET "/config" request
       (let [config (web/check-session request)]
         (web/render
          (let [conf (merge default-settings config)]
            [:div

             [:div
              [:h2 "Configuration"]
                (present/edn->html conf)]
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
                (views/index-log-view config request))))

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
        (if-let [config (web/check-session request)]
                 (views/project-view config request)))

  (POST "/person" request
        (if-let [config (web/check-session request)]
          (views/person-view config request)))


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
