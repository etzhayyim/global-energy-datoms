#!/usr/bin/env nbb
(ns build
  (:require [clojure.string :as str]
            [edamame.core :as edn]))

(def fs (js/require "fs"))
(def source-root (or (first *command-line-args*) ".."))
(defn slurp* [p] (.toString (.readFileSync fs p)))
(defn write! [p x] (.writeFileSync fs p (str (pr-str x) "\n")))
(defn json [p] (js->clj (js/JSON.parse (slurp* p)) :keywordize-keys true))
(defn path [& xs] (str/join "/" xs))
(defn valid-iso3? [s] (boolean (re-matches #"[A-Z]{3}" (or s ""))))
(defn value [s] (when-not (str/blank? s) (js/parseFloat s)))
(defn entity-id [source year id] (str source "-" year "-" id))

(def wb-root (path source-root "org.worldbank.api"))
(def owid-root (path source-root "org.ourworldindata"))
(def enecho-root (path source-root "jp.go.meti.enecho"))
(def unsdg-root (path source-root "org.un.unstats"))
(def wb-files [["renewable-electricity-share-2021.json" :energy.electricity/renewable-share-percent]
               ["fossil-electricity-share-2022.json" :energy.electricity/fossil-share-percent]
               ["nuclear-electricity-share-2022.json" :energy.electricity/nuclear-share-percent]
               ["renewable-final-energy-share-2022.json" :energy.final/renewable-share-percent]
               ["population-2022.json" :demography/population-total]
               ["gdp-per-capita-current-usd-2022.json" :economy/gdp-per-capita-current-usd]
               ["life-expectancy-2022.json" :health/life-expectancy-at-birth-years]
               ["internet-users-percent-2022.json" :connectivity/internet-users-percent]])
(def owid-columns {"Other renewables" :energy.electricity/other-renewables-twh "Bioenergy" :energy.electricity/bioenergy-twh "Solar" :energy.electricity/solar-twh "Wind" :energy.electricity/wind-twh "Hydropower" :energy.electricity/hydropower-twh "Nuclear" :energy.electricity/nuclear-twh "Oil" :energy.electricity/oil-twh "Gas" :energy.electricity/gas-twh "Coal" :energy.electricity/coal-twh})

(defn read-wb []
  (reduce (fn [acc [filename attr]]
            (let [rows (second (json (path wb-root "raw/wdi" filename)))]
              (reduce (fn [a row]
                        (let [iso (:countryiso3code row) v (:value row) year (js/parseInt (:date row))]
                          (if (and (valid-iso3? iso) (number? v))
                            (update a [iso year] merge {:energy.observation/id (entity-id "worldbank-wdi" year iso) :energy.observation/source :source/worldbank-wdi :energy.observation/release "2026-07-01" :country/iso3 iso :country/name (get-in row [:country :value]) :energy/year year attr v}) a))) acc rows))) {} wb-files))
(defn read-owid []
  (let [rows (str/split-lines (slurp* (path owid-root "raw/grapher/electricity-prod-source-stacked.csv"))) header (str/split (first rows) #"," -1) idx (zipmap header (range))]
    (reduce (fn [acc line] (let [cols (str/split line #"," -1) iso (get cols (idx "Code"))]
                             (if (and (valid-iso3? iso) (= "2024" (get cols (idx "Year"))))
                               (let [metrics (into {} (keep (fn [[col attr]] (when-let [v (value (get cols (idx col)))] [attr v])) owid-columns))]
                                 (if (seq metrics) (assoc acc iso (merge {:energy.observation/id (entity-id "owid-electricity" 2024 iso) :energy.observation/source :source/our-world-in-data :energy.observation/release "grapher-electricity-prod-source-stacked" :country/iso3 iso :country/name (get cols (idx "Entity")) :energy/year 2024} metrics)) acc)) acc))) {} (rest rows))))
(defn read-enecho []
  (let [series (edn/parse-string (slurp* (path enecho-root "derived/enecho-energy-timeseries.edn"))) row (first (filter #(= 2024 (:fy %)) (:series/observations series))) total (:generation-100m-kwh row)]
    [{:energy.observation/id "enecho-total-energy-2024-JPN" :energy.observation/source :source/jp-go-meti-enecho :energy.observation/release "2026-04-14" :country/iso3 "JPN" :country/name "Japan" :energy/year 2024 :energy.final/consumption-pj (:final-energy-pj row) :energy.electricity/generation-100m-kwh total :energy.electricity/thermal-excluding-biomass-share-percent (* 100 (/ (:thermal-excluding-biomass-100m-kwh row) total)) :energy.electricity/non-fossil-share-percent (* 100 (/ (:non-fossil-100m-kwh row) total)) :energy.electricity/renewables-including-hydro-share-percent (* 100 (/ (:renewables-including-hydro-100m-kwh row) total))}]))
(defn read-m49->iso3 []
  (into {} (map (juxt :geo/m49 :country/iso3)
                (:crosswalk/entries
                 (edn/parse-string (slurp* (path unsdg-root "derived/m49-iso3.edn")))))))
(defn read-unsdg [m49->iso3]
  (reduce (fn [acc [filename attr filter-row]]
            (reduce (fn [a row] (let [m49 (:geoAreaCode row) v (:value row) year (int (:timePeriodStart row))]
                                  (if (and (re-matches #"[0-9]+" m49) (filter-row row) (not (str/blank? v)))
                                    (update a [m49 year] merge {:energy.observation/id (entity-id "un-sdg" year m49) :energy.observation/source :source/un-sdg :energy.observation/release "2026-07-10" :geo/m49 m49 :country/name (:geoAreaName row) :energy/year year attr (value v)}
                                            (when-let [iso3 (get m49->iso3 m49)]
                                              {:country/iso3 iso3 :geo/crosswalk-source :source/un-m49-overview}))
                                    a))) acc (:data (json (path unsdg-root "raw/sdg" filename))))) {}
          [["electricity-access-2022.json" :energy.access/electricity-percent #(= "ALLAREA" (get-in % [:dimensions :Location]))] ["renewable-final-energy-share-2022.json" :energy.final/renewable-share-percent (constantly true)]]))
(defn datoms [entities tx] (vec (mapcat (fn [e] (for [[a v] (sort-by (comp str key) e)] [(:energy.observation/id e) a v tx :add])) (sort-by :energy.observation/id entities))))

(let [wb (vals (read-wb)) owid (vals (read-owid)) enecho (read-enecho) m49->iso3 (read-m49->iso3) unsdg (vals (read-unsdg m49->iso3)) entities (concat wb owid enecho unsdg) tx 20260710]
  (.mkdirSync fs "data" #js {:recursive true})
  (write! "data/datascript-tx.edn" (vec entities))
  (write! "data/global-energy.kotoba.edn" {:datom/format :eavt :datom/tx tx :datom/rows (datoms entities tx)})
  (write! "data/provenance.edn" {:build/as-of "2026-07-10" :sources [{:source/id :source/worldbank-wdi :source/repo "etzhayyim/org.worldbank.api" :source/entities (count wb) :source/year 2022} {:source/id :source/our-world-in-data :source/repo "etzhayyim/org.ourworldindata" :source/entities (count owid) :source/year 2024} {:source/id :source/jp-go-meti-enecho :source/repo "etzhayyim/jp.go.meti.enecho" :source/entities (count enecho) :source/year 2024} {:source/id :source/un-sdg :source/repo "etzhayyim/org.un.unstats" :source/entities (count unsdg) :source/year 2022} {:source/id :source/un-m49-overview :source/repo "etzhayyim/org.un.unstats" :source/entities (count m49->iso3) :source/role :geo-crosswalk}]})
  (println (str "built " (count wb) " WDI, " (count owid) " OWID, " (count enecho) " Eneqcho, and " (count unsdg) " UN SDG observations")))
