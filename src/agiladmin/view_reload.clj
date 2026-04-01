;; Copyright (C) 2018 Dyne.org foundation

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


(ns agiladmin.view-reload
  (:require
   [clojure.java.io :as io]
   [agiladmin.webpage :as web]
   [agiladmin.core :as core]
   [agiladmin.config :as conf]
   [taoensso.timbre :as log]
   [clj-jgit.porcelain :as git]))

(def reload-result-id "reload-result")

(defn- reload-result
  [body]
  [:div {:id reload-result-id
         :class "space-y-4"}
   body])

(defn- render-reload-page
  [request account result-body]
  (let [body [:div {:class "space-y-6"}
              [:div {:class "card bg-base-100 shadow-sm"}
               [:div {:class "card-body gap-4"}
                [:h1 {:class "card-title text-3xl"} "Reload budgets repository"]
                [:p "Fetch the configured budgets repository and refresh runtime caches after new data is adopted."]
                [:form {:action "/reload"
                        :method "post"
                        :class "inline-flex"
                        :hx-post "/reload"
                        :hx-target (str "#" reload-result-id)
                        :hx-swap "outerHTML"}
                 [:input {:type "submit"
                          :value "Reload"
                          :class "btn btn-primary btn-lg"}]]]
              (reload-result result-body)]]]
    (web/render account body)))

(defn- render-reload-response
  [request account body]
  (let [fragment (reload-result body)]
    (if (web/htmx-request? request)
      (web/render-fragment fragment)
      (render-reload-page request account body))))

(defn page
  [request _config account]
  (render-reload-page
   request
   account
   [:div {:class "alert alert-info shadow-sm"}
    "Press Reload to fetch the latest budgets repository state."]))

(defn- render-reload-message
  [request account message]
  (render-reload-response
   request
   account
   [:div {:class "alert alert-info shadow-sm"}
    message]))

(defn- render-reload-error
  [request account message]
  (render-reload-response request account (web/render-error message)))

(defn- git-ready?
  [budgets]
  (every? seq [(:path budgets) (:git budgets) (:ssh-key budgets)]))

(defn- safe-load-repo
  [path]
  (try
    (git/load-repo path)
    (catch Exception ex
      (log/error [:p "Error in git/load-repo: " ex])
      nil)))

(defn- empty-directory?
  [^java.io.File path]
  (when (.isDirectory path)
    (let [entries (.listFiles path)]
      (or (nil? entries)
          (zero? (alength entries))))))

(defn- clone-budgets!
  [budgets]
  (git/with-identity {:name (:ssh-key budgets)
                      :passphrase ""
                      :exclusive true}
    (git/git-clone (:git budgets) (:path budgets))))

(defn- render-repo-state-with-message
  [request account repo message]
  (render-reload-response
   request
   account
   [:div {:class "space-y-6"}
    [:div {:class "alert alert-success shadow-sm"}
     message]
    [:div [:h1 {:class "text-3xl font-semibold"} "Git status"]
     (web/render-yaml (git/git-status repo))]
    [:div [:h1 {:class "text-3xl font-semibold"} "Log (last 20 changes)"]
     (web/render-git-log repo)]]))

(defn start [request config account]
  (let [budgets (conf/q config [:agiladmin :budgets])
        keypath (:ssh-key budgets)
        path (io/file (:path budgets))
        repo (when (and (.exists path)
                        (.isDirectory path))
               (safe-load-repo (:path budgets)))]
    (cond
      (not (git-ready? budgets))
      (render-reload-message
       request
       account
       "Reload is unavailable until :agiladmin :budgets has git, path, and ssh-key configured.")

      repo
      (let [repo repo]
        (log/info
         (str "Path is a directory, trying to pull in: "
              (:path budgets)))
        (try
            (let [pull-result
                (git/with-identity {:name (:ssh-key budgets)
                                    :passphrase ""
                                    :exclusive true}
                  (git/git-pull repo))]
            ;; Adopted repo state changed, so all derived runtime reads must refresh.
            (core/invalidate-runtime-caches! config)
            (render-repo-state-with-message
             request
             account
             repo
             (if (= (type pull-result) org.eclipse.jgit.api.PullResult)
               (str "Reloaded successfully from " (:git budgets))
               "Reload completed.")))
          (catch Exception ex
            (log/error
             [:div
              [:p (str "Error in git-pull: " (.getMessage ex))]
              [:p (-> ex Throwable->map :cause)]])
            (render-reload-error
             request
             account
             (str "Error in git-pull: " (.getMessage ex))))))

      (and (.exists path)
           (.isDirectory path)
           (not repo)
           (not (empty-directory? path)))
      (render-reload-message
       request
       account
       (str "Budgets path exists but is not a git repository yet: " (:path budgets)))

      (or (not (.exists path))
          (and (.isDirectory path)
               (empty-directory? path)))
      (if (.exists (io/file (str keypath ".pub")))
        (try
          (clone-budgets! budgets)
          ;; First clone creates the live project tree for this budgets path.
          (core/invalidate-runtime-caches! config)
          (if-let [repo (safe-load-repo (:path budgets))]
            (render-repo-state-with-message
             request
             account
             repo
             (str "Cloned successfully from " (:git budgets)))
            (render-reload-message
             request
             account
             (str "No budgets repository is available yet at " (:path budgets))))
          (catch Exception ex
            (render-reload-error
             request
             account
             (str "Error cloning git repo: " (.getMessage ex)))))
        (render-reload-message
         request
         account
         (str "No budgets repository is available yet. Generate or configure SSH keys first: "
              keypath ".pub")))

      (.exists path)
      ;; exists but is not a directory
      (web/render-error-page
        config
        (log/spy
          :error
          (str "Invalid budgets directory: " (:path budgets))))

      :else
      (render-reload-error
       request
       account
       (str "Unsupported budgets directory state: " (:path budgets)))
      ;; end of POST /reload
      )))
