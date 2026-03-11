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
            [failjure.core :as f]
            [agiladmin.ring :as ring]
            [hiccup.core :as hiccup]
            [hiccup.page :as page]
            [hiccup.form :as hf]
            [clj-jgit.porcelain :as git]
            [clj-jgit.querying  :as gitq]))

(declare render)
(declare render-head)
(declare navbar-guest)
(declare navbar-account)
(declare render-footer)
(declare render-yaml)
(declare render-edn)
(declare render-error)
(declare render-error-page)
(declare render-fragment)

(defn button
  ([url text] (button url text [:p]))

  ([url text field] (button url text field "btn btn-primary"))

  ([url text field type]
   (let [fields (cond
                  (nil? field) []
                  (and (seq? field) (every? vector? field)) field
                  :else [field])]
   (apply hf/form-to
          {:class "inline-flex"} [:post url]
          (concat fields
                  [(hf/submit-button {:class type} text)])))))

(defn button-prev-year [year person]
  [:div {:class "w-full lg:w-1/4"}
   (button
    "/person"
    (str "Go to previous year ("(-> year Integer. dec)")")
    (list
     (hf/hidden-field "year" (-> year Integer. dec))
      (hf/hidden-field "person" person)))])

(defn htmx-request?
  [request]
  (let [value (or (get-in request [:headers "hx-request"])
                  (get-in request [:headers "HX-Request"]))]
    (= "true" value)))

(defn render-fragment
  [body]
  {:headers {"Content-Type" "text/html; charset=utf-8"}
   :body (hiccup/html body)})

(defn tabs
  [group-id tabs]
  [:div {:class "space-y-4" :data-tab-group group-id}
   [:div {:class "tabs tabs-boxed flex w-full flex-wrap gap-2 bg-base-200 p-2"}
    (for [[idx {:keys [id title]}] (map-indexed vector tabs)]
      [:button {:type "button"
                :class (str "tab min-w-max px-4 " (when (zero? idx) "tab-active"))
                :data-tab-trigger id
                :aria-controls id
                :aria-selected (if (zero? idx) "true" "false")}
       title])]
   (for [[idx {:keys [id content]}] (map-indexed vector tabs)]
     [:section {:id id
                :class "rounded-box border border-base-300 bg-base-100 p-4 shadow-sm"
                :data-tab-panel id
                :hidden (when-not (zero? idx) true)}
      content])])


(defn render
  ([body]
  {:headers {"Content-Type"
             "text/html; charset=utf-8"}
   :body (page/html5
          (render-head)
          [:body {:data-theme "nord"
                  :class "min-h-screen bg-base-200 text-base-content"}
           navbar-guest
           [:main {:class "mx-auto w-full max-w-screen-2xl px-4 pb-12 pt-24 md:px-6"} body]
           (render-footer)])})
  ([account body]
   {:headers {"Content-Type"
              "text/html; charset=utf-8"}
    :body (page/html5
           (render-head)
           [:body {:data-theme "nord"
                   :class "min-h-screen bg-base-200 text-base-content"}
            (if (empty? account)
                    navbar-guest
                    navbar-account)
            [:main {:class "mx-auto w-full max-w-screen-2xl px-4 pb-12 pt-24 md:px-6"} body]
            (render-footer)])}))


(defn render-error
  "render an error message without ending the page"
  [err]
  [:div {:class "alert alert-error shadow-sm" :role "alert"}
   [:span {:class "far fa-meh text-lg" :aria-hidden "true"}]
   [:span {:class "sr-only"} "Error:"]
   [:span err]])

(defn render-error-page
  ([]    (render-error-page {} "Unknown"))
  ([err] (render-error-page {} err))
  ([session error]
   (render
    [:div {:class "space-y-4"}
     (render-error error)])))


