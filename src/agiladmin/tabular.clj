(ns agiladmin.tabular)

(defn dataset
  ([rows]
   (let [rows (vec rows)
         column-names (if-let [first-row (first rows)]
                        (vec (keys first-row))
                        [])]
     {:column-names column-names
      :rows rows}))
  ([column-names rows]
   {:column-names (vec column-names)
    :rows (vec rows)}))

(defn rows
  [data]
  (vec (:rows data)))

(defn column-names
  [data]
  (vec (:column-names data)))

(defn to-row-seq
  [data]
  (mapv (fn [row]
          (mapv row (:column-names data)))
        (:rows data)))

(defn select-cols
  [data cols]
  (let [cols (vec cols)]
    {:column-names cols
     :rows (mapv (fn [row]
                   (select-keys row cols))
                 (:rows data))}))

(defn drop-cols
  [data cols]
  (let [to-drop (set cols)
        kept-cols (vec (remove to-drop (:column-names data)))]
    {:column-names kept-cols
     :rows (mapv (fn [row]
                   (apply dissoc row to-drop))
                 (:rows data))}))

(defn map-column
  [data col f]
  {:column-names (:column-names data)
   :rows (mapv (fn [row]
                 (update row col f))
               (:rows data))})

(defn column-values
  [data col]
  (mapv col (:rows data)))

(defn from-row-seq
  [rows]
  (let [[headers & body] rows
        column-names (vec headers)]
    {:column-names column-names
     :rows (mapv (fn [row]
                   (zipmap column-names row))
                 body)}))

(defn filter-rows
  [data pred]
  {:column-names (:column-names data)
   :rows (->> (:rows data)
              (filter pred)
              vec)})

(defn filter-by
  [data criteria]
  (filter-rows data
               (fn [row]
                 (every? (fn [[k v]]
                           (= (get row k) v))
                         criteria))))

(defn sum-col
  [data col]
  (reduce + 0 (column-values data col)))

(defn average-col
  [data col]
  (let [values (column-values data col)]
    (if (seq values)
      (/ (reduce + 0 values) (count values))
      0)))
