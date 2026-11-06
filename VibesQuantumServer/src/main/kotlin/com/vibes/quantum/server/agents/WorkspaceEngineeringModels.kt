package com.vibes.quantum.server.agents

import kotlinx.serialization.Serializable

@Serializable
data class WorkspaceCreateRequest(
    val targetApp: String,
    val goal: String,
    val continuous: Boolean = false,
    val maxIterations: Int = 2
)

@Serializable
data class WorkspaceJobView(
    val id: String,
    val targetApp: String,
    val goal: String,
    val state: String,
    val phase: String,
    val continuous: Boolean,
    val cycle: Int,
    val iteration: Int,
    val maxIterations: Int,
    val progress: List<String>,
    val research: String,
    val plan: String,
    val baselineSummary: String,
    val testSummary: String,
    val evaluation: String,
    val patch: String,
    val candidateZipReady: Boolean,
    val message: String,
    val createdAt: Long,
    val updatedAt: Long,
    val agentReports: List<WorkspaceAgentReport> = emptyList(),
    val projectProfile: WorkspaceProjectProfile? = null,
    val artifactZipReady: Boolean = false,
    val artifactSummary: String = "Build artifacts are not available yet.",
    val engineeringAudit: String = ""
)

@Serializable
data class WorkspaceAgentReport(
    val id: String,
    val name: String,
    val stage: String,
    val status: String = "QUEUED",
    val summary: String = "Waiting for its assignment.",
    val updatedAt: Long = 0
)

@Serializable
data class WorkspaceProjectProfile(
    val framework: String = "Unknown / source-only",
    val languages: List<String> = emptyList(),
    val dependencyManifests: List<String> = emptyList(),
    val dependencies: List<String> = emptyList(),
    val entryPoints: List<String> = emptyList(),
    val buildCommand: String = "",
    val buildDescription: String = "",
    val ecosystem: String = "unknown",
    val warnings: List<String> = emptyList()
)

@Serializable
data class WorkspaceBrainLesson(
    val id: String,
    val targetApp: String,
    val category: String,
    val summary: String,
    val evidence: String,
    val confidence: Int,
    val outcome: String,
    val createdAt: Long
)

@Serializable
data class WorkspaceBrainView(
    val targetApp: String,
    val totalCycles: Int = 0,
    val verifiedPasses: Int = 0,
    val failedCycles: Int = 0,
    val blockedCycles: Int = 0,
    val successRatePercent: Int = 0,
    val lessons: List<WorkspaceBrainLesson> = emptyList(),
    val diagnosis: String = "No reflections yet. Run a job, then ask the brain to reflect on measured results.",
    val updatedAt: Long = 0
)

@Serializable
data class WorkspaceBrainReflectRequest(val targetApp: String)

