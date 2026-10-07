(ns agiladmin.mcp.schemas
  "Published JSON contracts, independent of SDK and storage. All quantities are integer minutes.")

(defn object [properties required]
  {:type "object" :properties properties :required (mapv name required) :additionalProperties false})
(defn array [items] {:type "array" :items items})
(def text {:type "string"})
(def nullable-text {:type ["string" "null"]})
(def nonempty {:type "string" :minLength 1})
(def id (assoc nonempty :maxLength 128 :description "Stable owner-scoped identifier; keep it through retries and credential rotation."))
(def natural {:type "integer" :minimum 0})
(def month (assoc text :pattern "^[0-9]{4}-[0-9]{2}$" :description "Real reporting month YYYY-MM, e.g. 2024-02."))
(def date (assoc text :pattern "^[0-9]{4}-[0-9]{2}-[0-9]{2}$" :description "Real local calendar date in the configured reporting timezone. Ask when uncertain."))
(def cursor (object {:digest nonempty :offset natural} [:digest :offset]))
(def paging {:limit (assoc natural :minimum 1 :maximum 200 :default 50 :description "Maximum catalog items or records per page.")
             :cursor (assoc cursor :description "Opaque snapshot-bound cursor from next_cursor. Restart if stale.")})
(def entry
  (assoc (object {:external_id id :date date
                  :minutes {:type "integer" :minimum 1 :maximum 1440 :description "Whole recorded minutes; 6 hours = 360. Ask instead of guessing."}
                  :project_id (assoc nonempty :description "Existing canonical uppercase project. Supply this OR organization.")
                  :organization (assoc nonempty :description "Configured alias. Ambiguous aliases require asking the person for project_id.")
                  :task_id (assoc nonempty :description "Optional existing task under the chosen project; never invent one.")
                  :note (assoc text :maxLength 240 :description "At most 240 Unicode characters, plain text; preserved literally, including formula-like prefixes.")
                  :voluntary {:type "boolean" :default false :description "Explicit volunteering consumes no paid cap. Excess eligible minutes also become VOL."}}
                 [:external_id :date :minutes])
         :oneOf [{:required ["project_id"] :not {:required ["organization"]}}
                 {:required ["organization"] :not {:required ["project_id"]}}]))
(def entries (assoc (array entry) :minItems 1 :maxItems 200
                   :description "One month's atomic upserts; at most 200 entries. Stable IDs correct existing entries. Different IDs are never silently deduplicated."))
(def canonical-entry (object {:external_id nonempty :date date :minutes natural :project_id nonempty
                              :task_id nullable-text :note text :voluntary {:type "boolean"}}
                             [:external_id :date :minutes :project_id :task_id :note :voluntary]))
(def totals (object {:recorded_minutes natural :paid_minutes natural :vol_minutes natural}
                    [:recorded_minutes :paid_minutes :vol_minutes]))
(def policy {:policy_version nonempty :policy_hash nonempty})
(def capacity
  (object (merge policy {:month month :required_columns natural :max_columns {:const 7}
                         :exportable {:type "boolean"}
                         :assignments (array (object {:project_id nonempty :task_id nullable-text
                                                      :tag {:enum ["" "VOL"]} :minutes natural}
                                                     [:project_id :task_id :tag :minutes]))})
          [:month :required_columns :max_columns :exportable :assignments :policy_version :policy_hash]))
(def day
  (object (merge policy (:properties totals)
                 {:date date :cap_minutes natural
                  :assignments (array (object {:project_id nonempty :task_id nullable-text
                                               :recorded_minutes natural :eligible_minutes natural
                                               :paid_minutes natural :vol_minutes natural}
                                              [:project_id :task_id :recorded_minutes :eligible_minutes :paid_minutes :vol_minutes]))})
          [:date :cap_minutes :assignments :recorded_minutes :paid_minutes :vol_minutes :policy_version :policy_hash]))
(def allocation (object (assoc policy :days (array day)) [:policy_version :policy_hash :days]))
(def duplicates (array (array nonempty)))
(def warning (object {:code nonempty :explanation nonempty :next_action nonempty} [:code :explanation :next_action]))
(def error-output
  (object {:error (object {:code nonempty :field (array {:type ["string" "integer"]})
                           :explanation nonempty :next_action nonempty :candidates (array nonempty)
                           :capacity capacity :revision natural}
                          [:code :field :explanation :next_action])} [:error]))