(defn render-head
  ([] (render-head
       "Agiladmin" ;; default title
       "Agiladmin"
       "https://agiladmin.dyne.org")) ;; default desc

  ([title _desc _url]
   [:head [:meta {:charset "utf-8"}]
    [:meta {:http-equiv "X-UA-Compatible" :content "IE=edge,chrome=1"}]
    [:meta
     {:name "viewport"
      :content "width=device-width, initial-scale=1, maximum-scale=1"}]

    [:title title]

    ;; javascript scripts
    (page/include-js  "/static/js/dhtmlxgantt.js")
    (page/include-js  "/static/js/dhtmlxgantt_marker.js")
    (page/include-js  "/static/js/sorttable.js")
    (page/include-js  "/static/js/htmx.min.js")
    (page/include-js  "/static/js/app.js")
    (page/include-js  "/static/js/highlight.pack.js")
    (page/include-js  "/static/js/diff.js")
    (page/include-js  "/static/js/jsondiffpatch.min.js")
    (page/include-js  "/static/js/jsondiffpatch-formatters.min.js")
    (page/include-js  "/static/js/diff_match_patch_uncompressed.js")

    ;; cascade style sheets
    (page/include-css "/static/css/app.css")
    (page/include-css "/static/css/dhtmlxgantt.css")
    (page/include-css "/static/css/json-html.css")
    (page/include-css "/static/css/highlight-tomorrow.css")
    (page/include-css "/static/css/formatters-styles/html.css")
    (page/include-css "/static/css/formatters-styles/annotated.css")
    (page/include-css "/static/css/fa-regular.min.css")
    (page/include-css "/static/css/fontawesome.min.css")
    (page/include-css "/static/css/agiladmin.css")]))

(def navbar-guest
  [:nav
   {:class "navbar fixed inset-x-0 top-0 z-40 border-b border-base-300 bg-base-100/95 px-4 shadow-sm backdrop-blur md:px-6"}
   [:div {:class "flex-1 gap-3"}
    [:img {:src "/static/img/dyne-logo-small.png"
           :class "h-10 w-auto rounded-md bg-base-100 p-1"}]
    [:a {:class "btn btn-ghost text-lg normal-case"
         :href "/"} [:span {:class "far fa-handshake"}] " Agiladmin"]]
   [:div {:class "flex-none"}
    [:button {:type "button"
              :class "btn btn-ghost btn-square md:hidden"
              :data-nav-toggle "guest-nav"
              :aria-controls "guest-nav"
              :aria-expanded "false"
              :aria-label "Toggle navigation"}
     [:span {:class "far fa-bars"}]]
    [:ul {:class "menu menu-horizontal hidden items-center gap-2 px-1 md:flex"}
     [:li [:a {:class "gap-2" :href "/login"}
           [:span {:class "far fa-address-card"}] "Login"]]]]
   [:div {:id "guest-nav"
          :class "hidden w-full basis-full pt-3 md:hidden"}
    [:ul {:class "menu rounded-box bg-base-100 p-2 shadow"}
     [:li [:a {:class "gap-2" :href "/login"}
           [:span {:class "far fa-address-card"}] "Login"]]]]])

(def navbar-account
  [:nav {:class "navbar fixed inset-x-0 top-0 z-40 border-b border-base-300 bg-base-100/95 px-4 shadow-sm backdrop-blur md:px-6"}
   [:div {:class "flex-1 gap-3"}
    [:img {:src "/static/img/dyne-logo-small.png"
           :class "h-10 w-auto rounded-md bg-base-100 p-1"}]
    [:a {:class "btn btn-ghost text-lg normal-case"
         :href "/"} [:span {:class "far fa-handshake"}] " Agiladmin"]]
   [:div {:class "flex-none"}
    [:button {:type "button"
              :class "btn btn-ghost btn-square md:hidden"
              :data-nav-toggle "account-nav"
              :aria-controls "account-nav"
              :aria-expanded "false"
              :aria-label "Toggle navigation"}
     [:span {:class "far fa-bars"}]]
    [:ul {:class "menu menu-horizontal hidden items-center gap-2 px-1 md:flex"}
     [:li [:a {:class "gap-2" :href "/persons/list"} [:span {:class "far fa-address-card"}] "Personnel"]]
     [:li [:a {:class "gap-2" :href "/projects/list"} [:span {:class "far fa-paper-plane"}] "Projects"]]
     [:li [:a {:class "gap-2" :href "/timesheets"} [:span {:class "far fa-plus-square"}] "Upload"]]
     [:li [:a {:class "gap-2" :href "/reload"} [:span {:class "far fa-save"}] "Reload"]]
     [:li [:a {:class "gap-2" :href "/config"} [:span {:class "far fa-file-code"}] "Configuration"]]]]
   [:div {:id "account-nav"
          :class "hidden w-full basis-full pt-3 md:hidden"}
    [:ul {:class "menu rounded-box bg-base-100 p-2 shadow"}
     [:li [:a {:class "gap-2" :href "/persons/list"} [:span {:class "far fa-address-card"}] "Personnel"]]
     [:li [:a {:class "gap-2" :href "/projects/list"} [:span {:class "far fa-paper-plane"}] "Projects"]]
     [:li [:a {:class "gap-2" :href "/timesheets"} [:span {:class "far fa-plus-square"}] "Upload"]]
     [:li [:a {:class "gap-2" :href "/reload"} [:span {:class "far fa-save"}] "Reload"]]
     [:li [:a {:class "gap-2" :href "/config"} [:span {:class "far fa-file-code"}] "Configuration"]]]]])

