package com.vibes.quantum.server.agents

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant

/** File lifecycle and serialization are isolated from the engineering pipeline/orchestrator. */
internal class WorkspaceEngineeringPersistence(
    private val root: Path,
    private val json: Json,
) {
    fun readBrain(appId: String, targetApp: String): StoredWorkspaceBrain {
        val file = brainPath(appId, targetApp)
        return runCatching {
            if (!Files.isRegularFile(file)) StoredWorkspaceBrain(targetApp = targetApp)
            else json.decodeFromString<StoredWorkspaceBrain>(Files.readString(file, StandardCharsets.UTF_8)).let { stored ->
                if (stored.targetApp.equals(targetApp, ignoreCase = true)) stored else StoredWorkspaceBrain(targetApp = targetApp)
            }
        }.getOrElse { StoredWorkspaceBrain(targetApp = targetApp) }
    }

    fun writeBrain(appId: String, ledger: StoredWorkspaceBrain): Boolean {
        val file = brainPath(appId, ledger.targetApp)
        return runCatching {
            Files.createDirectories(file.parent)
            val temp = Files.createTempFile(file.parent, "brain-", ".tmp")
            try {
                Files.writeString(temp, json.encodeToString(StoredWorkspaceBrain.serializer(), ledger), StandardCharsets.UTF_8)
                moveAtomically(temp, file)
                setPrivate(file)
            } finally { Files.deleteIfExists(temp) }
            true
        }.getOrDefault(false)
    }

    fun brainPath(appId: String, targetApp: String): Path {
        val key = MessageDigest.getInstance("SHA-256")
            .digest((appId + "\u0000" + targetApp.trim().lowercase()).toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }.take(32)
        val base = root.resolve("brain").toAbsolutePath().normalize()
        val file = base.resolve("$key.json").normalize()
        require(file.startsWith(base)) { "Brain memory path escaped its private directory." }
        return file
    }

    fun persistJob(record: StoredWorkspaceJob) {
        runCatching {
            val dir = safeJobDirectory(record)
            val target = dir.resolve("job.json")
            val temp = Files.createTempFile(dir, "job-", ".tmp")
            try {
                Files.writeString(temp, json.encodeToString(StoredWorkspaceJob.serializer(), record), StandardCharsets.UTF_8)
                moveAtomically(temp, target)
                setPrivate(target)
            } finally { Files.deleteIfExists(temp) }
        }
    }

    fun loadStoredJobs(limit: Int): List<StoredWorkspaceJob> {
        if (!Files.isDirectory(root)) return emptyList()
        return Files.list(root).use { directories ->
            directories.filter { Files.isDirectory(it) && it.fileName.toString().matches(Regex("ws_[A-Za-z0-9]{12,30}")) }
                .limit(limit.toLong()).map { loadRecord(it) }.filter { it != null }.map { it!! }.toList()
        }
    }

    fun loadOne(id: String): StoredWorkspaceJob? = loadRecord(root.resolve(id))

    fun loadRecord(dir: Path): StoredWorkspaceJob? = runCatching {
        val normalized = dir.toAbsolutePath().normalize()
        if (!normalized.startsWith(root) || !Files.isDirectory(normalized)) return null
        val file = normalized.resolve("job.json")
        if (!Files.isRegularFile(file)) return null
        val record = json.decodeFromString<StoredWorkspaceJob>(Files.readString(file, StandardCharsets.UTF_8))
        if (record.id != dir.fileName.toString() || !record.id.matches(Regex("ws_[A-Za-z0-9]{12,30}"))) return null
        record
    }.getOrNull()

    private fun safeJobDirectory(record: StoredWorkspaceJob): Path {
        val dir = Path.of(record.jobDir).toAbsolutePath().normalize()
        require(dir.startsWith(root) && dir != root) { "Job directory is outside workspace root." }
        return dir
    }

    private fun moveAtomically(from: Path, to: Path) {
        try { Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
        catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(from, to, StandardCopyOption.REPLACE_EXISTING) }
    }

    private fun setPrivate(path: Path) {
        runCatching { Files.setPosixFilePermissions(path, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")) }
    }
}
