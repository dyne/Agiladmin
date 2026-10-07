(ns agiladmin.view-work-test
  (:use midje.sweet)
  (:require [agiladmin.view-work :as view]
            [agiladmin.handlers :as handlers]
            [agiladmin.ring :as ring]
            [agiladmin.work-preview :as preview]
            [agiladmin.work-ports :as ports]
            [agiladmin.work-service :as service]
            [agiladmin.mcp-tools-test :as fixture]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [failjure.core :as f])
  (:import [java.nio.file Files] [java.time Instant]))

(defn request [method path owner]
  {:request-method method :uri path :headers {} :session {:auth {:id (:owner-id owner) :name (:person owner)}}})
(defn setup [run]
  (fixture/fixture
   (fn [env]
     (fixture/save env "review-seed" 0 [(assoc fixture/entry :note "<script>alert('x')</script> =literal 😀\ncomplete note") fixture/second-entry])
     (let [value (preview/create! (get-in env [:runtime :preview-store]) (get-in env [:runtime :ledger])
                                  (get-in env [:runtime :workbook]) (:owner env)
                                  ((get-in env [:runtime :deps :settings])) "2024-02")
           uri (str "/work/review/" (:preview-id value))]
       (with-redefs [ring/config (:config env) handlers/mcp-state (atom (:runtime env))]
         (run (assoc env :value value :uri uri)))))))
(defn get-review [env] (handlers/work-handler (request :get (:uri env) (:owner env))))
(defn confirmation [env get-response]
  (-> (request :post (str (:uri env) "/confirm") (:owner env))
      (assoc :session (:session get-response)
             :form-params {"__anti-forgery-token" (get-in get-response [:session :ring.middleware.anti-forgery/anti-forgery-token])}
             :params (assoc (view/approval-fields (:value env))
                            "__anti-forgery-token" (get-in get-response [:session :ring.middleware.anti-forgery/anti-forgery-token])))))

(fact "Review exposes exact totals, complete escaped notes, owner download and prefixed confirmation"
  (setup
   (fn [env]
     (let [result (get-review env) body (:body result)]
       (:status result) => 200
       (str/includes? body "8.00 h") => true
       (str/includes? body "2.00 h") => true
       (str/includes? body "&lt;script&gt;") => true
       (str/includes? body "<script>alert") => false
       (str/includes? body "complete note") => true
       (str/includes? body "Exact workbook changes") => true
       (str/includes? body "4 / 7 columns") => true
       (str/includes? body (str "/payroll" (:uri env) "/confirm")) => true
       (str/includes? body "failed push") => true
       (str/includes? body "__anti-forgery-token") => true
       (get-in result [:headers "Cache-Control"]) => "private, no-store"
       (:status (handlers/work-handler (request :get (str (:uri env) "/download") (:owner env)))) => 200
       (:status (handlers/work-handler (request :get (:uri env) (:other env)))) => 404
       (:status (handlers/work-handler (request :get (str (:uri env) "/download") (:other env)))) => 404))))

(fact "Bearer alone, cross-owner POST, CSRF omissions and tampered evidence never call publication"
  (setup
   (fn [env]
     (let [calls (atom [])
           publication (reify ports/WorkPublication
                         (publication-status [_ _ _] {:state "draft"})
                         (publish-confirmed! [_ owner value] (swap! calls conj [owner value]) {:state "pushed"}))
           runtime (assoc (:runtime env) :publication publication)
           get-response (get-review env)
           req (confirmation env get-response)]
       (with-redefs [handlers/mcp-state (atom runtime)]
         (:status (handlers/work-handler (dissoc req :form-params))) => 403
         (:status (handlers/work-handler (assoc req :session {} :headers {"authorization" (str "Bearer " (get-in env [:admin :token]))}))) => 403
         (:status (handlers/work-handler (assoc-in req [:session :auth :id] (:owner-id (:other env))))) => 404
         (:status (handlers/work-handler (assoc req :session (assoc (:session req) :auth
                                                                       {:id (:owner-id (:other env)) :name (:person (:owner env)) :role "admin"})))) => 404
         (doseq [k (keys (view/approval-fields (:value env)))]
           (:status (handlers/work-handler (assoc-in req [:params k] "tampered"))) => 409)
         @calls => []
         (:status (handlers/work-handler req)) => 200
         (count @calls) => 1
         (second (first @calls)) => (:value env))))))

(fact "Draft changes, policy changes and external files disable confirmation and require fresh review"
  (setup
   (fn [env]
     (let [req (confirmation env (get-review env))]
       (fixture/save env "review-change" 1 [(assoc fixture/entry :minutes 300)])
       (:status (handlers/work-handler req)) => 409
       (str/includes? (:body (get-review env)) "A new review is required") => true
       (str/includes? (:body (get-review env)) "Confirm and publish month") => false
       (let [fresh (handlers/work-handler (-> req (assoc :uri (str (:uri env) "/refresh"))))]
         (:status fresh) => 303
         (str/starts-with? (get-in fresh [:headers "Location"]) "/payroll/work/review/") => true))))
  (setup
   (fn [env]
     (let [req (confirmation env (get-review env))]
       (swap! (:config env) assoc-in [:agiladmin :mcp :paid-cap-minutes] 300)
       (:status (handlers/work-handler req)) => 409)))
  (setup
   (fn [env]
     (let [req (confirmation env (get-review env))]
       (swap! (:config env) assoc-in [:agiladmin :mcp :enabled] false)
       (:status (handlers/work-handler req)) => 503)))
  (setup
   (fn [env]
     (let [req (confirmation env (get-review env))]
       (with-open [out (io/output-stream (io/file (:budgets env) (:filename (:value env))))]
         (.write out (byte-array [1 2 3])))
       (:status (handlers/work-handler req)) => 409)) ))

(fact "Expired and overflow drafts retain full owner data without a Confirm action"
  (setup
   (fn [env]
     (let [expired (assoc (:value env) :expires-at (str (.minusSeconds (Instant/now) 1)))]
       (with-redefs [preview/read-preview (fn [& _] expired)]
         (:status (get-review env)) => 409
         (str/includes? (:body (get-review env)) "Confirm and publish month") => false))))
  (setup
   (fn [env]
     (fixture/save env "overflow" 1
                   (mapv (fn [p] {:external_id (str "extra-" p) :date "2024-02-28" :project_id (str p) :minutes 60}) "CDEFGH"))
     (let [result (handlers/work-handler (request :get "/work/month/2024-02" (:owner env)))]
       (:status result) => 200
       (str/includes? (:body result) "Too many Excel assignments") => true
       (str/includes? (:body result) "complete draft is retained") => true
       (str/includes? (:body result) "10 / 7 columns") => true
       (str/includes? (:body result) "Confirm and publish month") => false))))
