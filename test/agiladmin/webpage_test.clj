(ns agiladmin.webpage-test
  (:require [agiladmin.webpage :as webpage]
            [hiccup.core :as hiccup]
            [midje.sweet :refer :all]))

(fact "Previous-year button submits both year and person hidden fields"
      (let [html (hiccup/html (webpage/button-prev-year "2022" "Denis Roio"))]
        html => (contains "name=\"year\"")
        html => (contains "value=\"2021\"")
        html => (contains "name=\"person\"")
        html => (contains "value=\"Denis Roio\"")))
