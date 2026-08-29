(require '[clojure.edn :as edn]
         '[adapters.datalog-runtime :as dr]
         '[adapters.read-only :as read-only])

(let [tx (edn/read-string (slurp "data/datascript-tx.edn"))
      quality (edn/read-string (slurp "data/quality-report.edn"))
      queries (:queries (edn/read-string (slurp "queries/examples.edn")))
      db (dr/db tx)
      run #(dr/q (:query (get queries %)) db)]
  (assert (>= (count tx) 900) "global observation coverage unexpectedly shrank")
  (assert (zero? (:quality/missing-required-provenance quality)) "required provenance missing")
  (assert (>= (get-in quality [:quality/sources :source/worldbank-wdi :with-iso3]) 450) "WDI ISO3 coverage missing")
  (assert (>= (get-in quality [:quality/sources :source/our-world-in-data :with-iso3]) 150) "OWID ISO3 coverage missing")
  (assert (>= (:quality/un-sdg-with-official-iso3 quality) 180) "official UN M49-to-ISO3 coverage missing")
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
