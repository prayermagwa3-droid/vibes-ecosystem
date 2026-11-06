package com.vibes.quantum.server

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.util.Properties
import java.util.concurrent.atomic.AtomicBoolean

/** Small JDBC boundary with explicit transactions and versioned, reversible SQL migrations. */
class VibesDatabase private constructor(
    private val jdbcUrl: String,
    private val username: String?,
    private val password: String?,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    fun connection(): Connection {
        check(!closed.get()) { "Database is closed" }
        val props = Properties()
        username?.takeIf { it.isNotBlank() }?.let { props.setProperty("user", it) }
        password?.takeIf { it.isNotBlank() }?.let { props.setProperty("password", it) }
        val connection = DriverManager.getConnection(jdbcUrl, props)
        if (jdbcUrl.startsWith("jdbc:sqlite:")) {
            connection.createStatement().use { it.execute("PRAGMA foreign_keys=ON") }
            connection.createStatement().use { it.execute("PRAGMA busy_timeout=10000") }
        }
        return connection
    }

    fun <T> transaction(block: (Connection) -> T): T = connection().use { connection ->
        connection.autoCommit = false
        try {
            val result = block(connection)
            connection.commit()
            result
        } catch (failure: Throwable) {
            runCatching { connection.rollback() }
            throw failure
        }
    }

    private fun ensureMigrationTable() {
        connection().use { c -> c.createStatement().use { it.execute("CREATE TABLE IF NOT EXISTS schema_migrations (version BIGINT PRIMARY KEY, name VARCHAR(200) NOT NULL, checksum VARCHAR(64) NOT NULL, applied_at_ms BIGINT NOT NULL)") } }
    }

    fun migrate() {
        ensureMigrationTable()
        migrations.forEach { migration ->
            transaction { c ->
                // Re-check under the same transaction so concurrent application starts do not re-apply a version.
                val applied = c.prepareStatement("SELECT checksum FROM schema_migrations WHERE version=?").use { p ->
                    p.setLong(1, migration.version); p.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
                }
                if (applied != null) {
                    require(applied == migration.checksum) { "Applied migration ${migration.name} checksum differs from the packaged migration" }
                } else {
                    migration.up.split(';').map(String::trim).filter(String::isNotEmpty).forEach { sql -> c.createStatement().use { it.execute(sql) } }
                    c.prepareStatement("INSERT INTO schema_migrations(version,name,checksum,applied_at_ms) VALUES(?,?,?,?)").use { p ->
                        p.setLong(1, migration.version); p.setString(2, migration.name); p.setString(3, migration.checksum); p.setLong(4, System.currentTimeMillis()); p.executeUpdate()
                    }
                }
            }
        }
    }

    /** Roll back exactly the latest migration. This is an operator action, never automatic on startup. */
    fun rollbackLastMigration(): String? {
        val current = connection().use { c -> c.createStatement().use { s -> s.executeQuery("SELECT version,name FROM schema_migrations ORDER BY version DESC").use { rs -> if (rs.next()) rs.getLong(1) to rs.getString(2) else null } } } ?: return null
        val migration = migrations.firstOrNull { it.version == current.first } ?: error("No down migration is packaged for ${current.second}")
        transaction { c ->
            migration.down.split(';').map(String::trim).filter(String::isNotEmpty).forEach { sql -> c.createStatement().use { it.execute(sql) } }
            c.prepareStatement("DELETE FROM schema_migrations WHERE version=?").use { p -> p.setLong(1, migration.version); p.executeUpdate() }
        }
        return migration.name
    }

    /** Read a bounded JSON document migrated from a legacy file into the shared SQL store. */
    fun readDocument(key: String): String? = connection().use { c ->
        c.prepareStatement("SELECT json_value FROM document_store WHERE store_key=?").use { p ->
            p.setString(1, key)
            p.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }
    }

    /** Transactional upsert shared by small structured stores while they migrate to normalized tables. */
    fun writeDocument(key: String, json: String) {
        require(key.matches(Regex("[A-Za-z0-9_.:-]{1,200}"))) { "Invalid document-store key" }
        require(json.length <= 64 * 1024 * 1024) { "Document exceeds the 64 MiB storage limit" }
        transaction { c ->
            val changed = c.prepareStatement("UPDATE document_store SET json_value=?,updated_at_ms=? WHERE store_key=?").use { p ->
                p.setString(1, json); p.setLong(2, System.currentTimeMillis()); p.setString(3, key); p.executeUpdate()
            }
            if (changed == 0) c.prepareStatement("INSERT INTO document_store(store_key,json_value,updated_at_ms) VALUES(?,?,?)").use { p ->
                p.setString(1, key); p.setString(2, json); p.setLong(3, System.currentTimeMillis()); p.executeUpdate()
            }
        }
    }

    fun migrationVersions(): List<Long> = connection().use { c ->
        c.createStatement().use { s -> s.executeQuery("SELECT version FROM schema_migrations ORDER BY version").use { rs -> buildList { while (rs.next()) add(rs.getLong(1)) } } }
    }

    override fun close() { closed.set(true) }

    private data class Migration(val version: Long, val name: String, val up: String, val down: String, val checksum: String)
    private val migrations: List<Migration> by lazy {
        val loader = javaClass.classLoader
        listOf(1L to "release_ledger", 2L to "platform_storage", 3L to "cloud_reservations").map { (version, name) ->
            val up = loader.getResourceAsStream("db/migration/V${version.toString().padStart(4, '0')}__${name}.sql")?.bufferedReader()?.use { it.readText() }
                ?: error("Missing up migration $name")
            val down = loader.getResourceAsStream("db/migration/U${version.toString().padStart(4, '0')}__${name}.sql")?.bufferedReader()?.use { it.readText() }
                ?: error("Missing down migration $name")
            Migration(version, name, up, down, java.security.MessageDigest.getInstance("SHA-256").digest(up.toByteArray()).joinToString("") { "%02x".format(it) })
        }
    }

    companion object {
        fun open(jdbcUrl: String, username: String? = null, password: String? = null, migrateOnOpen: Boolean = true): VibesDatabase {
            require(jdbcUrl.startsWith("jdbc:sqlite:") || jdbcUrl.startsWith("jdbc:postgresql:")) { "Database URL must use jdbc:sqlite: or jdbc:postgresql:" }
            if (jdbcUrl.startsWith("jdbc:sqlite:")) {
                val rawPath = jdbcUrl.removePrefix("jdbc:sqlite:").substringBefore('?')
                if (rawPath.isNotBlank() && rawPath != ":memory:") Path.of(rawPath).toAbsolutePath().parent?.let { Files.createDirectories(it) }
                Class.forName("org.sqlite.JDBC")
            } else Class.forName("org.postgresql.Driver")
            return VibesDatabase(jdbcUrl, username, password).also { database ->
                if (jdbcUrl.startsWith("jdbc:sqlite:")) database.connection().use { connection ->
                    connection.createStatement().use { it.execute("PRAGMA journal_mode=WAL") }
                    connection.createStatement().use { it.execute("PRAGMA synchronous=NORMAL") }
                }
                database.ensureMigrationTable()
                if (migrateOnOpen) database.migrate()
            }
        }

        fun fromEnvironment(migrateOnOpen: Boolean = true): VibesDatabase {
            val configuredUrl = System.getenv("VIBES_DATABASE_URL")?.trim()?.takeIf { it.isNotEmpty() } ?: "jdbc:sqlite:data/vibes-quantum.db"
            val envUser = System.getenv("VIBES_DATABASE_USER")
            val envPassword = System.getenv("VIBES_DATABASE_PASSWORD")
            if (configuredUrl.startsWith("postgres://", ignoreCase = true) || configuredUrl.startsWith("postgresql://", ignoreCase = true)) {
                // Render and other providers commonly supply a URI-form connection string
                // (postgresql://user:password@host:5432/database), while JDBC requires
                // jdbc:postgresql://host:5432/database with credentials in Properties.
                val uri = java.net.URI(configuredUrl)
                val authority = uri.rawAuthority?.substringAfterLast('@')?.takeIf { it.isNotBlank() }
                    ?: error("VIBES_DATABASE_URL is missing its PostgreSQL host")
                val jdbcUrl = "jdbc:postgresql://$authority${uri.rawPath.orEmpty()}${uri.rawQuery?.let { "?$it" }.orEmpty()}"
                val userInfo = uri.userInfo.orEmpty()
                val uriUser = userInfo.substringBefore(':').takeIf { it.isNotBlank() }
                val uriPassword = userInfo.substringAfter(':', "").takeIf { userInfo.contains(':') }
                return open(jdbcUrl, envUser ?: uriUser, envPassword ?: uriPassword, migrateOnOpen)
            }
            return open(configuredUrl, envUser, envPassword, migrateOnOpen)
        }
    }
}

/** CLI entry used by the Gradle rollbackDatabase task. */
object DatabaseMigrationCli {
    @JvmStatic fun main(args: Array<String>) {
        val database = VibesDatabase.fromEnvironment(migrateOnOpen = false)
        try {
            if (args.firstOrNull() == "rollback") {
                val rolled = database.rollbackLastMigration() ?: "No migration to roll back"
                println(rolled)
            } else println("Applied schema versions: ${database.migrationVersions()}")
        } finally { database.close() }
    }
}
