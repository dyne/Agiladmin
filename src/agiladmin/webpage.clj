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
            [agiladmin.version :as version]
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
(declare filterable-button-list)

(defn icon
  ([name] (icon name ""))
  ([name extra-class]
   (let [base-props {:xmlns "http://www.w3.org/2000/svg"
                     :fill "none"
                     :viewBox "0 0 24 24"
                     :stroke "currentColor"
                     :stroke-width "1.5"
                     :class (str "inline-block h-5 w-5 shrink-0 align-middle text-current " extra-class)
                     :aria-hidden "true"}]
     (case name
       :user-circle
       [:svg base-props
        [:path {:stroke-linecap "round"
                :stroke-linejoin "round"
                :d "M17.982 18.725A7.488 7.488 0 0 0 12 15.75a7.488 7.488 0 0 0-5.982 2.975m11.963 0a9 9 0 1 0-11.963 0m11.963 0A8.966 8.966 0 0 1 12 21a8.966 8.966 0 0 1-5.982-2.275M15 9.75a3 3 0 1 1-6 0 3 3 0 0 1 6 0Z"}]]
       :paper-airplane
       [:svg base-props
        [:path {:stroke-linecap "round"
                :stroke-linejoin "round"
                :d "M6 12 3.269 3.125A59.769 59.769 0 0 1 21.485 12 59.768 59.768 0 0 1 3.27 20.875L5.999 12Zm0 0h7.5"}]]
       :plus
       [:svg base-props
        [:path {:stroke-linecap "round"
                :stroke-linejoin "round"
                :d "M12 4.5v15m7.5-7.5h-15"}]]
       :arrow-path
       [:svg base-props
        [:path {:stroke-linecap "round"
                :stroke-linejoin "round"
                :d "M16.023 9.348h4.992V4.356m-4.992 4.992a9 9 0 0 0-15.591 2.129m15.591-2.129A8.966 8.966 0 0 0 12 6c-2.042 0-3.926.68-5.432 1.824m0 0V3.75M7.5 7.824H2.508m0 0a9 9 0 0 0 15.59 2.128m-15.59-2.128A8.966 8.966 0 0 1 12 18c2.042 0 3.926-.68 5.432-1.824m0 0v4.074m0-4.074h4.992"}]]
       :document-text
       [:svg base-props
        [:path {:stroke-linecap "round"
                :stroke-linejoin "round"
                :d "M19.5 14.25v-2.625a3.375 3.375 0 0 0-3.375-3.375h-1.5A1.125 1.125 0 0 1 13.5 7.125v-1.5A3.375 3.375 0 0 0 10.125 2.25H6.75A2.25 2.25 0 0 0 4.5 4.5v15A2.25 2.25 0 0 0 6.75 21.75h10.5a2.25 2.25 0 0 0 2.25-2.25V14.25Z"}]
        [:path {:stroke-linecap "round"
                :stroke-linejoin "round"
                :d "M8.25 12h7.5m-7.5 3h4.5"}]]
       :bars-3
       [:svg base-props
        [:path {:stroke-linecap "round"
                :stroke-linejoin "round"
                :d "M3.75 6.75h16.5m-16.5 5.25h16.5m-16.5 5.25h16.5"}]]
       :moon
       [:svg base-props
        [:path {:stroke-linecap "round"
                :stroke-linejoin "round"
                :d "M21.752 15.002A9.718 9.718 0 0 1 18 15.75c-5.385 0-9.75-4.365-9.75-9.75 0-1.33.266-2.597.748-3.752A9.753 9.753 0 1 0 21.752 15.002Z"}]]
       :sun
       [:svg base-props
        [:path {:stroke-linecap "round"
                :stroke-linejoin "round"
                :d "M12 3v1.5m0 15V21m9-9h-1.5M4.5 12H3m15.364 6.364-1.06-1.06M6.697 6.697 5.636 5.636m12.728 0-1.06 1.06M6.697 17.303l-1.06 1.06M15 12a3 3 0 1 1-6 0 3 3 0 0 1 6 0Z"}]]
       :face-frown
       [:svg base-props
        [:path {:stroke-linecap "round"
                :stroke-linejoin "round"
                :d "M15.182 15.182a4.5 4.5 0 0 0-6.364 0m6.364 0A9 9 0 1 0 8.818 8.818a9 9 0 0 0 6.364 6.364ZM9.75 9.75h.008v.008H9.75V9.75Zm4.5 0h.008v.008h-.008V9.75Z"}]]
       [:svg base-props]))))

