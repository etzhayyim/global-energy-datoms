(require '[clojure.edn :as edn]
         '[datascript.core :as d])

(let [schema (edn/read-string (slurp "schema/energy.edn"))
      tx (edn/read-string (slurp "data/datascript-tx.edn"))
      queries (:queries (edn/read-string (slurp "queries/examples.edn")))
      db (d/db-with (d/empty-db schema) tx)
      run #(d/q (:query (get queries %)) db)]
  (assert (>= (count tx) 900) "global observation coverage unexpectedly shrank")
  (assert (>= (count (run :renewable-electricity-share-2022)) 200) "WDI renewable coverage missing")
  (assert (>= (count (run :solar-generation-2024)) 150) "OWID generation coverage missing")
  (assert (>= (count (run :un-sdg-electricity-access-2022)) 200) "UN SDG coverage missing")
  (assert (>= (count (run :population-and-life-expectancy-2022)) 200) "non-energy coverage missing")
  (assert (number? (run :japan-official-non-fossil-share-2024)) "Japan official query missing")
  (println {:status :ok :entities (count tx) :queries (count queries)}))
