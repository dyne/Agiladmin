(ns agiladmin.utils-test
  (:use midje.sweet)
  (:require [agiladmin.utils :refer :all]
            [auxiliary.string :refer [strcasecmp]]
            [clojure.pprint :refer :all]))

(def nameorig "Luca Pacioli")
(def namecase "luca pacioli")
(def namedot  "L. Pacioli")
(def namecasedot "l. pacioli")
(def namesspace " Luca    Pacioli ")
(def namespacedot "L.  Pacioli ")
(def namespacecase " luca   pacioli")
(def namespacecasedot "l.   pacioli")   

(fact "Case insensitive string compare"
      (strcasecmp nameorig namecase) => true
      (strcasecmp nameorig "luca")   => false
      (strcasecmp namecase nameorig) => true
      (strcasecmp "luca" nameorig)   => false)


(fact "Dotted name comparison"
      (fact "case" (namecmp nameorig namecase) => true)
      (fact "dotted" (namecmp nameorig namedot)  => true)
      (fact "case dotted" (namecmp nameorig namecasedot) => true)
      (fact "space" (namecmp nameorig namesspace) => true)
      (fact "space dotted" (namecmp nameorig namespacedot) => true)
      (fact "space case" (namecmp nameorig namespacecase) => true)
      (fact "space case dot" (namecmp nameorig namespacecasedot) => true)

      (fact "reverse"
            (fact "case" (namecmp  namecase nameorig) => true)
            (fact "dotted" (namecmp namedot nameorig)  => true)
            (fact "case dotted" (namecmp namecasedot nameorig) => true)
            (fact "space" (namecmp namesspace nameorig) => true)
            (fact "space dotted" (namecmp namespacedot nameorig) => true)
            (fact "space case" (namecmp namespacecase nameorig) => true)
            (fact "space case dot" (namecmp namespacecasedot nameorig) => true)
            ))

(fact "Timesheet to name conversion"
      (fact "full name"
            (timesheet-to-name "2017_timesheet_Luca-Pacioli.xlsx") => "Luca-Pacioli")
      (fact "dotted name"
            (dotname (timesheet-to-name "2017_timesheet_Luca-Pacioli.xlsx")) => "L.Pacioli")
      )
