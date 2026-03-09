(ns build
  (:require [clojure.tools.build.api :as b]))

(def lib 'agiladmin/agiladmin)
(def version "0.4.0-SNAPSHOT")
(def class-dir "target/classes")
(def basis (delay (b/create-basis {:project "deps.edn"})))
(def uber-file (format "target/%s-standalone.jar" version))

(defn clean [_]
  (b/delete {:path "target"})
  nil)

(defn uber [_]
  (clean nil)
  (b/copy-dir {:src-dirs ["src" "resources"]
               :target-dir class-dir})
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis @basis
           :main 'agiladmin.main})
  (println uber-file))
