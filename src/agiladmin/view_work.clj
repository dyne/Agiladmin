(ns agiladmin.view-work
  "Session-only owner review. Approval evidence is server-retained and rechecked
  again by the publication port under mutation locks; bearer auth never enters."
  (:require [agiladmin.webpage :as web]
            [agiladmin.mcp.credentials :as credentials]
            [agiladmin.work-preview :as preview]
            [agiladmin.work-service :as service]
            [agiladmin.work-policy :as policy]
            [agiladmin.work-ports :as ports]
            [clojure.string :as str]
            [hiccup.util :as html]
            [ring.middleware.anti-forgery :as csrf]
            [failjure.core :as f])
  (:import [java.io ByteArrayInputStream] [java.net URLEncoder]))

;; Hiccup 1 treats content strings as raw HTML. All domain/client text crosses
;; this explicit escaping boundary, including values shown in the cell diff.
(defn- text [value] (html/escape-html (str value)))
(defn- hours [minutes] (format "%.2f h" (/ (double (or minutes 0)) 60.0)))
(defn- response [account status body]
  (-> (web/render account body)
      (assoc :status status)
      (update :headers merge {"Cache-Control" "private, no-store" "X-Content-Type-Options" "nosniff"})))
(defn- unavailable [account status message]
  (response account status [:div {:class "space-y-4"} [:h1 "Work review unavailable"] [:p message]]))
(defn browser-owner [request]
  (if-let [id (get-in request [:session :auth :id])]
    (credentials/resolve-owner id)
    (policy/error :owner-required [] "Sign in to review your own work." "Use your browser account, then follow the review link again.")))
(defn approval-fields [value]
  {:revision (str (get-in value [:snapshot :revision]))
   :policy_hash (get-in value [:snapshot :allocation :policy-hash])
   :source_fingerprint (get-in value [:source-inspection :fingerprint])
   :month_baseline (get-in value [:source-inspection :month-baseline])
   :artifact_fingerprint (:artifact-fingerprint value)
   :snapshot_digest (get-in value [:snapshot :digest])
   :preview_digest (:preview-digest value)})
