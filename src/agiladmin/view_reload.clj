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
    [agiladmin.utils :as util]
    [agiladmin.webpage :as web]
    [agiladmin.config :as conf]
    [taoensso.timbre :as log]
    [clj-jgit.porcelain :as git
     :refer [with-identity load-repo git-clone git-pull]]))

(defn start [request config account]
  (let [budgets (conf/q config [:agiladmin :budgets])
        keypath (:ssh-key budgets)
        gitpath (:git budgets)
        path (io/file (:path budgets))]
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

        (web/render
          account
          [:div {:class "container-fluid"}
           (git/with-identity {:name       (:ssh-key budgets)
                               :passphrase ""
                               :exclusive  true}
                              (let [res (try (git/git-pull repo)
                                             (catch Exception ex
                                               (web/render-error
                                                 (log/spy :error [:div [:p (str "Error in git-pull: " (.getMessage ex))]
                                                                  [:p (-> ex Throwable->map :cause)]]))))]
                                (if (= (type res) org.eclipse.jgit.api.PullResult)
                                  [:div {:class "alert alert-success"}
                                   (str "Reloaded successfully from " (:git budgets))]
                                  res)))

           [:div [:h1 "Git status"]
            (web/render-yaml (git/git-status repo))]
           [:div [:h1 "Log (last 20 changes)"]
            (web/render-git-log repo)]
           ;; [:div {:class "col-md-6"} [:h1 "Config"] (web/render-yaml config)]
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
       account
       [:div {:class "container-fluid"}
        (git/with-identity {:name       keypath
                            ;; :private (slurp keypath)
                            ;; :public  (slurp (str keypath ".pub")
                            :passphrase ""
                            :exclusive  true}
                           (try (git/git-clone (:path budgets) "budgets")
                                (catch Exception ex
                                  (web/render-error
                                    (log/spy :error
                                             [:p "Error cloning git repo" ex
                                              "Add your public key to the repository to access it:"
                                              (-> (str keypath ".pub") slurp str)])))))]

        (if-let [repo (git/load-repo (:path budgets))]
          [:div
           [:div [:h1 "Git status"]
            (web/render-yaml (git/git-status repo))]
           [:div [:h1 "Log (last 20 changes)"] (web/render-git-log repo)]]))
      ;; end of POST /reload
      )))
