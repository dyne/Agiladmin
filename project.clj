(defproject agiladmin "0.1.0-SNAPSHOT"
  :description "Agile Administration for SME"
  :url "http://agiladmin.dyne.org"
  :license {:name "Affero GPL v3"}
  :dependencies ^:replace [[org.clojure/clojure "1.8.0"]
                           [org.clojure/data.json "0.2.6"]
                           [org.clojure/data.csv "0.1.3"]
                           [clj-http "3.4.1"]
                           [cheshire "5.6.3"]
                           [clojure-humanize "0.2.2"]

                           ;; compojure, ring and middleware
                           [compojure "1.5.2"]
                           [ring/ring-defaults "0.2.3"]
                           [ring-middleware-accept "2.0.3"]
                           ;; aux web stuff
                           [formidable "0.1.10"]
                           [markdown-clj "0.9.98"]
                           [json-html "0.4.0"]

                           ;; spreadsheet
                           [dk.ative/docjure "1.11.0"]
                           [org.apache.poi/poi "3.15"]
                           [org.apache.poi/poi-ooxml "3.15"]

                           ;; git
                           [clj-jgit "0.9.1-SNAPSHOT"]

                           ]
  :source-paths ["src"]
  :resource-paths ["resources"]
  :template-additions ["ws/index.clj"]
  :main ^:skip-aot agiladmin.core
  :profiles {
             :uberjar {:aot :all}
             :dev {:dependencies [[midje "1.8.3"]]
                   :plugins [[lein-midje "3.1.3"]]}}

  :target-path "target/%s"
  :plugins [[lein-ring "0.9.7"]]
  :ring {:handler agiladmin.handlers/app}

  )
