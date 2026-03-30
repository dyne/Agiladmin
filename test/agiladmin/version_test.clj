(ns agiladmin.version-test
  (:require [agiladmin.version :as version]
            [midje.sweet :refer :all]))

(fact "Version reads the baked classpath resource when present"
      (let [tmp (doto (java.io.File/createTempFile "agiladmin-version" ".edn")
                  (spit "{:version \"9.9.9\"}"))]
        (with-redefs [clojure.java.io/resource
                      (fn [path]
                        (when (= path "agiladmin/version.edn")
                          (.toURL (.toURI tmp))))]
          (#'agiladmin.version/resource-version) => "9.9.9")))
