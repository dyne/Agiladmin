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
                 web/render-error web/render)

            :else (readme request))))

  (GET "/log" request
       (let [config (web/check-session request)
             path (io/file (get-in config [:agiladmin :budgets :path]))]
         (conj {:session config}
               (cond
                 (.isDirectory path)
                 ;; renders the /log webpage into this call
                 (views/index-log-view config request)

                 (.exists path)
                 (web/render-error
                  config
                  [:h1 (log/spy :error "Invalid budgets directory.")])

                 :else (web/render [:div "Budgets not yet imported"
                                    (web/button "/import" "Import")
                                    (web/show-config config)])))))

  (GET "/config" request
       (let [config (web/check-session request)]
         (web/render
          (let [conf (merge default-settings config)]
            [:div {:class "container-fluid"}
             [:h2 "SSH authentication keys"]
             [:div "Public: "
              [:pre
               (slurp
                (str
                 (get-in
                  conf
                  [:agiladmin :budgets :ssh-key]) ".pub"))]]
             [:div {:class "row-fluid"}
              [:h2 "Configuration"]
                (present/edn->html (:agiladmin conf))]]))))

  (POST "/pull" request
        (let [config (web/check-session request)
              budgets (get-in config [:agiladmin :budgets])
              repo (load-repo (:path budgets))]
          (with-identity {:name (slurp (:ssh-key budgets))
                          :private (slurp (:ssh-key budgets))
                          :public  (slurp (str (:ssh-key budgets) ".pub")
                          :passphrase "")
                          :exclusive true}
            (git-pull repo))
          (conj {:session config}
                (views/index-log-view config request))))

  (POST "/import" request
        (let [config  (web/check-session request)
              keypath (get-in config [:agiladmin :budgets :ssh-key])
              gitpath (get-in config [:agiladmin :budgets :git])]
          (web/render
           [:div
            (with-identity {:name (slurp keypath)
                            :private (slurp keypath)
                            :public  (slurp (str keypath ".pub")
                                            :passphrase "")
                            :exclusive true}
              (try (git-clone gitpath "budgets")
                   (catch Exception ex
                     (log/error (str "Error: " ex))
                     [:div
                      [:h1 "Error cloning git repo"]
                      [:h2 ex]
                      [:p "Add your public key to the repository to access it:"
                       [:pre (-> (str keypath ".pub") slurp str)]]])))])))

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

(log/merge-config! {:level :debug
                    ;; #{:trace :debug :info :warn :error :fatal :report}

                    ;; Control log filtering by
                    ;; namespaces/patterns. Useful for turning off
                    ;; logging in noisy libraries, etc.:
                    :ns-whitelist  ["agiladmin.*"]
                    :ns-blacklist  ["org.eclipse.jetty.*"]})
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
