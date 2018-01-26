;; Copyright (C) 2015-2018 Dyne.org foundation

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

(ns agiladmin.webpage
  (:require [clojure.java.io :as io]
            [clojure.data.json :as json]
            [clojure.data.csv :as csv]
            [yaml.core :as yaml]
            [agiladmin.config :as conf]
            [taoensso.timbre :as log]
            [hiccup.page :as page]
            [hiccup.form :as hf]
            [json-html.core :as present]
            [clj-jgit.porcelain :as git]
            [clj-jgit.querying  :as gitq]))

(declare render)
(declare render-page)
(declare render-head)
(declare render-navbar)
(declare render-footer)
(declare render-yaml)
(declare render-edn)
(declare render-error)
(declare render-error-page)
(declare render-static)

(defn show-config [session]
  (present/edn->html (dissoc session
                             :salt :prime :length :entropy
                             :type "__anti-forgery-token")))



(defn button
  ([url text] (button url text [:p]))

  ([url text field] (button url text field "btn-secondary btn-lg"))

  ([url text field type]
   (hf/form-to [:post url]
               field ;; can be an hidden key/value field (project,
                     ;; person, etc using hf/hidden-field)
               (hf/submit-button {:class (str "btn " type)} text))))

(defn people-download-toolbar
  [person year costs]
  [:form {:action "/people/spreadsheet"
          :method "post"}
   [:h3 "Download yearly totals:"]
   (hf/hidden-field "format" "excel")
   (hf/hidden-field "person" person)
   (hf/hidden-field "year" year)
   (hf/hidden-field "costs" (-> costs json/write-str))
   [:input {:type "submit" :name "format1" :value "excel"
            :class "btn btn-default"}]
   [:input {:type "submit" :name "format2" :value "json"
            :class "btn btn-default"}]
   [:input {:type "submit" :name "format3" :value "csv"
            :class "btn btn-default"}]
   [:input {:type "submit" :name "format4" :value "html"
            :class "btn btn-default"}]])


(defn reload-session [request]
  ;; TODO: validation of all data loaded via prismatic schema
  (conf/load-config "agiladmin" conf/default-settings)

)


(defn check-session [request]
  ;; reload configuration from file all the time if in debug mode
  (if (log/may-log? :debug)
    (conf/load-config "agiladmin" conf/default-settings)
    ;; else
    (let [session (:session request)]
      (cond
        (not (contains? session :config))
        (conj session (conf/load-config "agiladmin" conf/default-settings))
        (string?  (:config session)) session
        (false? (:config session)) conf/default-settings
        ))))

(defn render [body]
  {:headers {"Content-Type"
             "text/html; charset=utf-8"}
   :body (page/html5
          (render-head)
          [:body {:class "fxc static"}
           (render-navbar)
           
           [:div {:class "container"}
           ;;  [:img {:src "/static/img/secret_ladies.jpg"
           ;;         :class "pull-right img-responsive"
           ;;         :style "width: 16em; border:1px solid #010a40"}]
           ;;  [:h1 "Simple Secret Sharing Service" ] body]
           body]

           (render-footer)
           ])})

(defn render-error
  "render an error message without ending the page"
  [err]
  [:div {:class "alert alert-danger" :role "alert"}
   [:span {:class "glyphicon glyphicon-exclamation-sign"
           :aria-hidden "true" :style "padding: .5em"}]
   [:span {:class "sr-only"} "Error:" ]
   err])

(defn render-error-page
  ([]    (render-error-page {} "Unknown"))
  ([err] (render-error-page {} err))
  ([session error]
   (render
    [:div {:class "container-fluid"}
     (render-error error)
     (if-not (empty? session)
       [:div {:class "config"}
        [:h2 "Environment dump:"]
        (render-yaml session)])])))


(defn render-head
  ([] (render-head
       "Agiladmin" ;; default title
       "Agiladmin"
       "https://agiladmin.dyne.org")) ;; default desc

  ([title desc url]
   [:head [:meta {:charset "utf-8"}]
    [:meta {:http-equiv "X-UA-Compatible" :content "IE=edge,chrome=1"}]
    [:meta
     {:name "viewport"
      :content "width=device-width, initial-scale=1, maximum-scale=1"}]

    ;; social stuff
    [:meta {:name "description"  :content desc }]
    [:meta {:property "og:title" :content title }]
    [:meta {:property "og:description" :content desc }]
    [:meta {:property "og:type" :content "website" }]
    [:meta {:property "og:url" :content url }]
    [:meta {:property "og:image" :content (str url "/static/img/secret_ladies.jpg") }]

    [:meta {:name "twitter:card" :content "summary"}]
    [:meta {:name "twitter:site" :content "@DyneOrg"}]
    [:meta {:name "twitter:title" :content title }]
    [:meta {:name "twitter:description" :content desc }]
    [:meta {:name "twitter:image" :content (str url "/static/img/secret_ladies.jpg") }]

    [:title title]
    (page/include-js  "/static/js/dhtmlxgantt.js")
    (page/include-js  "/static/js/dhtmlxgantt_marker.js")
    (page/include-js  "/static/js/sorttable.js")
    (page/include-js  "/static/js/jquery-3.2.1.min.js")
    (page/include-js  "/static/js/bootstrap.min.js")
    (page/include-js  "/static/js/highlight.pack.js")
    (page/include-css "/static/css/bootstrap.min.css")
    (page/include-css "/static/css/dhtmlxgantt.css")
    (page/include-css "/static/css/bootstrap-theme.min.css")
    (page/include-css "/static/css/json-html.css")
    (page/include-css "/static/css/highlight-tomorrow.css")
    (page/include-css "/static/css/agiladmin.css")]))

