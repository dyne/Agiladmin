(ns agiladmin.webpage-test
  (:require [agiladmin.webpage :as webpage]
            [hiccup.form :as hf]
            [hiccup.core :as hiccup]
            [midje.sweet :refer :all]))

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
      (let [html (:body (webpage/render {:email "admin@example.org"
                                         :name "Admin User"
                                         :role "admin"}
                                        [:div "body"]))]
        html => (contains "Personnel")
        html => (contains "Projects")
        html => (contains "Logout")
        html =not=> (contains "Upload")
        html => (contains "Reload")
        html => (contains "Configuration")))

(fact "Guest navigation does not render a redundant login link"
      (let [html (:body (webpage/render [:div "body"]))]
        html =not=> (contains ">Login<")))
