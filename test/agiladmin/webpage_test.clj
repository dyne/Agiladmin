(ns agiladmin.webpage-test
  (:require [agiladmin.ring :as ring]
            [agiladmin.webpage :as webpage]
            [hiccup.form :as hf]
            [hiccup.core :as hiccup]
            [midje.sweet :refer :all]))

(fact "Base path helper normalizes root and nested prefixes"
      (webpage/base-path {:agiladmin {:webserver {:base-path "/"}}}) => "/"
      (webpage/base-path {:agiladmin {:webserver {:base-path "/agiladmin"}}}) => "/agiladmin"
      (webpage/base-path {:agiladmin {:webserver {:base-path "agiladmin/"}}}) => "/agiladmin"
      (webpage/base-path {:agiladmin {:webserver {:base-path ""}}}) => "/")

(fact "Path helper applies base path to app routes"
      (webpage/path {:agiladmin {:webserver {:base-path "/"}}} "/timesheets") => "/timesheets"
      (webpage/path {:agiladmin {:webserver {:base-path "/agiladmin"}}} "/timesheets") => "/agiladmin/timesheets"
      (webpage/path {:agiladmin {:webserver {:base-path "agiladmin/"}}} "timesheets") => "/agiladmin/timesheets")

(fact "Public URL helper includes host when present"
      (webpage/public-url {:agiladmin {:webserver {:base-host "https://admin.example.org"
                                                   :base-path "/agiladmin"}}}
                          "/timesheets")
      => "https://admin.example.org/agiladmin/timesheets"
      (webpage/public-url {:agiladmin {:webserver {:base-host ""
                                                   :base-path "/agiladmin"}}}
                          "/timesheets")
      => "/agiladmin/timesheets")

(fact "Button keeps a single hidden field intact"
      (let [html (hiccup/html
                  (webpage/button "/person" "Open"
                                  (hf/hidden-field "person" "Denis Roio")))]
        html => (contains "name=\"person\"")
        html => (contains "value=\"Denis Roio\"")))

(fact "Previous-year button submits both year and person hidden fields"
      (let [html (hiccup/html (webpage/button-prev-year "2022" "Denis Roio"))]
        html => (contains "name=\"year\"")
        html => (contains "value=\"2021\"")
        html => (contains "name=\"person\"")
        html => (contains "value=\"Denis Roio\"")))

(fact "Authenticated navigation hides inaccessible links for generic users"
      (let [html (:body (webpage/render {:email "user@example.org"
                                         :name "User Name"
                                         :role nil}
                                        [:div "body"]))]
        html => (contains "Logout")
        html => (contains "href=\"/persons/list\"")
        html =not=> (contains ">Personnel<")
        html =not=> (contains "Upload")
        html =not=> (contains "Projects")
        html =not=> (contains "Reload")
        html =not=> (contains "Configuration")))

(fact "Authenticated navigation shows project access for managers only"
      (let [html (:body (webpage/render {:email "manager@example.org"
                                         :name "Manager User"
                                         :role "manager"}
                                        [:div "body"]))]
        html => (contains "<svg")
        html => (contains "Personnel")
        html => (contains "Projects")
        html => (contains "Logout")
        html =not=> (contains ">h-5 w-5<")
        html =not=> (contains "Upload")
        html =not=> (contains "Reload")
        html =not=> (contains "Configuration")))

(fact "Authenticated navigation shows admin-only links for admins"
      (let [links (#'agiladmin.webpage/account-nav-links
                   {:email "admin@example.org"
                    :name "Admin User"
                    :role "admin"})
            html (:body (webpage/render {:email "admin@example.org"
                                         :name "Admin User"
                                         :role "admin"}
                                        [:div "body"]))]
        (count (filter #(= "/persons/list" (:href %)) links)) => 1
        html => (contains "Personnel")
        html => (contains "Projects")
        html => (contains "Logout")
        html =not=> (contains "Upload")
        html => (contains "Reload")
        html => (contains "Configuration")))

(fact "Guest navigation does not render a redundant login link"
      (let [html (:body (webpage/render [:div "body"]))]
        html =not=> (contains ">Login<")))

(fact "Shared UI emits root-path assets and form actions by default"
      (let [head-html (hiccup/html (webpage/render-head {}))
            login-html (hiccup/html (webpage/login-form {}))]
        head-html => (contains "src=\"/static/js/app.js\"")
        head-html => (contains "href=\"/static/css/app.css\"")
        login-html => (contains "action=\"/login\"")))

(fact "Shared UI emits prefixed assets, links, and actions with a custom base path"
      (with-redefs [ring/config (atom {:agiladmin {:webserver {:base-path "/admin"}}})]
        (let [head-html (hiccup/html (webpage/render-head))
              login-html (hiccup/html (webpage/login-form))
              nav-links (#'agiladmin.webpage/account-nav-links
                         {:email "admin@example.org"
                          :name "Admin User"
                          :role "admin"})
              button-html (hiccup/html
                           (webpage/button "/person"
                                           "Open"
                                           (hf/hidden-field "person" "Alice")))]
          head-html => (contains "src=\"/admin/static/js/app.js\"")
          head-html => (contains "href=\"/admin/static/css/app.css\"")
          login-html => (contains "action=\"/admin/login\"")
          (set (map :href nav-links)) => #{
                                         "/admin/persons/list"
                                         "/admin/projects/list"
                                         "/admin/reload"
                                         "/admin/config"
                                         "/admin/logout"}
          button-html => (contains "action=\"/admin/person\""))))
