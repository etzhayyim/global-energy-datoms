(require '[clojure.edn :as edn]
         '[datascript.core :as d]
         '[adapters.read-only :as read-only])

(let [schema (edn/read-string (slurp "schema/energy.edn"))
      tx (edn/read-string (slurp "data/datascript-tx.edn"))
      queries (:queries (edn/read-string (slurp "queries/examples.edn")))
      db (d/db-with (d/empty-db schema) tx)
      run #(d/q (:query (get queries %)) db)]
  (assert (>= (count tx) 900) "global observation coverage unexpectedly shrank")
  (assert (>= (count (run :renewable-electricity-share-2022)) 200) "WDI renewable coverage missing")
  (assert (>= (count (run :solar-generation-2024)) 150) "OWID generation coverage missing")
  (assert (>= (count (run :un-sdg-electricity-access-2022)) 200) "UN SDG coverage missing")
  (assert (>= (count (run :un-sdg-electricity-access-by-iso3-2022)) 180) "UN SDG M49-to-ISO3 join missing")
  (assert (>= (count (run :population-and-life-expectancy-2022)) 200) "non-energy coverage missing")
  (assert (number? (run :japan-official-non-fossil-share-2024)) "Japan official query missing")
  (let [consumer (first (:connections/consumers (edn/read-string (slurp "connections/actors.edn"))))]
    (read-only/require-provenance! consumer)
    (assert (seq (read-only/query db {:queries queries} :solar-generation-2024)) "read-only consumer adapter missing"))
  (println {:status :ok :entities (count tx) :queries (count queries)}))
