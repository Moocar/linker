(ns me.moocar.linker
  (:require [clojure.edn :as edn]
            [clojure.java.io :as jio]
            [clojure.pprint :refer [pprint]]
            [clojure.string :as string]
            [me.moocar.java.io :as mio])
  (:import (java.io PushbackReader PrintWriter File)
           (java.nio.charset Charset)
           (java.nio.file Files Path FileSystems FileSystem LinkOption DirectoryStream$Filter)
           (java.nio.file.attribute FileAttribute))
  (:gen-class))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; java.nio.file.Path stuff

(defn directory? [path]
  (Files/isDirectory path (into-array LinkOption [])))

(defn create-directories [path]
  (Files/createDirectories path (into-array java.nio.file.attribute.FileAttribute [])))

(defn exists? [path]
  (Files/exists path (into-array LinkOption [])))

(def ^:private ^:static default-omit-glob
  "{project.clj,pom.xml,target,LICENSE,README.md,intro.md,.nrepl-port,.gitignore}")

(defn glob-matcher [omit-glob]
  (. (FileSystems/getDefault) (getPathMatcher (str "glob:" omit-glob))))

(defn omit? [matchers project-root-path path]
  (some #(.matches % (.relativize project-root-path path)) matchers))

(defn directory-stream-filter [pred]
  (reify DirectoryStream$Filter (accept [_ entry] (pred entry))))

(defn path-seq
  "A tree seq on java.nio.file.Paths"
  [pred dir]
  (tree-seq
   (fn [^java.nio.file.Path p]
     (directory? p))
   (fn [^java.nio.file.Path p]
     (Files/newDirectoryStream p (directory-stream-filter pred)))
   dir))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; symbolically link project sub dirs

(defn link-path
  [project-root-path out-dir dir-path]
  (let [relative (.relativize project-root-path dir-path)
        new-path (.resolve out-dir relative)]
    (if (directory? dir-path)
      (create-directories new-path)
      (when-not (exists? new-path)
        (Files/createSymbolicLink new-path
                                  dir-path
                                  (make-array FileAttribute 0))))))

(defn link-project
  [spec project]
  (let [{:keys [out-dir omit]} spec
        project-root-path (:dir project)
        default-matcher (glob-matcher default-omit-glob)
        proj-matcher (glob-matcher omit)
        path-tree (path-seq (complement #(omit? [default-matcher proj-matcher]
                                                project-root-path
                                                %))
                            project-root-path)]
    (doseq [path path-tree]
      (link-path project-root-path out-dir path))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Generate project.clj

(defn- read-defproject [path]
  (with-open [stream (PushbackReader. (jio/reader path))]
    (loop []
      (when-let [form (read stream false nil)]
        (if (and (list? form)
                 (= 'defproject (first form)))
          form
          (recur))))))

(defn- project-map [defproject-form]
  (let [[_ name version & keyvals] defproject-form]
    (assoc (apply hash-map keyvals)
      :name name
      :version version)))

(defn- all-dependencies [project-map]
  (into (:dependencies project-map)
        (get-in project-map [:profiles :dev :dependencies])))

(defn get-project-map [project]
  (some-> (.resolve (:dir project) "project.clj")
          read-defproject
          project-map))

(defn fill-project [project]
  (merge project
         (get-project-map project)))

(defn- long-or-string [^String string]
  (try (Long. string)
       (catch NumberFormatException _ string)))

(defn split-version [s]
  (-> s
      (string/replace #"(?<=alpha|beta|RC)(?=\d)" ".")
      (string/split #"[-._]")
      (->> (mapv long-or-string))))

(defn compare-versions [a b]
  (compare (split-version a) (split-version b)))

(defn- latest-versions [dependencies]
  (mapv (fn [[sym dependencies]]
          (last (sort compare-versions dependencies)))
        (group-by first dependencies)))

(defn- all-dependencies-in-projects [project-maps]
  (->> project-maps
       (mapcat all-dependencies)
       (remove (fn [dep] (some #(= (take 2 dep)
                                   ((juxt :name :version) %))
                               project-maps)))
       latest-versions
       sort
       vec))

(defn- project-clj-data
  "Produces a leiningen project.clj as a form (clojure list)"
  [{:keys [projects jvm-opts project-clj] :as spec}]
  (let [{:keys [project-name version]} project-clj
        project-maps (map fill-project projects)]
    (-> project-clj
        (dissoc :project-name :version)
        (update :dependencies concat (all-dependencies-in-projects project-maps))
        (->> (into (list))
             (apply concat))
        (->> (concat (list 'defproject
                           project-name
                           version))))))

(defn- write-project-file [file content]
  (with-open [writer (jio/writer file)]
    (binding [*out* (PrintWriter. writer)]
      (println ";; This file was generated by me.moocar.linker")
      (println ";; Do not edit by hand.")
      (pprint content))))

(defn gen-project-clj
  [spec]
  (let [project-clj-file (.resolve (:out-dir spec) "project.clj")
        project-form (project-clj-data spec)]
    (write-project-file project-clj-file project-form)))

(defn home-dir []
  (System/getProperty "user.home"))

(defn splice-tilde [string]
  (string/replace string "~" (home-dir)))

(defn fileize-project [project]
  (update project :dir (comp mio/path splice-tilde)))

(defn fileize-spec [spec]
  (-> spec
      (update :out-dir (comp mio/path splice-tilde))
      (update :projects #(map fileize-project %))))

(defn run
  [spec]
  {:pre [(map? spec)
         (contains? spec :project-clj)
         (map? (:project-clj spec))
         (symbol? (:project-name (:project-clj spec)))
         (string? (:version (:project-clj spec)))

         (contains? spec :out-dir)
         (string? (:out-dir spec))

         (contains? spec :projects)
         (coll? (:projects spec))
         (every? :dir (:projects spec))
         (every? (comp string? :dir) (:projects spec))

         (or (not (contains? spec :omit-file-types))
             (set? (:omit-file-types spec)))
         (or (not (contains? spec :omit-dirs))
             (set? (:omit-dirs spec)))]}
  (let [spec (fileize-spec spec)]
    (create-directories (:out-dir spec))
    (assert (exists? (:out-dir spec)))
    (assert (every? #(exists? (.resolve (:dir %) "project.clj"))
                    (:projects spec)))
    (gen-project-clj spec)
    (doseq [project (:projects spec)]
      (link-project spec project))))

(defn usage []
  (format "NAME
     linker -- merges leiningen projects using symlinks

SYNOPSIS
     linker spec-file

DESCRIPTION
     See examples/linker.edn for an example of a documented spec file"))

(defn -main [& [spec-file :as args]]
  (if-not (and (= 1 (count args))
               (string? spec-file)
               (.exists (jio/file spec-file)))
    (println (usage))
    (run (edn/read-string (slurp spec-file)))))
