;; Local override for the legacy fxc dependency.
;; Clojure 1.12 rejects the old one-class :import vector syntax used upstream.

(ns fxc.random
  (:gen-class)
  (:import [java.security SecureRandom]))

(defn digit
  "Generate a single random digit in the range of 0-9"
  [max]
  (.nextInt (SecureRandom.) max))

(defn intchain
  "Generate a string chaining digits up to length"
  [length]
  (loop [x length
         res (int (digit 9))]
    (if (> x 1)
      (recur (dec x) (str res (digit 10)))
      res)))

(defn entropy
  "Measure (Shannon) the entropy of a string (returns a float)"
  [s]
  (let [len (count s)
        log-2 (Math/log 2)]
    (->> (frequencies s)
         (map (fn [[_ v]]
                (let [rf (/ v len)]
                  (-> (Math/log rf) (/ log-2) (* rf) Math/abs))))
         (reduce +))))

(defn create
  "Create a random `BigInteger` of the given length,
returning a map with keys [:integer :string]"
  [length]
  (let [res (intchain length)]
    {:integer (biginteger res)
     :string (str res)}))
