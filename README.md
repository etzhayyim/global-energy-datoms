# global-energy-datoms

出典別 DataLad dataset を複製せず、世界のエネルギー統計を query 可能な共通 EDN に投影する公開契約です。

- `schema/energy.edn` は Datascript の schema。
- `data/datascript-tx.edn` は Datascript に直接 `db-with` できる entity map 群。
- `data/global-energy.kotoba.edn` は Kotoba 互換の `[e a v tx :add]` EAVT。
- `data/provenance.edn` は source repo・release・件数。
- `coverage/registry.edn` は出典ごとの地理・年・指標 coverage と安全な結合キー。
- `connections/actors.edn` は actor／他リポジトリごとの read-only 接続契約。

再生成は、同階層の `org.worldbank.api` と `org.ourworldindata` を取得後、`bb bin/build.clj ..` を実行する。入力の raw snapshot は各 source dataset の annex 管理対象であり、ビルドはネットワークへアクセスしない。

Data contract は年・出典を属性として保持する。異なる年や方法論の値を同一観測値として上書きしない。
