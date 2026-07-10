#!/usr/bin/env nbb
(ns verify-source-catalogs (:require [edamame.core :as edn]))
(def fs (js/require "fs")) (def path (js/require "path")) (def crypto (js/require "crypto"))
(def root (or (first *command-line-args*) ".sources"))
(def catalogs ["jp.go.meti.enecho/raw/source-catalog.edn" "org.worldbank.api/raw/source-catalog.edn" "org.ourworldindata/raw/source-catalog.edn" "org.un.unstats/raw/source-catalog.edn"])
(defn slurp* [p] (.toString (.readFileSync fs p)))
(defn digest [p] (-> (.createHash crypto "sha256") (.update (.readFileSync fs p)) (.digest "hex")))
(defn refs [catalog]
  (mapcat (fn [s] (cond-> [] (and (:path s) (:sha256 s)) (conj [(:path s) (:sha256 s)]) (and (:metadata-path s) (:metadata-sha256 s)) (conj [(:metadata-path s) (:metadata-sha256 s)]) (and (:source/local-path s) (:source/sha256 s)) (conj [(:source/local-path s) (:source/sha256 s)]))) (:sources catalog)))
(let [checks (mapcat (fn [catalog-path] (let [catalog (edn/parse-string (slurp* (.join path root catalog-path))) dataset (.resolve path root (.dirname path catalog-path) "..")] (for [[rel sha] (refs catalog)] [(.resolve path dataset rel) sha]))) catalogs)
      result (reduce (fn [{:keys [hashed annex-pointers] :as acc} [file expected]]
                       (if (.existsSync fs file)
                         (do (assert (= expected (digest file)) (str "sha256 mismatch: " file)) (assoc acc :hashed (inc hashed)))
                         (let [stat (.lstatSync fs file)]
                           (assert (.isSymbolicLink stat) (str "missing raw source or annex pointer: " file))
                           (assoc acc :annex-pointers (inc annex-pointers)))))
                     {:hashed 0 :annex-pointers 0} checks)]
  (println (str "verified " (:hashed result) " raw source hashes and " (:annex-pointers result) " git-annex pointers")))
