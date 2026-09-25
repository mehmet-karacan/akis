# PostgreSQL target ledger (`akis` schema)

Target-local publication evidence for PostgreSQL targets — the counterpart of `database/oracle` (ETL_KANIT_PKG).
These objects are installed **in each target PostgreSQL database**, never in the AKIS metadata database.

| Script | Run as | Purpose |
|---|---|---|
| `001_ledger_schema.sql` | ledger owner (DBA) | schema, installation identity (generated once), fence, batch and publish evidence tables, append-only triggers |
| `002_ledger_functions.sql` | ledger owner | `cit_al/cit_oku`, `yayin_hazirla/yayin_kaydet/yayin_dogrula`, `parti_hazirla/parti_kaydet/parti_dogrula` |
| `003_runtime_grants.sql` | ledger owner | explicit EXECUTE grant only for the eight ledger API functions; ledger table DML stays closed |
| `005_move_ledger_to_akis.sql` | legacy ledger owner | existing `akis_yayin_defteri` objects and data are moved under `akis`, then the legacy schema is removed |
| `validate.sql` | anyone | read-only installation check |

```
psql -d <target> -v akis_runtime=akis_app -f 001_ledger_schema.sql -f 002_ledger_functions.sql -f 003_runtime_grants.sql
psql -d <target> -f validate.sql
```

Existing installations must run `005_move_ledger_to_akis.sql` once, followed by
`003_runtime_grants.sql`. New installations use `akis` directly and do not need the migration script.

Semantics match Oracle: prepare/record must run in one transaction (the guard is a transaction-local setting), the fence
token is monotonic per canonical target, and reconciliation reads use a fresh connection. Errors carry SQLSTATE `AK0nn`
where `nn` equals the Oracle `-200nn` code the Java adapter maps (010 fence missing, 011 ownership, 012 stale token,
013 owner conflict, 015/016/019 batch conflict, 022/025 publish conflict, 014/018/021/024 transaction protocol).

Unlike Oracle, `TRUNCATE` is transactional here, so a Faz A publish is a single transaction:
`yayin_hazirla → TRUNCATE ONLY target → INSERT … SELECT FROM work → yayin_kaydet → COMMIT`.