(defn- param [request k] (or (get-in request [:params k]) (get-in request [:params (name k)])))
(defn- csrf-field [] [:input {:type "hidden" :name "__anti-forgery-token" :value (force csrf/*anti-forgery-token*)}])
(defn- table [label headings rows]
  [:div {:class "overflow-x-auto rounded-box bg-base-100" :tabindex "0" :role "region" :aria-label label}
   [:table {:class "table w-full tabular-nums"}
    [:caption {:class "sr-only"} label]
    [:thead [:tr (for [h headings] [:th {:scope "col"} h])]]
    [:tbody (for [row rows] [:tr (for [v row] [:td {:class "align-top whitespace-pre-wrap break-words"} (text v)])])]]])
(defn- draft-body [owner settings snapshot]
  (let [days (get-in snapshot [:allocation :days])
        totals (:totals snapshot)
        assignments (->> (mapcat :assignments days)
                         (group-by (juxt :project_id :task_id))
                         (sort-by key)
                         (map (fn [[[p t] values]] [p (or t "—")
                                                   (hours (reduce + (map :paid-minutes values)))
                                                   (hours (reduce + (map :vol-minutes values)))])))]
    [:div {:class "space-y-8"}
     [:section {:class "space-y-3" :aria-labelledby "work-totals"}
      [:h2 {:id "work-totals"} "Monthly totals"]
      [:dl {:class "flex flex-wrap gap-x-8 gap-y-3 tabular-nums"}
       (for [[label k] [["Recorded" :recorded-minutes] ["Paid" :paid-minutes] ["Volunteer (VOL)" :vol-minutes]]]
         [:div [:dt {:class "font-semibold"} label] [:dd (hours (k totals))]])]
      [:p (str "Paid cap: " (policy/paid-cap (merge policy/defaults settings) (:owner-id owner))
               " minutes per day across all projects. Excess is allocated proportionally as VOL; explicit volunteer work receives no paid hours.")]
      [:p "Reporting timezone: " (text (:timezone (merge policy/defaults settings))) ". Paid and VOL assignments each use one of the seven Excel columns."]
      [:p {:class "break-all text-sm"} "Policy: " (text (get-in snapshot [:allocation :policy-hash]))]]
     [:section {:class "space-y-3"} [:h2 "Project and task totals"]
      (table "Project and task totals" ["Project" "Task" "Paid" "VOL"] assignments)]
     [:section {:class "space-y-3"} [:h2 "Daily allocation"]
      (table "Daily work allocation" ["Date" "Project / task" "Paid" "VOL"]
             (for [day days a (:assignments day)]
               [(:date day) (str (:project_id a) (when (:task_id a) (str " / " (:task_id a))))
                (hours (:paid-minutes a)) (hours (:vol-minutes a))]))]
     [:section {:class "space-y-3"} [:h2 "Entries and complete notes"]
      [:p "Notes are plain text in Excel column I. Each entry remains in your draft."]
      (table "Entries and complete notes" ["Date" "Project / task" "Recorded" "Note"]
             (for [r (:records snapshot)] [(:date r) (str (:project_id r) (when (:task_id r) (str " / " (:task_id r))))
                                         (str (hours (:minutes r)) (when (:voluntary r) " · explicit VOL")) (:note r)]))]
     (when (seq (:possible-duplicate-ids snapshot))
       [:div {:class "alert alert-warning" :role "status"}
        [:p "Possible duplicate entries: " (text (str/join ", " (mapcat identity (:possible-duplicate-ids snapshot))))
         ". Review these entries; none have been deleted automatically."]])
     [:section {:class "space-y-3"} [:h2 "Excel column usage"]
      [:p (str (get-in snapshot [:capacity :required-columns]) " / 7 columns required.")]
      (table "Excel assignments" ["Project" "Task" "Tag" "Hours"]
             (for [a (get-in snapshot [:capacity :assignments])]
               [(:project_id a) (or (:task_id a) "—") (if (= "VOL" (:tag a)) "VOL" "Paid") (hours (:minutes a))]))]]))
(defn- review-page [request config account owner settings value failure]
  (let [snapshot (:snapshot value) id (:preview-id value)
        publication (:publication-status value)
        path (web/path config (str "/work/review/" id))]
    (response account (if failure 409 200)
      [:div {:class "work-review mx-auto max-w-5xl space-y-8"}
       [:header {:class "space-y-3"} [:h1 "Review " (text (:month snapshot)) " work"]
        [:p (text (:person owner)) " · Draft revision " (text (:revision snapshot))]
        [:p "Check your work and the exact spreadsheet changes before making this month official."]]
       (when failure
         [:section {:class "alert alert-warning" :role "alert"}
          [:div [:h2 "A new review is required"] [:p (text (f/message failure))]
           [:p "Your draft is retained. Create a fresh review after correcting entries or reconciling official workbook changes."]
           [:form {:action (str path "/refresh") :method "post" :class "mt-4"}
            (csrf-field) [:button {:type "submit" :class "btn btn-primary"} "Create fresh review"]]]])
       (when publication
         [:section {:class "alert alert-info" :role "status"}
          [:h2 "Publication: " (text (:state publication))]
          [:p (text (:next-action publication))]])
       (draft-body owner settings snapshot)
       [:section {:class "space-y-3"} [:h2 "Exact workbook changes"]
        [:p "Only this month changes; other months and the signature area are preserved. Empty cells are shown as blank."]
        (table "Exact Excel cell changes" ["Cell" "Before" "After"]
               (for [c (get-in value [:changes :cells])] [(:cell c) (:before c) (:after c)]))]
       [:section {:class "space-y-4" :aria-labelledby "work-confirmation"}
        [:h2 {:id "work-confirmation"} "Make this month official"]
        [:p "Confirm replaces your annual workbook with this reviewed version, commits only that workbook in the budgets repository, and attempts to push it to the configured remote. Local reports refresh as soon as the workbook changes. A failed push remains visible and can be retried without another commit."]
        [:p "VOL hours are retained separately. Confirmation applies only to the revision, policy, and workbook shown here."]
        [:p {:class "text-sm break-all"} "Review expires: " (text (:expires-at value)) " · Digest: " (text (:preview-digest value))]
        [:div {:class "flex flex-wrap gap-3"}
         [:a {:class "btn btn-outline" :href (str path "/download")} "Download reviewed workbook"]
         (when (and (not failure) (not= "pushed" (:state publication)))
           [:form {:action (str path "/confirm") :method "post"}
            (csrf-field)
            (for [[k v] (approval-fields value)] [:input {:type "hidden" :name (name k) :value (or v "")}])
            [:button {:type "submit" :class "btn btn-primary"}
             (if publication "Retry approved publication" "Confirm and publish month")]])]]])))
(defn review [request config runtime id]
  (let [account (get-in request [:session :auth]) owner (browser-owner request)]
    (cond
      (f/failed? owner) (unavailable account 403 "Sign in with the active account that owns this month.")
      (nil? runtime) (unavailable account 503 "Work capture is unavailable. Ask the operator to enable it.")
      :else
      (let [value (preview/read-preview (:preview-store runtime) owner id)
            settings ((get-in runtime [:deps :settings]))]
        (cond
          (f/failed? value) (unavailable account 404 "This review is unavailable for your account. Follow your own preview link.")
          (f/failed? settings) (unavailable account 503 "Work policy is unavailable. Ask the operator to restore valid settings.")
          :else (let [approved (when-let [read (:read-approval runtime)] (read owner value))
                      approved (when-not (f/failed? approved) approved)
                      verified (when-not approved (preview/verify-preview (:ledger runtime) (:workbook runtime) owner settings value))]
                  (review-page request config account owner settings (assoc value :publication-status approved)
                               (when (f/failed? verified) verified))))))))
(defn download [request runtime id]
  (let [owner (browser-owner request) account (get-in request [:session :auth])
        artifact (when (and runtime (not (f/failed? owner))) (preview/artifact (:preview-store runtime) owner id))]
    (if (or (f/failed? owner) (nil? artifact) (f/failed? artifact))
      (unavailable account 404 "Reviewed workbook unavailable. Sign in and create a fresh review.")
      {:status 200 :headers {"Content-Type" "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                            "Cache-Control" "private, no-store" "X-Content-Type-Options" "nosniff"
                            "Content-Disposition" (str "attachment; filename=\"timesheet.xlsx\"; filename*=UTF-8''"
                                                       (str/replace (URLEncoder/encode (:filename artifact) "UTF-8") "+" "%20"))}
       :body (ByteArrayInputStream. (:bytes artifact))})))
(defn confirm [request config runtime id]
  (let [account (get-in request [:session :auth]) owner (browser-owner request)]
    (cond
      (f/failed? owner) (unavailable account 403 "Only the signed-in owner can confirm this month.")
      (nil? runtime) (unavailable account 503 "Publication is unavailable.")
      :else
      (let [value (preview/read-preview (:preview-store runtime) owner id)
            settings ((get-in runtime [:deps :settings]))]
        (cond
          (f/failed? value) (unavailable account 404 "This review is unavailable for your account.")
          (f/failed? settings) (unavailable account 503 "Work policy is unavailable.")
          (not= (approval-fields value)
                 (into {} (for [k (keys (approval-fields value))] [k (let [v (param request k)] (when-not (= "" v) v))])))
          (unavailable account 409 "Confirmation does not match the reviewed workbook. Open the review again.")
          :else
          (let [approved (when-let [read (:read-approval runtime)] (read owner value))
                verified (if (and approved (not (f/failed? approved))) value
                             (preview/verify-preview (:ledger runtime) (:workbook runtime) owner settings value))]
            (if (f/failed? verified)
              (review-page request config account owner settings value verified)
              (if-let [publication (:publication runtime)]
                (let [result (ports/publish-confirmed! publication owner value)]
                  (if (f/failed? result)
                    (review-page request config account owner settings value result)
                    (response account 200 [:div {:class "space-y-4"} [:h1 "Month publication"]
                                           [:p "Publication state: " (text (:state result))]
                                           [:p (text (:next-action result))]
                                           (when (:retryable result)
                                             [:a {:class "btn btn-primary" :href (web/path config (str "/work/review/" id))} "Retry approved publication"])
                                           [:a {:class "btn btn-outline" :href (web/path config (str "/work/month/" (get-in value [:snapshot :month])))} "Review current month"]])))
                (unavailable account 503 "Publication is unavailable. Your draft and review are retained; ask the operator to restore the archive service.")))))))))
(defn refresh [request config runtime id]
  (let [owner (browser-owner request) account (get-in request [:session :auth])
        value (when (and runtime (not (f/failed? owner))) (preview/read-preview (:preview-store runtime) owner id))]
    (if (or (f/failed? owner) (nil? value) (f/failed? value))
      (unavailable account 404 "This review is unavailable for your account.")
      (let [settings ((get-in runtime [:deps :settings]))
            fresh (if (f/failed? settings) settings
                      (preview/create! (:preview-store runtime) (:ledger runtime) (:workbook runtime) owner settings (get-in value [:snapshot :month])))]
        (if (f/failed? fresh)
          (response account 409 [:div {:class "space-y-4"} [:h1 "A new review could not be created"]
                                 [:p (text (f/message fresh))]
                                 [:a {:class "btn btn-outline" :href (web/path config (str "/work/month/" (get-in value [:snapshot :month])))} "Review current draft"]])
          {:status 303 :headers {"Location" (web/path config (str "/work/review/" (:preview-id fresh))) "Cache-Control" "no-store"} :body ""})))))
(defn month [request config runtime month]
  (let [account (get-in request [:session :auth]) owner (browser-owner request)
        settings (when runtime ((get-in runtime [:deps :settings])))
        snapshot (when (and runtime (not (f/failed? owner)) (not (f/failed? settings)))
                   (service/month-snapshot (:ledger runtime) owner settings month))]
    (if (or (f/failed? owner) (nil? snapshot) (f/failed? snapshot))
      (unavailable account 404 "Your month draft is unavailable.")
      (response account 200
        [:div {:class "work-review mx-auto max-w-5xl space-y-8"} [:h1 "Draft " (text month)]
         (when-not (get-in snapshot [:capacity :exportable?])
           [:div {:class "alert alert-warning" :role "alert"} [:div [:h2 "Too many Excel assignments"]
            [:p "Your complete draft is retained. Export and confirmation are blocked. Explicitly consolidate assignments with your agent, then create a new preview; no hours or notes are discarded."]]])
         (draft-body owner settings snapshot)]))))
