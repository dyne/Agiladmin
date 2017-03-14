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

                           ;; gorilla-repl deps
                           [http-kit "2.2.0"]
                           [ring/ring-json "0.4.0"]
                           [compojure "1.5.1"]
                           [org.slf4j/slf4j-api "1.7.22"]
                           [ch.qos.logback/logback-classic "1.1.8"]
                           [gorilla-renderable "2.0.0"]
                           [gorilla-plot "0.1.4"]
                           [javax.servlet/servlet-api "2.5"]
                           [grimradical/clj-semver "0.3.0" :exclusions [org.clojure/clojure]]
                           [cider/cider-nrepl "0.14.0"]
                           [org.clojure/tools.nrepl "0.2.12"]

                           ;; spreadsheet
                           [dk.ative/docjure "1.11.0"]
                           [org.apache.poi/poi "3.15"]
                           [org.apache.poi/poi-ooxml "3.15"]

                           [clj-jgit "0.8.9"]
                           ]
  :source-paths ["src"]
  :resource-paths ["resources"]
  :template-additions ["ws/index.clj"]
  :main ^:skip-aot agiladmin.core
  :profiles {:uberjar {:aot [gorilla-repl.core agiladmin.core]}}
  :target-path "target/%s"
  )
