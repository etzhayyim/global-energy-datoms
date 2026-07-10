#!/usr/bin/env bb
(ns verify-source-catalogs
  "Offline integrity verification for raw files referenced by source catalogs.
  It checks only committed snapshots; it never contacts or mutates a source URL."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io])
  (:import [java.security MessageDigest]
           [java.math BigInteger]))

(def source-root (or (first *command-line-args*) ".sources"))
(def catalogs ["jp.go.meti.enecho/raw/source-catalog.edn"
               "org.worldbank.api/raw/source-catalog.edn"
               "org.ourworldindata/raw/source-catalog.edn"
               "org.un.unstats/raw/source-catalog.edn"])

(defn sha256 [path]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (with-open [in (io/input-stream path)]
      (let [buf (byte-array 65536)]
        (loop [n (.read in buf)]
          (when (pos? n)
            (.update digest buf 0 n)
            (recur (.read in buf))))))
    (format "%064x" (BigInteger. 1 (.digest digest)))))

(defn references [catalog]
  (mapcat (fn [source]
            (cond-> []
              (and (:path source) (:sha256 source))
              (conj [(:path source) (:sha256 source)])
              (and (:metadata-path source) (:metadata-sha256 source))
              (conj [(:metadata-path source) (:metadata-sha256 source)])
              (and (:source/local-path source) (:source/sha256 source))
              (conj [(:source/local-path source) (:source/sha256 source)])))
          (:sources catalog)))

(let [checks (mapcat (fn [catalog-path]
                       (let [catalog (edn/read-string (slurp (io/file source-root catalog-path)))
                             dataset-root (io/file source-root (.getParent (io/file catalog-path)) "..")]
                         (for [[rel expected] (references catalog)]
                           [(io/file dataset-root rel) expected])))
                     catalogs)]
  (doseq [[file expected] checks]
    (assert (.isFile file) (str "missing raw source: " file))
    (let [actual (sha256 file)]
      (assert (= expected actual) (str "sha256 mismatch: " file " expected=" expected " actual=" actual))))
  (println (str "verified " (count checks) " raw source hashes")))