(defn render-navbar []
  [:nav {:class "navbar navbar-default navbar-fixed-top"}
   [:div {:class "container"}
    [:ul {:class "nav navbar-nav"}
     [:li [:a {:href "/"} "Home"]]
     [:li {:role "separator" :class "divider"} ]
     [:li [:a {:href "/home"} "List all"]]
     [:li [:a {:href "/reload"} "Reload"]]
     [:li {:role "separator" :class "divider"} ]
     [:li [:a {:href "/config"} "Configuration"]]
     ]]])

(defn render-footer []
  [:footer {:class "row" :style "margin: 20px"}
   [:hr]

   [:div {:class "footer col-lg-3"}
    [:img {:src "static/img/AGPLv3.png" :style "margin-top: 2.5em"
           :alt "Affero GPLv3 License"
           :title "Affero GPLv3 License"} ]]

   [:div {:class "footer col-lg-3"}
    [:a {:href "https://www.dyne.org"}
     [:img {:src "/static/img/swbydyne.png"
            :alt   "Software by Dyne.org"
            :title "Software by Dyne.org"}]]]
   ])


(defn render-static [body]
  (page/html5 (render-head)
              [:body {:class "fxc static"}

               (render-navbar)

               [:div {:class "container"} body]

               (render-footer)
               ]))


(defn render-page [{:keys [section body] :as content}]
  (let [title "AgileAdmin"
        desc "Agile Administration for Small and Medium Organisations"
        url "https://agiladmin.dyne.org"]

    (page/html5

     (render-head)

     (render-navbar)

      [:div {:class "container-fluid"}
       [:h1 "Agiladmin" ]
       [:h3 section]
       body]

      (render-footer))))

;; highlight functions do no conversion, take the format they highlight
;; render functions take edn and convert to the highlight format
;; download functions all take an edn and convert it in target format
;; edit functions all take an edn and present an editor in the target format

(defn render-html
  "renders an edn into an organised html table"
  [data]
  (present/edn->html data))

(defn render-yaml
  "renders an edn into an highlighted yaml"
  [data]
  [:span
   [:pre [:code {:class "yaml"}
          (yaml/generate-string data)]]
   [:script "hljs.initHighlightingOnLoad();"]])

(defn highlight-yaml
  "renders a yaml text in highlighted html"
  [data]
  [:span
   [:pre [:code {:class "yaml"}
          data]]
   [:script "hljs.initHighlightingOnLoad();"]])


(defn highlight-json
  "renders a json text in highlighted html"
  [data]
  [:span
   [:pre [:code {:class "json"}
          data]]
   [:script "hljs.initHighlightingOnLoad();"]])
  
(defn download-csv
  "takes an edn, returns a csv plain/text for download"
  [data]
  {:headers {"Content-Type"
             "text/plain; charset=utf-8"}
   :body (with-out-str (csv/write-csv *out* data))})
  
(defn edit-edn
  "renders an editor for the edn in yaml format"
  [data]
  [:div;; {:class "form-group"}
   [:textarea {:class "form-control"
               :rows "20" :data-editor "yaml"
               :id "config" :name "editor"}
    (yaml/generate-string data)]
   [:script {:src "/static/js/ace.js"
             :type "text/javascript" :charset "utf-8"}]
   [:script {:type "text/javascript"}
    (slurp (io/resource "public/static/js/ace-embed.js"))]
   ;; code to embed the ace editor on all elements in page
   ;; that contain the attribute "data-editor" set to the
   ;; mode language of choice
   [:input {:class "btn btn-success btn-lg pull-top"
            :type "submit" :value "submit"}]])

(defn git-log
  "list the last 20 git commits in the budget repo"
  [repo]
  [:div {:class "commitlog"}
   (->> (git/git-log repo)
        (map #(gitq/commit-info repo %))
        (map #(select-keys % [:author :message :time :changed_files]))
        (take 20) render-yaml)])