(defn render-footer []
  [:footer {:class "mx-auto mt-12 w-full max-w-screen-2xl border-t border-base-300 px-4 py-8 md:px-6"}
   [:div {:class "grid gap-6 md:grid-cols-3 md:items-end"}
    [:div {:class "footer"}
     [:img {:src "/static/img/AGPLv3.png" :class "h-auto max-w-40"
            :alt "Affero GPLv3 License"
            :title "Affero GPLv3 License"}]]
    [:div {:class "footer"}
    [:a {:href "https://www.dyne.org"}
     [:img {:src "/static/img/swbydyne.png"
            :alt   "Software by Dyne.org"
            :title "Software by Dyne.org"}]]]
    [:div {:class "footer text-sm text-base-content/70"}
     "For enquiries please contact Manuela Annibali &lt;manuela@dyne.org&gt;"]]])

;; highlight functions do no conversion, take the format they highlight
;; render functions take edn and convert to the highlight format
;; download functions all take an edn and convert it in target format
;; edit functions all take an edn and present an editor in the target format


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
  [:div {:class "space-y-4"}
   [:textarea {:class "textarea textarea-bordered min-h-80 w-full font-mono text-sm"
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
   [:input {:class "btn btn-success btn-lg"
            :type "submit" :value "submit"}]])

(defn render-git-log
  "list the last 20 git commits in the budget repo"
  [repo]
  [:div {:class "commitlog"}
   (->> (git/git-log repo)
        (map #(gitq/commit-info repo %))
        (map #(select-keys % [:author :message :time :changed_files]))
        (take 20) render-yaml)])

(defonce readme
  (slurp (io/resource "public/static/README.html")))

(defonce login-form
  [:div {:class "mx-auto max-w-lg"}
   [:div {:class "card bg-base-100 shadow-xl"}
    [:div {:class "card-body gap-4"}
     [:h1 {:class "card-title text-3xl"} "Login into Agiladmin"]
     [:form {:action "/login"
             :method "post"
             :class "space-y-4"}
      [:input {:type "text" :name "email"
               :placeholder "Email"
               :class "input input-bordered w-full"}]
      [:input {:type "password" :name "password"
               :placeholder "Password"
               :class "input input-bordered w-full"}]
      [:input {:type "submit" :value "Login"
               :class "btn btn-primary btn-lg w-full"}]]]]])

(defonce signup-form
  [:div {:class "mx-auto max-w-lg"}
   [:div {:class "card bg-base-100 shadow-xl"}
    [:div {:class "card-body gap-4"}
     [:h1 {:class "card-title text-3xl"} "Sign Up Agiladmin"]
     [:form {:action "/signup"
             :method "post"
             :class "space-y-4"}
      [:input {:type "text" :name "name"
               :placeholder "Display name"
               :class "input input-bordered w-full"}]
      [:input {:type "text" :name "email"
               :placeholder "Email"
               :class "input input-bordered w-full"}]
      [:input {:type "password" :name "password"
               :placeholder "Password"
               :class "input input-bordered w-full"}]
      [:input {:type "password" :name "repeat-password"
               :placeholder "Repeat password"
               :class "input input-bordered w-full"}]
      [:input {:type "submit" :value "Sign Up"
               :class "btn btn-primary btn-lg w-full"}]]]]])
