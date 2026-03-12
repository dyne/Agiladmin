(ns agiladmin.auth.user
  (:require [clojure.string :as str]))

(defn role-from-groups
  "Derive the Agiladmin role from Pocket ID groups."
  [groups {:keys [admin-group manager-group]}]
  (let [group-set (->> groups
                       (keep #(some-> % str/trim not-empty))
                       set)]
    (cond
      (and admin-group (contains? group-set admin-group)) "admin"
      (and manager-group (contains? group-set manager-group)) "manager"
      :else nil)))
