(defproject agiladmin "0.4.0-SNAPSHOT"
  :description "Agile Administration for SMEs"
  :url "http://agiladmin.dyne.org"
  :license {:name "Affero GPL v3"}
  :dependencies
  ^:replace
  [[org.clojure/clojure "1.9.0"]
   [org.clojure/data.json "0.2.6"]
   [org.clojure/data.csv "0.1.4"]
   [org.clojure/data.codec "0.1.1"]
   [org.clojure/tools.nrepl "0.2.13"]
   [clj-http "3.9.1"]
   [cheshire "5.8.1"]
   [clojure-humanize "0.2.2"]

   ;; logging done right with slf4j
   [com.taoensso/timbre "4.10.0"]
   [com.fzakaria/slf4j-timbre "0.3.12"]
   [org.slf4j/slf4j-api "1.7.26"]
   [org.slf4j/log4j-over-slf4j "1.7.26"]
   [org.slf4j/jul-to-slf4j "1.7.26"]
   [org.slf4j/jcl-over-slf4j "1.7.26"]

   ;; error handling
   [failjure "1.3.0"]

   ;; auxiliary lib functions
   [org.clojars.dyne/auxiliary "0.5.0-SNAPSHOT"]
   [org.clojars.dyne/just-auth "0.4.0"]

   ;; compojure, ring and middleware
   [compojure "1.6.1"]
   [ring/ring-defaults "0.3.2"]
   [ring-middleware-accept "2.0.3"]
   [ring/ring-core "1.7.1"]
   [ring/ring-jetty-adapter "1.7.1"]

   ;; aux web stuff
   [formidable "0.1.10"]
   [markdown-clj "1.0.7"]
   [io.forward/yaml "1.0.9"]

   ;; spreadsheet
   ;; upgrade? see https://github.com/mjul/docjure/issues/82
   [dk.ative/docjure "1.12.0" :upgrade false]
   [org.apache.poi/poi "3.17" :upgrade false]
   [org.apache.poi/poi-ooxml "3.17" :upgrade false]

   ;; Data validation
   [prismatic/schema "1.1.10"]

   ;; git
   [clj-jgit "0.9.1-SNAPSHOT"]

   ;; filesystem utilities
   [me.raynes/fs "1.4.6"]

   ;; graphical visualization
   [incanter/incanter-core   "1.5.7" :upgrade false]
;;   [incanter/incanter-charts "1.5.7" :upgrade false]

   [org.clojars.dyne/clj-openssh-keygen "0.1.0"]

   ;; time from joda-time
   [clj-time "0.15.1"]

   ]

  :aliases {"test" "midje"}
  :source-paths ["src"]
  :resource-paths ["resources"]

  :plugins [[lein-ring "0.12.5"]]
  :ring    {:init agiladmin.ring/init
            :handler agiladmin.handlers/app}
  :uberwar {:init agiladmin.ring/init
            :handler agiladmin.handlers/app}
  
  :main agiladmin.handlers
  :target-path "target/%s"
  :profiles
  { :dev {:dependencies [[javax.servlet/servlet-api "2.5"]
                         [ring/ring-mock "0.3.2"]
                         [midje "1.9.6"]]
          :plugins [[lein-midje "3.1.3"]]
          :aot :all
          :main agiladmin.handlers}

   :uberjar {:aot  :all
             :main agiladmin.handlers}}


  )
