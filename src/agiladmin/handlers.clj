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

   [hiccup.page :as page]
   [hiccup.form :as hf]
   [hiccup.middleware :refer [wrap-base-url]]
   [json-html.core :as present]
   [markdown.core :as md]

   [ring.middleware.session :refer :all]
   [ring.middleware.accept :refer [wrap-accept]]
   [ring.middleware.defaults :refer [wrap-defaults site-defaults]]

   [clj-jgit.porcelain :refer :all]
   [clj-jgit.querying  :refer :all]

   [agiladmin.core :refer :all]
   [agiladmin.webpage :as web]
   [agiladmin.config :refer :all])
  (:import java.io.File))

(defn readme [request]
  (conj {:session (web/check-session request)}
        (web/render
         (md/md-to-html-string
          (slurp (let [accept (:accept request)
                       readme "public/static/README-"
                       lang (:language accept)
                       locale (io/resource (str readme lang ".md"))]
                   (if (nil? locale) (io/resource "public/static/README.md") locale)))))))

(defn button
  ([config url text] (button config url text [:p]))

  ([config url text fields]
   (hf/form-to [:post url]
               (hf/hidden-field "__anti-forgery-token" (config "__anti-forgery-token"))
               fields
               (hf/submit-button text))))

(defn project-log-view [config request]
  (let [repo (load-repo "budgets")]
    (web/render [:div {:class "row-fluid"}

                 [:div {:class "projects col-lg-6"}
                  (for [f (->> (list-files-matching "budgets" #"budget.*xlsx$")
                               (map #(.getName %)))]
                    [:div {:class "row"}
                     [:div {:class "col-lg-4"} f]
                     [:div {:class "col-lg-2"} (button config "/update" "Update"
                                                       (hf/hidden-field "project" f))]])]

                 [:div {:class "commitlog col-lg-6"}
                  (button config "/pull" "Pull")
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
                 (.isDirectory (io/file "budgets")) (project-log-view config request)
                 (.exists (io/file "budgets")) (web/render-error config [:h1 "Invalid budgets directory."])
                 :else
                 (web/render [:div "Budgets not yet imported" (button config "/import" "Import")
                              (web/show-config config)])))))

  (POST "/pull" request
        (let [config (web/check-session request)
              repo (load-repo "budgets")]
          (with-identity {:name (slurp (:ssh-priv config))
                          :private (slurp (:ssh-priv config))
                          :public  (slurp (:ssh-pub config))
                          :passphrase (:ssh-pass config)
                          :exclusive true}
            (git-pull repo))
          (conj {:session config}
                (project-log-view config request))))

  (POST "/import" request
        (let [config (web/check-session request)]
          (conj {:session config}
                (web/render [:div
                             (with-identity {:name (slurp (:ssh-priv config))
                                             :private (slurp (:ssh-priv config))
                                             :public  (slurp (:ssh-pub config))
                                             :passphrase (:ssh-pass config)
                                             :exclusive true}
                               (git-clone (:git config) "budgets"))]))))

  (POST "/update" request
        (let [config (web/check-session request)
              projfile (get-in request [:params :project])
              projname (-> (str/split projfile #"_") (second)
                           (str/split #"\.") (first))
              hours (if-let [hs (:hours config)]
                      hs (->> (load-all-timesheets "budgets/" #".*_timesheet_.*xlsx$")
                              (load-project-hours projname)
                              (into [["Name" "Date" "Task" "Hours"]])))]
          (write-project-hours (str "budgets/" projfile) hours)

          (web/render [:h1 projname
                       [:div (present/edn->html
                              (-> (load-repo "budgets")
                                  (git-status)))]
                       [:div (present/edn->html hours)]])))


  ;; TODO: detect cryptographical conversion error: returned is the first share
  (route/resources "/")
  (route/not-found "Not Found"))


(def app
  (-> (wrap-defaults app-routes site-defaults)
      (wrap-session)
      (wrap-accept {:mime ["text/html"]
                    ;; preference in language, fallback to english
                    :language ["en" :qs 0.5
                               "it" :qs 1
                               "nl" :qs 1
                               "hr" :qs 1]})))
