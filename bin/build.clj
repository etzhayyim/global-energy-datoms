#!/usr/bin/env bb
(ns build
  "Builds portable Datascript transaction maps and Kotoba EAVT datoms from the
  versioned source datasets. Input locations are explicit; no network I/O occurs here."
  (:require [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def source-root (or (first *command-line-args*) ".."))
(def wb-root (str source-root "/org.worldbank.api"))
(def owid-root (str source-root "/org.ourworldindata"))
(def enecho-root (str source-root "/jp.go.meti.enecho"))

(def wb-files
  [["renewable-electricity-share-2021.json" :energy.electricity/renewable-share-percent]
   ["fossil-electricity-share-2022.json" :energy.electricity/fossil-share-percent]
   ["nuclear-electricity-share-2022.json" :energy.electricity/nuclear-share-percent]
   ["renewable-final-energy-share-2022.json" :energy.final/renewable-share-percent]])

(def owid-columns
  {"Other renewables" :energy.electricity/other-renewables-twh
   "Bioenergy" :energy.electricity/bioenergy-twh
   "Solar" :energy.electricity/solar-twh
   "Wind" :energy.electricity/wind-twh
   "Hydropower" :energy.electricity/hydropower-twh
   "Nuclear" :energy.electricity/nuclear-twh
   "Oil" :energy.electricity/oil-twh
   "Gas" :energy.electricity/gas-twh
   "Coal" :energy.electricity/coal-twh})

(defn valid-iso3? [s] (boolean (re-matches #"[A-Z]{3}" (or s ""))))
(defn value [s] (when-not (str/blank? s) (Double/parseDouble s)))
(defn entity-id [source year iso3] (str source "-" year "-" iso3))

(defn read-wb []
  (reduce
   (fn [acc [filename attr]]
     (let [[_ rows] (json/parse-string (slurp (str wb-root "/raw/wdi/" filename)) true)]
       (reduce (fn [a row]
                 (let [iso (:countryiso3code row) v (:value row) year (Long/parseLong (:date row))]
                   (if (and (valid-iso3? iso) (number? v))
                     (update a [iso year] merge {:energy.observation/id (entity-id "worldbank-wdi" year iso)
                                          :energy.observation/source :source/worldbank-wdi
                                          :energy.observation/release "2026-07-01"
                                          :country/iso3 iso
                                          :country/name (get-in row [:country :value])
                                          :energy/year year
                                          attr (double v)})
                     a))) acc rows))) {} wb-files))

(defn read-owid []
  (let [rows (str/split-lines (slurp (str owid-root "/raw/grapher/electricity-prod-source-stacked.csv")))
        header (str/split (first rows) #"," -1)
        idx (zipmap header (range))]
    (reduce (fn [acc line]
              (let [cols (str/split line #"," -1)
                    iso (get cols (idx "Code"))
                    year (get cols (idx "Year"))]
                (if (and (valid-iso3? iso) (= "2024" year))
                  (let [metric-map (into {}
                                         (keep (fn [[column attr]]
                                                 (when-let [v (value (get cols (idx column)))] [attr v])))
                                         owid-columns)]
                    (if (seq metric-map)
                      (assoc acc iso (merge {:energy.observation/id (entity-id "owid-electricity" 2024 iso)
                                             :energy.observation/source :source/our-world-in-data
                                             :energy.observation/release "grapher-electricity-prod-source-stacked"
                                             :country/iso3 iso
                                             :country/name (get cols (idx "Entity"))
                                             :energy/year 2024}
                                            metric-map))
                      acc))
                  acc))) {} (rest rows))))

(defn read-enecho []
  (let [series (edn/read-string (slurp (str enecho-root "/derived/enecho-energy-timeseries.edn")))
        row (first (filter #(= 2024 (:fy %)) (:series/observations series)))
        total (double (:generation-100m-kwh row))]
    [{:energy.observation/id "enecho-total-energy-2024-JPN"
      :energy.observation/source :source/jp-go-meti-enecho
      :energy.observation/release "2026-04-14"
      :country/iso3 "JPN"
      :country/name "Japan"
      :energy/year 2024
      :energy.final/consumption-pj (double (:final-energy-pj row))
      :energy.electricity/generation-100m-kwh total
      :energy.electricity/thermal-excluding-biomass-share-percent (* 100.0 (/ (:thermal-excluding-biomass-100m-kwh row) total))
      :energy.electricity/non-fossil-share-percent (* 100.0 (/ (:non-fossil-100m-kwh row) total))
      :energy.electricity/renewables-including-hydro-share-percent (* 100.0 (/ (:renewables-including-hydro-100m-kwh row) total))}]))

(defn datoms [entities tx]
  (vec (mapcat (fn [e]
                 (for [[a v] (sort-by (comp str key) e)]
                   [(:energy.observation/id e) a v tx :add]))
               (sort-by :energy.observation/id entities))))

(defn write-edn [path value]
  (spit path (with-out-str (binding [*print-namespace-maps* false] (prn value)))))

(let [wb (vals (read-wb))
      owid (vals (read-owid))
      enecho (read-enecho)
      tx 20260710
      output (io/file "data")]
  (.mkdirs output)
  (write-edn "data/datascript-tx.edn" (vec (concat wb owid enecho)))
  (write-edn "data/global-energy.kotoba.edn" {:datom/format :eavt :datom/tx tx :datom/rows (datoms (concat wb owid enecho) tx)})
  (write-edn "data/provenance.edn" {:build/as-of "2026-07-10"
                                     :sources [{:source/id :source/worldbank-wdi :source/repo "etzhayyim/org.worldbank.api" :source/entities (count wb) :source/year 2022}
                                               {:source/id :source/our-world-in-data :source/repo "etzhayyim/org.ourworldindata" :source/entities (count owid) :source/year 2024}
                                               {:source/id :source/jp-go-meti-enecho :source/repo "etzhayyim/jp.go.meti.enecho" :source/entities (count enecho) :source/year 2024}]})
  (println (str "built " (count wb) " WDI, " (count owid) " OWID, and " (count enecho) " Eneqcho observations")))
