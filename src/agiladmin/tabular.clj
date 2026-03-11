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

(defn order-by-col
  [data col direction]
  (let [cmp (case direction
              :desc #(compare %2 %1)
              :asc compare
              compare)]
    {:column-names (:column-names data)
     :rows (->> (:rows data)
                (sort-by col cmp)
                vec)}))

(defn aggregate-sum
  [data fields group-cols]
  (let [fields (if (coll? fields) (vec fields) [fields])
        group-cols (vec group-cols)
        initial-row (fn [row]
                      (merge (select-keys row group-cols)
                             (zipmap fields (repeat 0))))
        grouped
        (reduce (fn [{:keys [order by-key]} row]
                  (let [group-key (select-keys row group-cols)
                        row-key (mapv group-key group-cols)
                        next-row (reduce (fn [acc field]
                                           (update acc field + (or (get row field) 0)))
                                         (or (get by-key row-key)
                                             (initial-row row))
                                         fields)]
                    {:order (if (contains? by-key row-key)
                              order
                              (conj order row-key))
                     :by-key (assoc by-key row-key next-row)}))
                {:order []
                 :by-key {}}
                (:rows data))]
    {:column-names (vec (concat group-cols fields))
     :rows (mapv (fn [row-key]
                   (get-in grouped [:by-key row-key]))
                 (:order grouped))}))

(defn append-rows
  [first-arg & more]
  (let [[column-names tables]
        (if (map? first-arg)
          [(:column-names first-arg) (cons first-arg more)]
          [first-arg more])]
    {:column-names (vec column-names)
     :rows (mapv (fn [row]
                   (select-keys row column-names))
                 (mapcat :rows tables))}))
