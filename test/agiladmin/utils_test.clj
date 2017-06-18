(ns agiladmin.utils-test
  (:use midje.sweet)
  (:require [agiladmin.utils :refer :all]
            [clojure.pprint :refer :all]))

(def name     "Luca Pacioli")
(def namecase "luca pacioli")
(def namedot  "L. Pacioli")
(def namecasedot "l. pacioli")
(def namespace " Luca    Pacioli ")
(def namespacedot "L.  Pacioli ")
(def namespacecase " luca   pacioli")
(def namespacecasedot "l.   pacioli")   

(fact "Case insensitive string compare"
      (strcasecmp name namecase) => true
      (strcasecmp name "luca")   => false
      (strcasecmp namecase name) => true
      (strcasecmp "luca" name)   => false)


(fact "Dotted name comparison"
      (fact " - case" (namecmp name namecase) => true)
      (fact " - dotted" (namecmp name namedot)  => true)
      (fact " - case dotted" (namecmp name namecasedot) => true)
      (fact " - space" (namecmp name namespace) => true)
      (fact " - space dotted" (namecmp name namespacedot) => true)
      (fact " - space case" (namecmp name namespacecase) => true)
      (fact " - space case dot" (namecmp name namespacecasedot) => true)
      )

(fact "Dotted name comparison - reverse"
      (fact " - case" (namecmp  namecase name) => true)
      (fact " - dotted" (namecmp namedot name )  => true)
      (fact " - case dotted" (namecmp namecasedot name) => true)
      (fact " - space" (namecmp namespace name) => true)
      (fact " - space dotted" (namecmp namespacedot name) => true)
      (fact " - space case" (namecmp namespacecase name) => true)
      (fact " - space case dot" (namecmp namespacecasedot name) => true)
      )

