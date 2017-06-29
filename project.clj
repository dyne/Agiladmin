(defproject agiladmin "0.2.0-SNAPSHOT"
  :description "Agile Administration for SME"
  :url "http://agiladmin.dyne.org"
  :license {:name "Affero GPL v3"}
  :dependencies ^:replace [[org.clojure/clojure "1.8.0"]
                           [org.clojure/data.json "0.2.6"]
                           [org.clojure/data.csv "0.1.4"]
                           [org.clojure/data.codec "0.1.0"]
                           [org.clojure/tools.nrepl "0.2.13"]
                           [clj-http "3.6.1"]
                           [cheshire "5.7.1"]
                           [clojure-humanize "0.2.2"]

                           ;; compojure, ring and middleware
                           [compojure "1.6.0"]
                           [ring/ring-defaults "0.3.0"]
                           [ring-middleware-accept "2.0.3"]
				           [ring/ring-core "1.6.1"]
				           [ring/ring-jetty-adapter "1.6.1"]

                           ;; aux web stuff
                           [formidable "0.1.10"]
                           [markdown-clj "0.9.99"]
                           [json-html "0.4.4"]

                           ;; spreadsheet
                           [dk.ative/docjure "1.11.0"]
                           [org.apache.poi/poi "3.16"]
                           [org.apache.poi/poi-ooxml "3.16"]

                           ;; git
                           [clj-jgit "0.9.1-SNAPSHOT"]

                           ;; graphical visualization
                           [incanter "1.5.7"]

                           [org.clojars.dyne/clj-openssh-keygen "0.1.0"]

                           ;; javafx application wrapper
                           [org.clojars.metal-slime/javafx2.2.0 "2.2.0"]

                           ]

  :source-paths ["src"]
  :resource-paths ["resources"]

  :plugins [[lein-ring "0.9.7"]]
  :ring    {:handler agiladmin.handlers/app}
  :uberwar {:handler agiladmin.handlers/app}

  :main agiladmin.handlers
  :target-path "target/%s"
  :profiles
  { :dev {:dependencies [[javax.servlet/servlet-api "2.5"]
                         [ring/ring-mock "0.3.0"]
                         [midje "1.8.3"]]
          :plugins [[lein-midje "3.1.3"]]
          :aot :all
          :main agiladmin.handlers}

   :uberjar {:aot  :all
             :main agiladmin.app}}


  )
