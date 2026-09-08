#!/usr/bin/env nbb
(ns report-upstream-drift
  (:require [kotoba.lang.text :as str]
            [edamame.core :as edn]))

(def fs (js/require "fs"))
(def crypto (js/require "crypto"))
(def child-process (js/require "child_process"))
(def root (or (first *command-line-args*) ".sources"))
(def catalogs ["jp.go.meti.enecho/raw/source-catalog.edn"
               "org.ourworldindata/raw/source-catalog.edn"
               "org.un.unstats/raw/source-catalog.edn"])
(defn slurp* [p] (.toString (.readFileSync fs p)))
(defn source-url [s] (or (:url s) (:source/url s)))
(defn expected-sha [s] (or (:sha256 s) (:source/sha256 s) (:csv-sha256 s)))
(defn sha256 [buffer]
  (-> (.createHash crypto "sha256") (.update (js/Buffer.from buffer)) (.digest "hex")))
(defn check! [s]
  (let [url (source-url s) expected (expected-sha s)]
    (if-not (and url expected)
      {:status :not-configured :label (or (:title s) (:source/title s) (:reference s) (:source/id s))}
      (try
        (let [bytes (.execFileSync child-process "curl"
                                  #js ["--fail" "--location" "--silent" "--show-error"
                                       "--user-agent" "global-energy-datoms-drift-monitor/1.0" url]
                                  #js {:maxBuffer (* 5 1024 1024)})
              actual (sha256 bytes)]
          {:status (if (= expected actual) :unchanged :changed)
           :label url :expected expected :actual actual})
        (catch :default error
          {:status :unavailable :label url :detail (str error)})))))
(defn line [{:keys [status label detail]}]
  (str "| " (name status) " | " label " | " (or detail "") " |"))

(let [sources (mapcat (fn [catalog-path]
                         (:sources (edn/parse-string (slurp* (str root "/" catalog-path)))))
                       catalogs)
      rows (map check! sources)
      report (str "# Upstream drift report\n\n"
                  "Read-only monitoring: this workflow never updates a raw snapshot, lock, or release.\n\n"
                  "| status | source | detail |\n|---|---|---|\n"
                  (str/join "\n" (map line rows)) "\n")]
  (println report)
  (when-let [summary (aget js/process.env "GITHUB_STEP_SUMMARY")]
    (.appendFileSync fs summary report))
  (println (str "changed=" (count (filter #(= :changed (:status %)) rows))
                " unavailable=" (count (filter #(= :unavailable (:status %)) rows)))))
