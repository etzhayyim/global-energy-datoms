#!/usr/bin/env bb
(require '[clojure.edn :as edn]
         '[clojure.string :as str])

(let [lock (edn/read-string (slurp "sources.lock.edn"))
      workflow (slurp ".github/workflows/contract.yml")]
  (doseq [{:keys [repository revision]} (:sources lock)]
    (assert (str/includes? workflow (str "repository: " repository))
            (str "CI does not check out " repository))
    (assert (str/includes? workflow (str "ref: " revision))
            (str "CI revision differs from sources.lock for " repository)))
  (println (str "verified " (count (:sources lock)) " locked CI source revisions")))
