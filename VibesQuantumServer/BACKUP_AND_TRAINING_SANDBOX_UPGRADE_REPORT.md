# Vibes Quantum Server 2.9.0 — Backup restore automation and training sandbox hardening

## Scope

This release implements the next two operational/security requirements on top of the 2.8.0 server baseline:

1. A recurring weekly scratch restore drill for the configured backup volume.
2. A training-worker isolation gate that has no host-process execution fallback and keeps training endpoints unavailable until every required sandbox control can be attested.

## 1. Automated backup restore drill

`BackupScheduler` now owns two recurring tasks:

- Daily backup creation (24-hour default, configurable with `VIBES_BACKUP_INTERVAL_HOURS`).
- Restore-drill due checks every hour. A successful drill is next due after seven days by default (`VIBES_BACKUP_RESTORE_TEST_INTERVAL_DAYS`). If no successful drill exists, the first due check runs immediately. A failed drill becomes eligible for retry after 24 hours.

Each drill:

1. Creates a fresh backup archive from the configured data directory.
2. Restores the archive into a newly-created private scratch directory under the backup volume. It never restores over the live data directory.
3. Validates archive paths and rejects traversal/duplicate entries.
4. Compares the relative path, byte count and SHA-256 digest of every extracted file with the archive manifest.
5. Persists the latest result atomically to `restore-drill-latest.json`, including status, backup ID, counts, elapsed time and a non-sensitive error code.
6. Attempts to remove the scratch restore directory in `finally`; cleanup failure turns an otherwise successful drill into a failed result.

A shared-volume OS file lock coordinates scheduler work across replicas. This prevents two server processes sharing the same backup volume from running the same scheduled backup/drill concurrently. Use a persistent filesystem that supports advisory file locks. `GET /api/v1/ops/backups` exposes the latest report and configured interval to an authorized administrator.

## 2. Training worker sandbox gate

The training worker no longer launches the configured trainer as a host process. Its only execution path invokes a Docker/Podman-compatible runtime with:

- An immutable image reference ending in `@sha256:<digest>`.
- A local dependency lock whose file SHA-256 matches `ALIS_DEPENDENCY_LOCK_SHA256`, whose package entries are exact `==` pins with SHA-256 hashes, and whose digest is stamped into the image label `org.vibes.dependency-lock-sha256`.
- Runtime image inspection and an executable-path check for the trainer inside the image.
- `--network=none`, a read-only root filesystem, dropped Linux capabilities, `no-new-privileges`, and a non-root UID/GID.
- Explicit memory, CPU, PID and tmpfs limits; a maximum wall-clock job timeout; a bounded file-descriptor limit.
- Exactly two working-data mounts: the approved dataset read-only and the output directory writable.
- A minimal container environment. Host provider credentials and unrelated host environment variables are not passed to the trainer.
- An explicit artifact suffix allow-list; only allow-listed files can be returned, and path containment, size and SHA-256 are checked before serving.
- Redaction of bearer tokens, labelled credentials, common OpenAI/Google/AWS key prefixes, PEM private-key blocks and signed URL query strings before log persistence or error responses. Worker logs are capped at 2 MiB.

The worker's authenticated `GET /v1/training/health` returns HTTP 503 and `sandboxReady=false` until runtime, image, dependency lock, image label, resource controls, trainer executable and artifact allow-list all pass. Create/get/cancel operations are also unavailable while the sandbox is not ready. The Quantum gateway validates the worker attestation on every training operation and fails closed if any required field is missing or invalid.

## 3. Deployment gate — intentionally not enabled by this source package

This archive does not ship a prebuilt training image or a production dependency lock. A deployer must create and review the complete platform-specific hash lock, build and scan the image from a verified digest-pinned base, publish/pin the final image digest, and configure all required sandbox settings on the worker. Until this is done, training health remains 503 and the training API remains disabled. Keep `VIBES_TRAINING_ENABLED=false` until the health attestation is positive and a reviewed sandbox smoke job has passed. See `workers/quantum-labs/training-service/SANDBOX_DEPLOYMENT.md`.

## 4. Configuration

Backup configuration:

- `VIBES_DATA_DIRECTORY`
- `VIBES_BACKUP_DIRECTORY`
- `VIBES_BACKUP_INTERVAL_HOURS` (default `24`)
- `VIBES_BACKUP_RESTORE_TEST_INTERVAL_DAYS` (default `7`)

Worker sandbox configuration is documented in `workers/quantum-labs/training-service/SANDBOX_DEPLOYMENT.md` and includes `ALIS_SANDBOX_RUNTIME`, `ALIS_SANDBOX_IMAGE`, `ALIS_DEPENDENCY_LOCK_FILE`, `ALIS_DEPENDENCY_LOCK_SHA256`, memory/CPU/PID/tmpfs limits, trainer executable/arguments and `ALIS_ARTIFACT_SUFFIX_ALLOWLIST`.

## 5. Verification performed in this environment

- Python syntax compilation: passed.
- Training worker + tiny trainer focused suite: **12 tests passed**.
- `ServerInfrastructure.kt` Kotlin syntax pass with lightweight compile-time stubs for kotlinx serialization and SLF4J: passed. This validates Kotlin syntax for the edited infrastructure file only; it is not a real dependency-linked Gradle compilation.
- Full Kotlin/Gradle suite: **not run**. The repository wrapper requires Gradle 9.6.1, which is not installed or cached in this environment, and the distribution could not be downloaded.

The new Kotlin `FileBackupManagerTest` cases are included but still require execution in a Gradle-enabled environment. This release is packaged as an implementation candidate, not a claim of fully verified production readiness.
