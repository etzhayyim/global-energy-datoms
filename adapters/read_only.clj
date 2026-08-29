(ns adapters.read-only
  (:require [clojure.edn :as edn]
            [adapters.datalog-runtime :as dr]))

(defn load-db
  "Loads the published projection into an immutable datalog db value."
  [_schema-path tx-path]
  (dr/db (edn/read-string (slurp tx-path))))

(defn load-contract [queries-path]
  (edn/read-string (slurp queries-path)))

(defn query
  "Runs one named, published query. No transact function is exposed."
  [db contract query-id]
  (let [spec (get-in contract [:queries query-id])]
    (assert spec (str "unknown published query: " query-id))
    (dr/q (:query spec) db)))

(defn require-provenance!
  "Rejects a consumer profile that does not declare the required lineage fields."
  [consumer]
  (let [required (set (:consumer/required-provenance consumer))]
    (assert (contains? required :energy.observation/source) "source provenance is mandatory")
    (assert (contains? required :energy/year) "observation year is mandatory")
    consumer))