(defn button
  ([url text] (button url text [:p]))

  ([url text field] (button url text field "btn btn-primary"))

  ([url text field type]
   (let [fields (cond
                  (nil? field) []
                  (and (seq? field) (every? vector? field)) field
                  :else [field])
         form-class (str "inline-flex max-w-full"
                         (when (re-find #"(?:^|\s)w-full(?:\s|$)" type)
                           " w-full"))]
   (apply hf/form-to
          {:class form-class} [:post url]
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

(defn filterable-button-list
  [id title empty-text items]
  [:section {:class "card bg-base-100 shadow-sm"}
   [:div {:class "card-body gap-4"}
    [:div {:class "flex flex-wrap items-center justify-between gap-3"}
     [:h2 title]
     [:div {:class "join w-full sm:w-auto"
            :data-text-filter id}
      [:input {:type "text"
               :class "input input-bordered join-item w-full sm:w-72"
               :placeholder (str "Filter " (clojure.string/lower-case title))
               :aria-label (str "Filter " title)
               :autocomplete "off"
               :data-text-filter-input "true"}]
      [:button {:type "button"
                :class "btn btn-outline join-item"
                :aria-label (str "Clear " title " filter")
                :data-text-filter-clear "true"}
       "Clear"]]]
    (if (seq items)
      (into
       [:div {:id id
              :class "grid gap-2 sm:grid-cols-2 xl:grid-cols-3"
              :data-text-filter-list id
              :data-empty-text empty-text}
        [:p {:class "hidden rounded-box border border-dashed border-base-300 px-4 py-6 text-sm text-base-content/70"
             :data-text-filter-empty "true"}
         empty-text]]
       items)
      [:p {:class "rounded-box border border-dashed border-base-300 px-4 py-6 text-sm text-base-content/70"}
       empty-text])]])

(defn- nav-menu
  [links]
  (for [{:keys [href icon label]} links
        :let [icon-name icon]]
    [:li [:a {:class "gap-2" :href href}
          (icon icon-name "h-5 w-5") label]]))

(defn- admin-role?
  [account]
  (= "admin" (:role account)))

(defn- manager-role?
  [account]
  (= "manager" (:role account)))

(defn- project-access?
  [account]
  (or (admin-role? account)
      (manager-role? account)))

(defn- account-nav-links
  [account]
  (cond-> []
    (project-access? account)
    (conj {:href "/persons/list" :icon :user-circle :label "Personnel"}
          {:href "/projects/list" :icon :paper-airplane :label "Projects"})

    (admin-role? account)
    (conj {:href "/persons/list" :icon :user-circle :label "Personnel"}
          {:href "/reload" :icon :arrow-path :label "Reload"}
          {:href "/config" :icon :document-text :label "Configuration"})

    true
    (conj {:href "/logout" :icon :user-circle :label "Logout"})))

(defn- account-home-href
  [account]
  (if (project-access? account)
    "/"
    "/persons/list"))

(defn- theme-toggle
  []
  [:label {:class "swap swap-rotate btn btn-ghost btn-circle"
           :aria-label "Toggle dark mode"
           :title "Toggle dark mode"}
   [:input {:type "checkbox"
            :data-theme-toggle "true"
            :aria-label "Toggle dark mode"}]
   [:span {:class "swap-off"} (icon :moon "h-5 w-5")]
   [:span {:class "swap-on"} (icon :sun "h-5 w-5")]])

(defn- navbar
  [toggle-id links home-href]
  [:nav
   {:class "sticky top-0 z-40 border-b border-base-300 bg-base-100/90 shadow-sm backdrop-blur"}
   [:div {:class "mx-auto flex min-h-0 w-full max-w-screen-2xl items-center justify-between px-4 py-2 md:hidden md:px-6"}
    [:a {:class "flex items-center gap-2 no-underline"
         :href home-href}
     [:span {:class "flex h-7 w-7 items-center justify-center overflow-hidden rounded-full border border-base-300/50 bg-base-100 shadow-sm"}
      [:img {:src "/static/img/dyne-icon-black.svg"
             :class "h-[1.2rem] w-[1.2rem] object-contain"
             :alt "Dyne icon"
             :data-theme-invert "true"}]]
     [:div {:class "leading-tight"}
      [:div {:class "text-sm font-semibold tracking-wide"} "Agiladmin"]
      [:div {:class "text-[10px] uppercase tracking-[0.25em] text-base-content/60"}
       (str "v" version/current)]]]
    [:button {:type "button"
              :class "btn btn-ghost btn-square"
              :data-nav-toggle toggle-id
              :aria-controls toggle-id
              :aria-expanded "false"
              :aria-label "Toggle navigation"}
     (icon :bars-3 "h-5 w-5")]]
   [:div {:class "mx-auto hidden min-h-0 w-full max-w-screen-2xl items-center px-4 py-2 md:flex md:px-6"}
    [:div {:class "flex flex-1 justify-start"}
     [:a {:class "flex items-center gap-3 no-underline"
          :href home-href}
      [:span {:class "flex h-7 w-7 items-center justify-center overflow-hidden rounded-full border border-base-300/50 bg-base-100 shadow-sm"}
       [:img {:src "/static/img/dyne-icon-black.svg"
              :class "h-[1.2rem] w-[1.2rem] object-contain"
              :alt "Dyne icon"
              :data-theme-invert "true"}]]
      [:div {:class "leading-tight"}
       [:div {:class "text-sm font-semibold tracking-wide"} "Agiladmin"]
       [:div {:class "text-[10px] uppercase tracking-[0.25em] text-base-content/60"}
        (str "v" version/current)]]]]
    [:div {:class "flex flex-1 justify-center"}
     (theme-toggle)]
    [:div {:class "flex flex-1 justify-end"}
     (into [:ul {:class "menu menu-horizontal items-center gap-2 px-1"}]
           (nav-menu links))]]
   [:div {:id toggle-id
          :class "mx-auto hidden w-full max-w-screen-2xl px-4 pb-4 md:hidden"}
    [:div {:class "rounded-box border border-base-300 bg-base-100 p-3 shadow-sm"}
     [:div {:class "mb-3 flex justify-end"}
      (theme-toggle)]
     (into [:ul {:class "menu gap-1"}]
           (nav-menu links))]]])


(defn render
  ([body]
  {:headers {"Content-Type"
             "text/html; charset=utf-8"}
   :body (page/html5
          (render-head)
          [:body {:data-theme "nord"
                  :data-theme-light "nord"
                  :data-theme-dark "dim"
                  :class "min-h-screen bg-base-200 text-base-content"}
           navbar-guest
           [:main {:class "mx-auto w-full max-w-screen-2xl px-4 pb-12 pt-6 md:px-6"} body]
           (render-footer)
           [:div {:data-page-loading "true"
                  :aria-live "polite"
                  :style "position:fixed;inset:0;z-index:2147483647;display:none;align-items:center;justify-content:center;background:rgba(46,52,64,0.22);backdrop-filter:blur(10px);-webkit-backdrop-filter:blur(10px);"}
            [:div {:style "display:flex;flex-direction:column;align-items:center;gap:1rem;padding:1.5rem 2rem;border:1px solid rgba(216,222,233,0.45);border-radius:1rem;background:rgba(236,239,244,0.78);box-shadow:0 20px 50px rgba(46,52,64,0.18);"}
             [:span {:class "loading loading-spinner loading-lg text-primary"}]
             [:span {:class "text-sm font-semibold tracking-wide"} "Loading"]]]])})
  ([account body]
   {:headers {"Content-Type"
              "text/html; charset=utf-8"}
    :body (page/html5
           (render-head)
           [:body {:data-theme "nord"
                   :data-theme-light "nord"
                   :data-theme-dark "dim"
                   :class "min-h-screen bg-base-200 text-base-content"}
            (if (empty? account)
              navbar-guest
              (navbar-account account))
            [:main {:class "mx-auto w-full max-w-screen-2xl px-4 pb-12 pt-6 md:px-6"} body]
            (render-footer)
            [:div {:data-page-loading "true"
                   :aria-live "polite"
                   :style "position:fixed;inset:0;z-index:2147483647;display:none;align-items:center;justify-content:center;background:rgba(46,52,64,0.22);backdrop-filter:blur(10px);-webkit-backdrop-filter:blur(10px);"}
             [:div {:style "display:flex;flex-direction:column;align-items:center;gap:1rem;padding:1.5rem 2rem;border:1px solid rgba(216,222,233,0.45);border-radius:1rem;background:rgba(236,239,244,0.78);box-shadow:0 20px 50px rgba(46,52,64,0.18);"}
              [:span {:class "loading loading-spinner loading-lg text-primary"}]
              [:span {:class "text-sm font-semibold tracking-wide"} "Loading"]]]])}))


(defn render-error
  "render an error message without ending the page"
  [err]
  [:div {:class "alert alert-error mb-4 shadow-sm" :role "alert"}
   (icon :face-frown "h-5 w-5")
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
    (page/include-css "/static/css/agiladmin.css")]))

(def navbar-guest
  (navbar "guest-nav"
          [{:href "/login"
            :icon :user-circle
            :label "Login"}]
          "/"))

(defn navbar-account
  [account]
  (navbar "account-nav"
          (account-nav-links account)
          (account-home-href account)))

(defn render-footer []
  [:footer {:class "mt-16 border-t border-base-300 bg-base-100/80"}
   [:div {:class "mx-auto flex w-full max-w-screen-2xl flex-col gap-6 px-4 py-8 md:flex-row md:items-center md:justify-between md:px-6"}
    [:a {:href "https://www.dyne.org"
         :class "inline-flex items-center"}
     [:img {:src "/static/img/dyne-logotype-black.svg"
            :class "h-10 w-auto"
            :alt "Dyne.org"
            :data-theme-logo "true"
            :data-theme-logo-light "/static/img/dyne-logotype-black.svg"
            :data-theme-logo-dark "/static/img/dyne-logotype-white.svg"}]]
    [:p
     "Software By Denis \"Jaromil\" Roio and Manuela Annibali<br/>"
     "Copyright (C) 2016-2026 by the Dyne.org Foundation"]
    [:div {:class "flex items-center gap-4 self-start md:self-auto"}
     [:img {:src "/static/img/AGPLv3.png"
            :class "h-auto max-w-32 opacity-80"
            :alt "Affero GPLv3 License"
            :title "Affero GPLv3 License"}]]]])

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
