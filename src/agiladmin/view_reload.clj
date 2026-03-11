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
   [agiladmin.config :as conf]
   [taoensso.timbre :as log]
   [clj-jgit.porcelain :as git
    :refer [with-identity load-repo git-clone git-pull]]))

(defn- render-reload-message
  [account message]
  (web/render
   account
   [:div {:class "space-y-4"}
    [:div {:class "alert alert-info shadow-sm"}
     message]]))

(defn- render-reload-error
  [account message]
  (web/render
   account
   [:div {:class "space-y-4"}
    (web/render-error message)]))

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

(defn- render-repo-state
  [account repo]
  (web/render
   account
   [:div {:class "space-y-6"}
    [:div [:h1 {:class "text-3xl font-semibold"} "Git status"]
     (web/render-yaml (git/git-status repo))]
    [:div [:h1 {:class "text-3xl font-semibold"} "Log (last 20 changes)"]
     (web/render-git-log repo)]]))

(defn start [request config account]
  (let [budgets (conf/q config [:agiladmin :budgets])
        keypath (:ssh-key budgets)
        path (io/file (:path budgets))]
    (cond
      (not (git-ready? budgets))
      (render-reload-message
       account
       "Reload is unavailable until :agiladmin :budgets has git, path, and ssh-key configured.")

      (.isDirectory path)
      ;; the directory exists (we assume also is a correct
      ;; one) TODO: analyse contents of path, detect git
      ;; repo and correct agiladmin environment, detect
      ;; errors and report them
      (if-let [repo (safe-load-repo (:path budgets))]
        (do
          (log/info
           (str "Path is a directory, trying to pull in: "
                (:path budgets)))
          (web/render
           account
           [:div {:class "space-y-6"}
            (git/with-identity {:name (:ssh-key budgets)
                                :passphrase ""
                                :exclusive true}
                               (let [res (try (git/git-pull repo)
                                              (catch Exception ex
                                                (web/render-error
                                                 (log/spy :error
                                                          [:div
                                                           [:p (str "Error in git-pull: " (.getMessage ex))]
                                                           [:p (-> ex Throwable->map :cause)]]))))]
                                 (if (= (type res) org.eclipse.jgit.api.PullResult)
                                   [:div {:class "alert alert-success shadow-sm"}
                                    (str "Reloaded successfully from " (:git budgets))]
                                   res)))
            [:div [:h1 {:class "text-3xl font-semibold"} "Git status"]
             (web/render-yaml (git/git-status repo))]
            [:div [:h1 {:class "text-3xl font-semibold"} "Log (last 20 changes)"]
             (web/render-git-log repo)]]))
        (render-reload-message
         account
         (str "Budgets path exists but is not a git repository yet: " (:path budgets))))


      (.exists path)
      ;; exists but is not a directory
      (web/render-error-page
        config
        (log/spy
          :error
          (str "Invalid budgets directory: " (:path budgets))))

      :else
      ;; doesn't exists at all
      (if (.exists (io/file (str keypath ".pub")))
        (let [clone-result
              (git/with-identity {:name keypath
                                  :passphrase ""
                                  :exclusive true}
                                 (try (git/git-clone (:path budgets) "budgets")
                                      (catch Exception ex
                                        (web/render-error
                                         (log/spy :error
                                                  [:p "Error cloning git repo" ex
                                                   "Add your public key to the repository to access it:"
                                                   (-> (str keypath ".pub") slurp str)])))))]
          (web/render
           account
           [:div {:class "space-y-6"}
            clone-result
            (if-let [repo (safe-load-repo (:path budgets))]
              [:div
               [:div [:h1 {:class "text-3xl font-semibold"} "Git status"]
                (web/render-yaml (git/git-status repo))]
               [:div [:h1 {:class "text-3xl font-semibold"} "Log (last 20 changes)"] (web/render-git-log repo)]]
              [:div {:class "alert alert-info shadow-sm"}
               (str "No budgets repository is available yet at " (:path budgets))])]))
        (render-reload-message
         account
         (str "No budgets repository is available yet. Generate or configure SSH keys first: "
              keypath ".pub")))
      ;; end of POST /reload
      )))
