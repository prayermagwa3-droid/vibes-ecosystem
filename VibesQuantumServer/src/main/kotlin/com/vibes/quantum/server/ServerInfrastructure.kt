package com.vibes.quantum.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.BufferedWriter
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.StandardCopyOption
import java.util.Comparator
import java.time.Instant
import java.time.Duration
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

private val infraJson = Json { encodeDefaults = true }
private val infraLog = LoggerFactory.getLogger("VibesServer")

@Serializable
data class StructuredRequestLog(
    val timestamp: String, val event: String = "http_request", val requestId: String,
    val method: String, val path: String, val status: Int, val durationMs: Double,
    val outcome: String, val administrative: Boolean
)


@Serializable
data class PrivilegedSecurityEvent(
    val timestamp: String,
    val actor: String,
    val action: String,
    val route: String,
    val outcome: String,
    val event: String = "privileged_action",
)

/** Append-only JSONL audit trail. Never records headers, tokens, request bodies, or query strings. */
class SecurityAuditLog(private val file: Path) {
    @Synchronized fun append(event: StructuredRequestLog) = appendJson(infraJson.encodeToString(event))

    @Synchronized fun appendPrivileged(event: PrivilegedSecurityEvent) = appendJson(infraJson.encodeToString(event))

    private fun appendJson(line: String) {
        val absolute = file.toAbsolutePath().normalize()
        Files.createDirectories(absolute.parent)
        val localLock = auditFileLocks.computeIfAbsent(absolute.toString()) { Any() }
        synchronized(localLock) {
            val bytes = ByteBuffer.wrap((line + "\n").toByteArray(Charsets.UTF_8))
            FileChannel.open(absolute, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND).use { channel ->
                // The JVM lock prevents overlapping locks in this process; the OS lock coordinates
                // separate Ktor/JVM instances sharing the same audit volume.
                channel.lock().use {
                    while (bytes.hasRemaining()) channel.write(bytes)
                    channel.force(true)
                }
            }
        }
        runCatching { Files.setPosixFilePermissions(absolute, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")) }
        infraLog.info(line)
    }

    companion object {
        private val auditFileLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()
    }
}

@Serializable
data class BackupMetadata(val id: String, val createdAt: String, val files: Int, val bytes: Long, val sha256: String)

@Serializable
data class BackupRestoreDrillReport(
    val completedAt: String,
    val status: String,
    val backupId: String? = null,
    val restoredFiles: Int = 0,
    val restoredBytes: Long = 0,
    val durationMs: Long = 0,
    val errorCode: String? = null,
)

/**
 * File-backed snapshot service. Restore drills always extract to an isolated scratch directory,
 * verify the extracted tree against the ZIP contents, and remove scratch data afterwards.
 */
class FileBackupManager(private val dataDirectory: Path, private val backupDirectory: Path) {
    private val lock = Any()
    private val drillReportFile: Path get() = backupDirectory.resolve("restore-drill-latest.json")
    private val scratchRoot: Path get() = backupDirectory.resolve(".restore-scratch")

    fun create(): BackupMetadata = synchronized(lock) {
        require(Files.isDirectory(dataDirectory)) { "Data directory does not exist" }
        Files.createDirectories(backupDirectory)
        restrictDirectory(backupDirectory)
        val backupRoot = backupDirectory.toAbsolutePath().normalize()
        val id = "vibes-${DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(java.time.ZoneOffset.UTC).format(Instant.now())}-${java.util.UUID.randomUUID().toString().take(8)}"
        val target = backupDirectory.resolve("$id.zip")
        var count = 0
        var bytes = 0L
        ZipOutputStream(Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)).use { zip ->
            Files.walk(dataDirectory).use { paths ->
                paths.filter {
                    Files.isRegularFile(it) && !Files.isSymbolicLink(it) &&
                        !it.toAbsolutePath().normalize().startsWith(backupRoot)
                }.sorted().forEach { file ->
                    val rel = dataDirectory.relativize(file).toString().replace('\\', '/')
                    zip.putNextEntry(ZipEntry(rel))
                    Files.newInputStream(file).use { input -> bytes += input.copyTo(zip) }
                    zip.closeEntry()
                    count++
                }
            }
        }
        restrictFile(target)
        val digest = sha256(target)
        verify(target)
        BackupMetadata(id, Instant.now().toString(), count, bytes, digest)
    }

    fun list(): List<String> {
        if (!Files.isDirectory(backupDirectory)) return emptyList()
        Files.list(backupDirectory).use { s ->
            return s.filter { it.fileName.toString().matches(Regex("vibes-[A-Za-z0-9-]+\\.zip")) }
                .map { it.fileName.toString().removeSuffix(".zip") }.sorted().toList()
        }
    }

    fun verifyArchive(id: String): Boolean = verify(resolve(id))

    fun latestRestoreDrill(): BackupRestoreDrillReport? = synchronized(lock) {
        val path = drillReportFile
        if (!Files.isRegularFile(path)) return null
        runCatching { infraJson.decodeFromString<BackupRestoreDrillReport>(Files.readString(path)) }.getOrNull()
    }

    fun isRestoreDrillDue(intervalDays: Long, now: Instant = Instant.now()): Boolean {
        require(intervalDays >= 1)
        val latest = latestRestoreDrill() ?: return true
        val completedAt = runCatching { Instant.parse(latest.completedAt) }.getOrNull() ?: return true
        val retryAfter = if (latest.status == "passed") Duration.ofDays(intervalDays) else Duration.ofHours(24)
        return !now.isBefore(completedAt.plus(retryAfter))
    }

    /**
     * Shared-volume lease prevents every server replica from running the same scheduled backup
     * or restore drill simultaneously. The OS file lock is released automatically on process exit.
     */
    fun withSchedulerLease(action: () -> Unit) {
        Files.createDirectories(backupDirectory)
        val leasePath = backupDirectory.resolve(".scheduler.lock").toAbsolutePath().normalize()
        FileChannel.open(leasePath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            val lease = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
            if (lease != null) lease.use {
                restrictFile(leasePath)
                action()
            }
        }
    }

    /** Creates a fresh archive, restores it into scratch, and byte-compares every restored entry. */
    fun runScratchRestoreTest(): BackupRestoreDrillReport = synchronized(lock) {
        val started = System.nanoTime()
        var backupId: String? = null
        var scratch: Path? = null
        var cleanupFailed = false
        var report: BackupRestoreDrillReport
        try {
            val metadata = create()
            backupId = metadata.id
            Files.createDirectories(scratchRoot)
            restrictDirectory(scratchRoot)
            scratch = Files.createTempDirectory(scratchRoot, "run-")
            restrictDirectory(scratch!!)
            val restoredCount = restoreTo(metadata.id, scratch)
            val archiveManifest = archiveManifest(resolve(metadata.id))
            val restoredManifest = directoryManifest(scratch)
            require(archiveManifest == restoredManifest) { "Scratch restore contents do not match the backup archive" }
            val restoredBytes = restoredManifest.values.sumOf { it.second }
            require(restoredCount == archiveManifest.size && restoredCount == metadata.files) { "Scratch restore file count mismatch" }
            report = BackupRestoreDrillReport(
                completedAt = Instant.now().toString(), status = "passed", backupId = metadata.id,
                restoredFiles = restoredCount, restoredBytes = restoredBytes,
                durationMs = Duration.ofNanos(System.nanoTime() - started).toMillis()
            )
        } catch (failure: Exception) {
            infraLog.error("Automated scratch restore drill failed", failure)
            report = BackupRestoreDrillReport(
                completedAt = Instant.now().toString(), status = "failed", backupId = backupId,
                durationMs = Duration.ofNanos(System.nanoTime() - started).toMillis(),
                errorCode = "RESTORE_DRILL_FAILED"
            )
        } finally {
            scratch?.let { directory ->
                runCatching { Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } } }
                    .onFailure { cleanupFailed = true; infraLog.error("Could not remove scratch restore directory", it) }
            }
        }
        if (cleanupFailed && report.status == "passed") {
            report = report.copy(completedAt = Instant.now().toString(), status = "failed", errorCode = "SCRATCH_CLEANUP_FAILED")
        }
        persistDrillReport(report)
        report
    }

    private fun persistDrillReport(report: BackupRestoreDrillReport) {
        Files.createDirectories(backupDirectory)
        val temporary = Files.createTempFile(backupDirectory, ".restore-drill-", ".tmp")
        try {
            Files.writeString(temporary, infraJson.encodeToString(report), StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
            try { Files.move(temporary, drillReportFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(temporary, drillReportFile, StandardCopyOption.REPLACE_EXISTING) }
            restrictFile(drillReportFile)
        } finally { Files.deleteIfExists(temporary) }
    }

    private fun restrictDirectory(path: Path) {
        runCatching { Files.setPosixFilePermissions(path, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")) }
    }

    private fun restrictFile(path: Path) {
        runCatching { Files.setPosixFilePermissions(path, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")) }
    }

    private fun archiveManifest(path: Path): Map<String, Pair<String, Long>> {
        val manifest = linkedMapOf<String, Pair<String, Long>>()
        ZipInputStream(Files.newInputStream(path)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(!entry.isDirectory && !entry.name.startsWith("/") && !entry.name.split('/').contains("..")) { "Unsafe backup entry" }
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                var size = 0L
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val read = zip.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                    size += read
                }
                val key = entry.name.replace('\\', '/')
                require(!manifest.containsKey(key)) { "Duplicate backup entry" }
                manifest[key] = digest.digest().joinToString("") { "%02x".format(it) } to size
            }
        }
        require(manifest.isNotEmpty()) { "Backup is empty" }
        return manifest
    }

    private fun directoryManifest(directory: Path): Map<String, Pair<String, Long>> {
        val manifest = linkedMapOf<String, Pair<String, Long>>()
        Files.walk(directory).use { paths ->
            paths.filter { Files.isRegularFile(it) }.sorted().forEach { file ->
                val key = directory.relativize(file).toString().replace('\\', '/')
                manifest[key] = sha256(file) to Files.size(file)
            }
        }
        return manifest
    }

    private fun sha256(path: Path): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun resolve(id: String): Path {
        require(id.matches(Regex("vibes-[A-Za-z0-9-]+"))) { "Invalid backup ID" }
        val path = backupDirectory.resolve("$id.zip").normalize()
        require(path.parent == backupDirectory.normalize()) { "Invalid backup path" }
        return path
    }

    private fun verify(path: Path): Boolean {
        require(Files.isRegularFile(path)) { "Backup not found" }
        archiveManifest(path)
        return true
    }

    /** Restores into a separate target directory; caller must explicitly choose a safe empty/staging directory. */
    fun restoreTo(id: String, targetDirectory: Path): Int = synchronized(lock) {
        val archive = resolve(id)
        verify(archive)
        val target = targetDirectory.toAbsolutePath().normalize()
        Files.createDirectories(target)
        require(Files.list(target).use { !it.findAny().isPresent }) { "Restore target must be empty" }
        var restored = 0
        ZipInputStream(Files.newInputStream(archive)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(!entry.isDirectory && !entry.name.startsWith("/") && !entry.name.split('/').contains("..")) { "Unsafe backup entry" }
                val out = target.resolve(entry.name).normalize()
                require(out.startsWith(target) && out != target) { "Unsafe backup path" }
                Files.createDirectories(out.parent)
                Files.newOutputStream(out, StandardOpenOption.CREATE_NEW).use { zip.copyTo(it) }
                restored++
            }
        }
        restored
    }
}

class BackupScheduler(
    private val manager: FileBackupManager,
    backupIntervalHours: Long = 24,
    restoreTestIntervalDays: Long = 7,
) : AutoCloseable {
    private val executor = Executors.newScheduledThreadPool(2) { r -> Thread(r, "vibes-backup-scheduler").apply { isDaemon = true } }
    init {
        require(backupIntervalHours >= 1)
        require(restoreTestIntervalDays >= 1)
        executor.scheduleWithFixedDelay({
            runCatching { manager.withSchedulerLease { manager.create() } }
                .onFailure { infraLog.error("Automated backup failed", it) }
        }, backupIntervalHours, backupIntervalHours, TimeUnit.HOURS)
        // First run is immediate when no successful drill exists; after success the stored timestamp
        // makes this a weekly task even across server restarts. Failed drills retry on the hourly check.
        // A shared-volume OS lease ensures only one replica performs a drill at a time.
        executor.scheduleWithFixedDelay({
            runCatching {
                manager.withSchedulerLease {
                    if (manager.isRestoreDrillDue(restoreTestIntervalDays)) {
                        val report = manager.runScratchRestoreTest()
                        if (report.status != "passed") infraLog.error("Weekly scratch restore drill did not pass")
                        else infraLog.info("Weekly scratch restore drill passed: backupId=${report.backupId}, files=${report.restoredFiles}, bytes=${report.restoredBytes}")
                    }
                }
            }.onFailure { infraLog.error("Could not schedule weekly scratch restore drill", it) }
        }, 0, 1, TimeUnit.HOURS)
    }
    override fun close() { executor.shutdownNow() }
}

