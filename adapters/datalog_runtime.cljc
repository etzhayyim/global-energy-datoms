(ns adapters.datalog-runtime
  "In-memory Datalog query layer for global-energy-datoms.

  Replaces JVM `datascript.core` with `kotoba-lang/datalog`. Keyword attributes
  and keyword values in entity maps are stored as bare strings so published
  queries in queries/examples.edn keep working unchanged."
  (:require [clojure.edn :as edn]
            [datalog.core :as dl]
            [datalog.index :as index]))

(defn- attr-name [k]
  (if (keyword? k)
    (if-let [ns* (namespace k)]
      (str ns* "/" (name k))
      (name k))
    (str k)))

(defn- attr-value [v]
  (cond
    (keyword? v) (attr-name v)
    (map? v) (pr-str v)
    (or (vector? v) (seq? v) (set? v)) (pr-str v)
    (nil? v) ""
    :else v))

(defn- parse-vector-query [query]
  (let [qvec (if (string? query) (edn/read-string query) query)
        idx-in (first (keep-indexed #(when (= %2 :in) %1) qvec))
        idx-where (or (first (keep-indexed #(when (= %2 :where) %1) qvec)) -1)
        find-end (or idx-in idx-where)
        find-part (subvec qvec 1 find-end)
        find-syms (vec (remove #{'$ '.} find-part))
        scalar-dot? (some #{'.} find-part)
        in-syms (when idx-in (vec (remove #{'$} (subvec qvec (inc idx-in) idx-where))))
        where-clauses (when (pos? idx-where) (vec (subvec qvec (inc idx-where))))]
    {:find find-syms :in in-syms :where where-clauses :scalar-dot? scalar-dot?}))

(defn- norm-ground [x]
  (cond
    (symbol? x) x
    (keyword? x) (attr-name x)
    :else x))

(defn- norm-clause [clause]
  (cond
    (and (seq? clause) (= 'not (first clause)) (vector? (second clause)))
    (list 'not (mapv norm-ground (second clause)))

    (vector? clause)
    (mapv (fn [x]
            (if (seq? x)
              (apply list (map norm-ground x))
              (norm-ground x)))
          clause)

    :else clause))

(defn db
  "Build a datalog db from a seq of entity maps (one map per entity)."
  [records]
  (loop [records (seq records), db (index/empty-db), n 0]
    (if-not records
      db
      (let [record (first records)
            subject (str "e" n)
            db' (reduce (fn [acc [k v]]
                          (if (= k :db/id)
                            acc
                            (index/assert-quad acc
                                               {:s subject
                                                :p (attr-name k)
                                                :o (attr-value v)}
                                               (constantly false))))
                        db
                        record)]
        (recur (next records) db' (inc n))))))

(defn q
  "Run a DataScript-shaped vector query over `db`. Optional `inputs` follow
  `:in` after the `$` db placeholder. A trailing `.` in `:find` returns a
  scalar aggregate (DataScript compatibility).

  Argument order matches DataScript: `(q query db & inputs)`."
  [query db & inputs]
  (let [{:keys [find in where scalar-dot?]} (parse-vector-query query)
        in-syms (vec (remove #{'$} (or in [])))
        _ (when (not= (count in-syms) (count inputs))
            (throw (ex-info "datalog-runtime: :in arity mismatch"
                            {:in in-syms :inputs inputs})))
        rows (vec (dl/q db {:find find :in in :where (mapv norm-clause where)}
                        (constantly true)
                        inputs))]
    (if (and scalar-dot? (= 1 (count rows)) (= 1 (count (first rows))))
      (ffirst rows)
      rows)))
