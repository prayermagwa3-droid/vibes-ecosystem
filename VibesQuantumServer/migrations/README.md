# Database migrations

The active Kotlin server migration runner loads paired, classpath SQL files from `src/main/resources/db/migration/`: `VNNNN__name.sql` applies a schema change and `UNNNN__name.sql` reverses it. Every up migration is SHA-256 checksummed in `schema_migrations`; changing an already-applied migration is rejected. Migrations execute in JDBC transactions.

Use `./gradlew databaseStatus` to inspect applied schema versions and `./gradlew rollbackDatabase` to roll back the most recent migration. The rollback task does not create a backup automatically. Stop writers and create/verify a database backup before rollback. Startup applies pending migrations; rollback is never automatic. SQLite is intended for one server process. Use PostgreSQL for multi-instance operation.

The older `0001_release_ledger.*.sql` pair in this directory is retained as a historical source copy; the active runner uses the V0001/V0002/V0003 copies in the classpath migration directory. JSON documents are imported per store when no database row exists. Not every legacy file store has been migrated; consult `README.md` for the remaining paths.
