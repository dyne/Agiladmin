(ns agiladmin.work-archive
  "Narrow filesystem/Git adapter. No client paths or commands; commits/pushes
  name the single server-mapped workbook and exact recorded commit."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clj-jgit.porcelain :as git]
            [agiladmin.utils :as util]
            [agiladmin.work-workbook :as workbook])
  (:import [java.nio.file Files Path Paths LinkOption StandardOpenOption StandardCopyOption]
           [java.nio.channels FileChannel] [java.nio ByteBuffer]
           [org.eclipse.jgit.api Git]
           [org.eclipse.jgit.lib Repository RepositoryState]
           [org.eclipse.jgit.transport RefSpec RemoteRefUpdate$Status]
           [org.eclipse.jgit.treewalk CanonicalTreeParser]
           [org.eclipse.jgit.revwalk RevWalk]))
(defn safe! [^Path path]
  (loop [p path]
    (when p
      (when (Files/isSymbolicLink p) (throw (ex-info "Unsafe archive path" {})))
      (recur (.getParent p)))) path)
(defn root [config]
  (safe! (.normalize (.toAbsolutePath (Paths/get (get-in config [:agiladmin :budgets :path]) (make-array String 0))))))
(defn target [config owner year]
  (let [r (root config) p (.normalize (.resolve r (util/name-year-to-timesheet (:person owner) year)))]
    (when-not (= r (.getParent p)) (throw (ex-info "Unsafe workbook mapping" {})))
    (safe! p)))
(defn sync-directory! [^Path path]
  (with-open [ch (FileChannel/open path (into-array StandardOpenOption [StandardOpenOption/READ]))] (.force ch true)))
(defn replace-workbook! [^Path target bytes]
  (safe! target)
  (let [root (.getParent target) tmp (Files/createTempFile root ".workbook-" ".tmp" (make-array java.nio.file.attribute.FileAttribute 0))]
    (try
      (with-open [ch (FileChannel/open tmp (into-array StandardOpenOption [StandardOpenOption/WRITE]))]
        (let [buffer (ByteBuffer/wrap bytes)] (while (.hasRemaining buffer) (.write ch buffer))) (.force ch true))
      (Files/move tmp target (into-array StandardCopyOption [StandardCopyOption/ATOMIC_MOVE StandardCopyOption/REPLACE_EXISTING]))
      (sync-directory! root)
      (finally (Files/deleteIfExists tmp)))))
(defn fingerprint [^Path target]
  (safe! target)
  (when (and (Files/exists target (make-array LinkOption 0)) (> (Files/size target) (* 32 1024 1024)))
    (throw (ex-info "Workbook too large" {})))
  (workbook/fingerprint (when (Files/exists target (make-array LinkOption 0)) (Files/readAllBytes target))))
(defn open-repository [config]
  (let [r (root config)] (safe! (.resolve r ".git")) (Git/open (.toFile r))))
(defn head [^Git git] (some-> (.resolve (.getRepository git) "HEAD") .name))
(defn destination [^Git git]
  (let [repo (.getRepository git) ref (.getFullBranch repo) branch (.getBranch repo)
        config (.getConfig repo) remote (or (.getString config "branch" branch "remote") "origin")
        target (or (.getString config "branch" branch "merge") ref)]
    (when-not (and (not (.isBare repo)) (= RepositoryState/SAFE (.getRepositoryState repo))
                   ref (head git) (str/starts-with? ref "refs/heads/")
                   (Repository/isValidRefName target) (str/starts-with? target "refs/heads/")
                   (not= "." remote) (.getString config "remote" remote "url"))
      (throw (ex-info "Configured branch/remote required" {})))
    {:remote remote :remote-url (.getString config "remote" remote "url") :ref target :local-ref ref}))
(defn index-fingerprint [^Git git filename]
  (if-let [entry (.getEntry (.readDirCache (.getRepository git)) filename)]
    (workbook/fingerprint (.getBytes (.open (.getRepository git) (.getObjectId entry)))) "absent"))
(defn target-clean? [^Git git filename]
  (let [s (.call (.status git))]
    (not-any? #(contains? % filename) [(.getAdded s) (.getChanged s) (.getRemoved s) (.getModified s)
                                     (.getMissing s) (.getUntracked s) (.getConflicting s)])))
(defn commit-workbook! [^Git git filename message owner]
  (.call (.addFilepattern (.add git) filename))
  ;; setOnly builds a temporary index for this commit and preserves the real
  ;; index's unrelated staged entries. Never commit the entire shared index.
  (.name (.call (-> (.commit git) (.setOnly filename) (.setMessage message)
                    (.setAuthor (:person owner) (or (:email owner) "agiladmin@localhost"))
                    (.setCommitter "Agiladmin" "agiladmin@localhost")))))
(defn committed-fingerprint [^Git git commit-id filename]
  (with-open [walk (RevWalk. (.getRepository git))]
    (let [commit (.parseCommit walk (.resolve (.getRepository git) commit-id))
          tree (org.eclipse.jgit.treewalk.TreeWalk/forPath (.getRepository git) filename (.getTree commit))]
      (when tree
        (with-open [t tree] (workbook/fingerprint (.getBytes (.open (.getRepository git) (.getObjectId t 0)))))))))
(defn recover-commit [^Git git entry]
  (with-open [walk (RevWalk. (.getRepository git)) reader (.newObjectReader (.getRepository git))]
    (let [h (head git) commit (.parseCommit walk (.resolve (.getRepository git) h))]
      (when (and (= 1 (.getParentCount commit)) (= (:parent-head entry) (.name (.getParent commit 0)))
                 (= (:message entry) (.getFullMessage commit))
                 (= (:artifact-fingerprint entry) (committed-fingerprint git h (:filename entry))))
        (let [old (CanonicalTreeParser.) new (CanonicalTreeParser.)
              parent (.parseCommit walk (.getId (.getParent commit 0)))]
          (.reset old reader (.getId (.getTree parent))) (.reset new reader (.getId (.getTree commit)))
          (when (= [(:filename entry)] (mapv #(.getNewPath %) (.call (-> (.diff git) (.setOldTree old) (.setNewTree new))))) h))))))
(defn ancestor? [^Git git commit-id]
  (with-open [walk (RevWalk. (.getRepository git))]
    (.isMergedInto walk (.parseCommit walk (.resolve (.getRepository git) commit-id))
                   (.parseCommit walk (.resolve (.getRepository git) (head git))))))
(defn push-commit! [^Git git config entry]
  (let [push (fn [] (.call (-> (.push git) (.setRemote (:remote entry))
                             (.setRefSpecs (java.util.Collections/singletonList
                                            (RefSpec. (str (:commit-id entry) ":" (:ref entry)))))
                             (.setForce false))))
        results (if (get-in config [:agiladmin :budgets :ssh-key])
                  (git/with-identity {:name (get-in config [:agiladmin :budgets :ssh-key]) :exclusive true} (push)) (push))
        updates (mapcat #(.getRemoteUpdates %) results)]
    (when-not (and (seq updates) (every? #(contains? #{RemoteRefUpdate$Status/OK RemoteRefUpdate$Status/UP_TO_DATE} (.getStatus %)) updates))
      (throw (ex-info "Push rejected" {}))) true))