(def status-output
  (object {:month month :draft_revision natural
           :state {:enum ["draft" "prepared" "local-committed" "pushed" "conflict" "failed"]}
           :published_revision {:type ["integer" "null"] :minimum 0} :commit nullable-text
           :push_state text :next_action nonempty}
          [:month :draft_revision :state :published_revision :commit :push_state :next_action]))
(def mutation-output
  (object {:month month :revision natural :records (array canonical-entry)
           :deleted_ids (array nonempty) :allocation allocation :capacity capacity :totals totals
           :possible_duplicate_ids duplicates :warnings (array warning)}
          [:month :revision :records :allocation :capacity :totals :possible_duplicate_ids :warnings]))
(def month-output
  (object (merge (dissoc (:properties mutation-output) :deleted_ids :warnings)
                 {:digest nonempty :next_cursor {:anyOf [cursor {:type "null"}]}
                  :publication status-output})
          [:month :revision :records :allocation :capacity :totals :possible_duplicate_ids :digest :next_cursor :publication]))
(def context-output
  (object {:owner (object {:name nonempty} [:name]) :timezone nonempty :paid_cap_minutes natural
           :policy (object policy [:policy_version :policy_hash])
           :catalog (array (object {:kind {:enum ["project" "task" "organization"]} :project_id nonempty
                                    :task_id nonempty :organization nonempty :candidates (array nonempty) :text text}
                                   [:kind]))
           :next_cursor {:anyOf [cursor {:type "null"}]}
           :units {:const "integer minutes"} :note_limit {:const 240} :assignment_columns {:const 7}
           :workflow nonempty :rules (array nonempty)}
          [:owner :timezone :paid_cap_minutes :policy :catalog :next_cursor :units :note_limit :assignment_columns :workflow :rules]))
(def validation-output
  (object {:valid {:const true} :revision natural :records (array canonical-entry) :allocation allocation
           :capacity capacity :totals totals :possible_duplicate_ids duplicates :warnings (array warning)}
          [:valid :revision :records :allocation :capacity :totals :possible_duplicate_ids :warnings]))
(def preview-output
  (object {:preview_id nonempty :month month :revision natural :digest nonempty
           :created_at text :expires_at text :policy (object (merge policy {:paid_cap_minutes natural :timezone text})
                                                          [:policy_version :policy_hash :paid_cap_minutes :timezone])
           :source_fingerprint nonempty :target_month_baseline nonempty :artifact_fingerprint nonempty
           :filename nonempty :owner_review_url nonempty :artifact_url nonempty
           :capacity capacity :totals totals :allocation allocation :record_count natural
           :possible_duplicate_ids duplicates
           :exact_changes (object {:cells (array (object {:cell nonempty :before {:type ["string" "number" "boolean" "null"]}
                                                          :after {:type ["string" "number" "boolean" "null"]}}
                                                         [:cell :before :after]))
                                   :preserves_other_months {:const true} :notes_column {:const "I"}}
                                  [:cells :preserves_other_months :notes_column])
           :next_action nonempty}
          [:preview_id :month :revision :digest :created_at :expires_at :policy :source_fingerprint
           :target_month_baseline :artifact_fingerprint :filename :owner_review_url :artifact_url
           :capacity :totals :allocation :record_count :possible_duplicate_ids :exact_changes :next_action]))
(def command {:month month :request_id (assoc id :description "Unique owner-scoped request ID. Retry identical arguments with the same ID; changed payloads require a new ID.")
              :expected_revision (assoc natural :description "Current month revision from get_month/validation. Stale revisions fail atomically; read and reconcile before retrying.")})
(def contracts
  {"get_work_context" {:input (object paging []) :output context-output}
   "validate_work_entries" {:input (object {:month month :entries entries} [:month :entries]) :output validation-output}
   "upsert_work_entries" {:input (object (assoc command :entries entries) [:month :request_id :expected_revision :entries]) :output mutation-output}
   "delete_work_entries" {:input (object (assoc command :ids (assoc (array id) :minItems 1 :maxItems 200 :uniqueItems true))
                                         [:month :request_id :expected_revision :ids]) :output mutation-output}
   "get_month" {:input (object (assoc paging :month month) [:month]) :output month-output}
   "preview_month" {:input (object {:month month} [:month]) :output preview-output}
   "get_publication_status" {:input (object {:month month} [:month]) :output status-output}})
(defn input [name] (get-in contracts [name :input]))
(defn output [name] {:type "object" :anyOf [(get-in contracts [name :output]) error-output]})
