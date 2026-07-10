#!/usr/bin/env nbb
(ns build-quality-report
  (:require [edamame.core :as edn]))

(def fs (js/require "fs"))
(defn slurp* [p] (.toString (.readFileSync fs p)))
(defn observation? [e] (contains? e :energy.observation/id))
(defn count-where [pred xs] (count (filter pred xs)))

(let [entities (edn/parse-string (slurp* "data/datascript-tx.edn"))
      observations (filter observation? entities)
      by-source (into (sorted-map)
                      (for [[source xs] (group-by :energy.observation/source observations)]
                        [source {:entities (count xs)
                                 :with-iso3 (count-where :country/iso3 xs)
                                 :with-year (count-where :energy/year xs)}]))
      missing-provenance (count-where #(or (nil? (:energy.observation/source %))
                                           (nil? (:energy.observation/release %))
                                           (nil? (:energy/year %))) observations)
      report {:quality/as-of "2026-07-10"
              :quality/entities (count entities)
              :quality/observations (count observations)
              :quality/missing-required-provenance missing-provenance
              :quality/un-sdg-with-official-iso3
              (count-where #(and (= :source/un-sdg (:energy.observation/source %))
                                  (= :source/un-m49-overview (:geo/crosswalk-source %))) observations)
              :quality/sources by-source
              :quality/thresholds {:minimum-observations 900
                                   :minimum-wdi-iso3 450
                                   :minimum-owid-iso3 150
                                   :minimum-un-sdg-official-iso3 180
                                   :maximum-missing-required-provenance 0}}]
  (.writeFileSync fs "data/quality-report.edn" (str (pr-str report) "\n"))
  (println (str "quality report: " (:quality/observations report) " observations; "
                (:quality/un-sdg-with-official-iso3 report) " UN SDG observations with official ISO3")))
