;; Agiladmin - spreadsheet based time and budget administration

;; Copyright (C) 2016-2019 Dyne.org foundation

;; Sourcecode written and maintained by Denis Roio <jaromil@dyne.org>

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

(ns agiladmin.view-auth
  (:require
   [agiladmin.auth.core :as auth]
   [agiladmin.webpage :as web]
   [agiladmin.session :as s]
   [agiladmin.ring :as ring]
   [agiladmin.config :as conf]
   [failjure.core :as f]))

(defonce config (conf/load-config "agiladmin" conf/default-settings))

(defn- get-client-ip [req]
  (if-let [ips (get-in req [:headers "x-forwarded-for"])]
    (-> ips (clojure.string/split #",") first)
    (:remote-addr req)))

(defn login-get [request]
  (f/attempt-all
   [acct (s/check-account @ring/config request)]
   (web/render acct
               [:div
                [:h1 (str "Already logged in with account: "
                          (:email acct))]
                [:h2 [:a {:href "/logout"} "Logout"]]])
   (f/when-failed [e]
     (web/render web/login-form))))

(defn login-post [request]
  (f/attempt-all
   [username (or (get-in request [:params :email])
                 (get-in request [:params :username])
                 (f/fail "Parameter not found: :email"))
   password (s/param request :password)
    logged (auth/sign-in username password {:ip-address (get-client-ip request)})]
   (let [session {:session {:config config
                            :auth (s/normalize-role logged)}}]
     (conj session
           (web/render
            logged
            [:div
             [:h1 "Logged in: " username]])))
   (f/when-failed [e]
     (web/render-error-page
      (str "Login failed: " (f/message e))))))

(defn logout-get [request]
  (conj {:session {:config config}}
        (web/render [:h1 "Logged out."])))

(defn signup-get [request]
  (web/render web/signup-form))

(defn signup-post [request]
  (f/attempt-all
   [name (s/param request :name)
    email (s/param request :email)
    password (s/param request :password)
    repeat-password (s/param request :repeat-password)]
   (web/render
    (if (= password repeat-password)
      (f/try*
       (f/if-let-ok?
           [signup (auth/sign-up name
                                 email
                                 password
                                 {}
                                 [])]
         (f/if-let-failed?
             [verification (auth/request-verification email)]
           (web/render-error
            (str "Failure requesting verification: "
                 (f/message verification)))
           [:div
            [:h2 (str "Account created: "
                      name " &lt;" email "&gt;")]
            [:h3 "Account pending activation. Check your email for the verification link."]])
         (web/render-error
          (str "Failure creating account: "
               (f/message signup)))))
      (web/render-error
       "Repeat password didnt match")))
   (f/when-failed [e]
     (web/render-error-page
      (str "Sign-up failure: " (f/message e))))))

(defn activate
  ([request token]
   (activate request nil token))
  ([request email token]
   (web/render
    [:div
     (f/if-let-failed?
         [act (auth/confirm-verification email token)]
       (web/render-error
        [:div
         [:h1 "Failure activating account"]
         [:h2 (f/message act)]
         [:p (str "Token: " token)]])
       [:h1 (str "Account activated" (when email (str " - " email)))])])))
