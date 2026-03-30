(ns agiladmin.main
  (:gen-class)
  (:require [agiladmin.handlers :as handlers]
            [agiladmin.ring :as ring]
            [ring.adapter.jetty :refer [run-jetty]]))

(defn- server-options []
  (let [webserver (get-in @ring/config [:agiladmin :webserver] {})]
    {:port (int (get webserver :port 8000))
     :host (get webserver :host "localhost")
     :join? true}))

(defn -main [& _]
  (ring/init)
  (handlers/init-app!)
  (let [{:keys [host port] :as options} (server-options)]
    (println (str "Starting jetty server on http://" host ":" port))
    (run-jetty handlers/app options)))
