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

(declare login-start)

(defonce config (conf/load-config "agiladmin" conf/default-settings))

(defn- get-client-ip [req]
  (if-let [ips (get-in req [:headers "x-forwarded-for"])]
    (-> ips (clojure.string/split #",") first)
    (:remote-addr req)))

(defn- active-backend-kind []
  (let [kind (auth/backend-kind)]
    (when-not (f/failed? kind)
      kind)))

(defn- login-shell
  []
  (case (active-backend-kind)
    :pocket-id web/pocket-id-login-form
    web/login-form))

(defn- password-login-response
  [request]
  (f/attempt-all
   [username (or (get-in request [:params :email])
                 (get-in request [:params :username])
                 (f/fail "Parameter not found: :email"))
    password (s/param request :password)
    logged (auth/sign-in username password {:ip-address (get-client-ip request)})]
   (let [session {:session {:config config
                            :auth (s/normalize-role logged)}}
         account (s/normalize-role logged)]
     (if (or (s/admin? account)
             (s/manager? account))
       (conj session
             (web/render
              logged
              [:div {:class "card mx-auto max-w-xl bg-base-100 shadow-xl"}
               [:div {:class "card-body"}
                [:h1 {:class "card-title text-3xl"} "Logged in: " username]]]))
       (assoc session
              :status 302
              :headers {"Location" "/persons/list"}
              :body "")))
   (f/when-failed [e]
     (web/render-error-page
      (str "Login failed: " (f/message e))))))

(defn login-get [request]
  (f/attempt-all
   [acct (s/check-account @ring/config request)]
   (web/render acct
               [:div {:class "card mx-auto max-w-xl bg-base-100 shadow-xl"}
                [:div {:class "card-body gap-4"}
                 [:h1 {:class "card-title text-3xl"}
                  (str "Already logged in with account: " (:email acct))]
                 [:div {:class "card-actions"}
                  [:a {:class "btn btn-primary" :href "/logout"} "Logout"]]]])
   (f/when-failed [e]
     (web/render (login-shell)))))

(defn login-post [request]
  (if (= :pocket-id (active-backend-kind))
    (login-start request)
    (password-login-response request)))

(defn login-start [request]
  (f/attempt-all
   [response (auth/begin-login request)]
   response
   (f/when-failed [e]
     (web/render-error-page
      (str "Login failed: " (f/message e))))))

(defn pocket-id-callback [request]
  (f/attempt-all
   [logged (auth/complete-login request)]
   {:status 302
    :headers {"Location" "/persons/list"}
    :session {:config config
              :auth (s/normalize-role logged)}
    :body ""}
   (f/when-failed [e]
     (web/render-error-page
      (str "Login failed: " (f/message e))))))

(defn logout-get [request]
  (let [response (auth/logout-response request)
        session (merge (:session response) {:config config})]
    (assoc response :session session)))

(defn signup-get [request]
  (if (= :pocket-id (active-backend-kind))
    (web/render
     (web/signup-disabled-card
      "Pocket ID manages user onboarding. Ask an administrator to create your account and enroll a passkey there."))
    (web/render web/signup-form)))

(defn signup-post [request]
  (if (= :pocket-id (active-backend-kind))
    (web/render
     (web/signup-disabled-card
      "Pocket ID manages sign-up and passkey enrollment outside Agiladmin."))
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
             [:div {:class "card mx-auto max-w-xl bg-base-100 shadow-xl"}
              [:div {:class "card-body"}
               [:h2 (str "Account created: "
                         name " &lt;" email "&gt;")]
               [:h3 "Account pending activation. Check your email for the verification link."]]])
           (web/render-error
            (str "Failure creating account: "
                 (f/message signup)))))
        (web/render-error
         "Repeat password didnt match")))
     (f/when-failed [e]
       (web/render-error-page
        (str "Sign-up failure: " (f/message e)))))))

(defn activate
  ([request token]
   (activate request nil token))
  ([request email token]
   (if (= :pocket-id (active-backend-kind))
     (web/render
      (web/signup-disabled-card
       "Pocket ID manages account activation outside Agiladmin."))
     (web/render
      [:div
       [:div {:class "card mx-auto max-w-xl bg-base-100 shadow-xl"}
        [:div {:class "card-body"}
         (f/if-let-failed?
           [act (auth/confirm-verification email token)]
           (web/render-error
            [:div
             [:h1 "Failure activating account"]
             [:h2 (f/message act)]
             [:p (str "Token: " token)]])
           [:h1 {:class "card-title text-3xl"}
            (str "Account activated" (when email (str " - " email)))])]]]))))
