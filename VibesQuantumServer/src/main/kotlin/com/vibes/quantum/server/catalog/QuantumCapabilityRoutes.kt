package com.vibes.quantum.server.catalog

import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable

@Serializable
data class QuantumToolDescriptor(
    val id: String,
    val name: String,
    val description: String,
    val action: String
)

@Serializable
data class QuantumCategoryDescriptor(
    val id: String,
    val name: String,
    val description: String,
    val tools: List<QuantumToolDescriptor>
)

@Serializable
data class QuantumCategoryCatalog(
    val schemaVersion: Int = 1,
    val source: String = "vibes-quantum-server",
    val categories: List<QuantumCategoryDescriptor>
)

@Serializable
data class QuantumModuleDescriptor(
    val id: String,
    val name: String,
    val sourceFeatureGroups: List<String>,
    val status: String,
    val serverRoutes: List<String>,
    val description: String,
    val migrationNote: String? = null
)

@Serializable
data class QuantumModuleCatalog(
    val schemaVersion: Int = 1,
    val canonicalEngine: String = "VibesQuantumServer",
    val primaryAndroidClient: String = "VibesVyronX",
    val legacyQuantumAndroidRuntime: String = "reference-only",
    val modules: List<QuantumModuleDescriptor>,
    val note: String
)

/**
 * Server-owned capability metadata. Vyron X asks Quantum for this catalogue rather than
 * embedding a permanent category/tool list in the APK. The catalogue describes real server
 * route families and explicitly labels capabilities that still need an external worker.
 */
fun Route.quantumCapabilityRoutes() {
    val catalog = QuantumCategoryCatalog(
        categories = listOf(
            QuantumCategoryDescriptor("general_ai", "General AI", "Everyday questions, explanations and writing assistance.", listOf(
                QuantumToolDescriptor("chat", "AI Chat", "Ask questions and work through ideas with Vibes AI.", "chat"),
                QuantumToolDescriptor("summarize", "Summarizer", "Create a concise summary of supplied material.", "summarize"),
                QuantumToolDescriptor("translate", "Translator", "Translate while retaining source-language context.", "translate")
            )),
            QuantumCategoryDescriptor("programming", "Programming", "Generate, explain, review and improve software.", listOf(
                QuantumToolDescriptor("code_generate", "Code Generator", "Generate code for a specified language and task.", "code"),
                QuantumToolDescriptor("code_explain", "Code Explainer", "Explain how supplied code works.", "explain"),
                QuantumToolDescriptor("code_debug", "Debug Assistant", "Analyse errors and propose evidence-based fixes.", "debug"),
                QuantumToolDescriptor("code_optimize", "Code Optimizer", "Improve clarity, performance and maintainability.", "optimize")
            )),
            QuantumCategoryDescriptor("debugging", "Debugging", "Investigate failures, logs, tests and regressions.", listOf(
                QuantumToolDescriptor("error_analysis", "Error Analysis", "Trace an error from its message and relevant code.", "debug"),
                QuantumToolDescriptor("test_design", "Test Designer", "Design focused unit and integration tests.", "test"),
                QuantumToolDescriptor("regression_review", "Regression Review", "Review a change for likely regressions.", "review")
            )),
            QuantumCategoryDescriptor("architecture", "Software Architecture", "Plan modular systems, interfaces and deployment boundaries.", listOf(
                QuantumToolDescriptor("system_design", "System Designer", "Design a system and its module boundaries.", "architect"),
                QuantumToolDescriptor("api_design", "API Designer", "Design versioned APIs and consistent error contracts.", "api"),
                QuantumToolDescriptor("security_review", "Security Reviewer", "Review trust boundaries and common security risks.", "security")
            )),
            QuantumCategoryDescriptor("mathematics", "Mathematics", "Work through calculations and mathematical reasoning.", listOf(
                QuantumToolDescriptor("solver", "Math Solver", "Solve a supplied mathematical exercise with working.", "solve"),
                QuantumToolDescriptor("proof_review", "Proof Review", "Check assumptions and steps in a proof.", "proof")
            )),
            QuantumCategoryDescriptor("android", "Android Development", "Build and maintain Kotlin and Android applications.", listOf(
                QuantumToolDescriptor("kotlin_assistant", "Kotlin Assistant", "Help with Kotlin code and coroutines.", "kotlin"),
                QuantumToolDescriptor("compose_builder", "Compose UI Builder", "Design Jetpack Compose UI and state flows.", "compose"),
                QuantumToolDescriptor("gradle_assistant", "Gradle Assistant", "Analyse Gradle configuration and dependency issues.", "gradle"),
                QuantumToolDescriptor("android_debugger", "Android Debugger", "Investigate Android build and runtime errors.", "android_debug")
            )),
            QuantumCategoryDescriptor("database", "Database Engineering", "Model data, design queries and plan migrations.", listOf(
                QuantumToolDescriptor("schema_design", "Schema Designer", "Design normalized schemas and indexes.", "database"),
                QuantumToolDescriptor("sql_assistant", "SQL Assistant", "Write and explain SQL queries.", "sql"),
                QuantumToolDescriptor("migration_review", "Migration Reviewer", "Review data migrations and rollback safety.", "migration")
            )),
            QuantumCategoryDescriptor("web", "Web Development", "Build frontends, services and integrations.", listOf(
                QuantumToolDescriptor("frontend_builder", "Frontend Builder", "Create accessible HTML, CSS and JavaScript.", "frontend"),
                QuantumToolDescriptor("backend_builder", "Backend Builder", "Design backend services and request handling.", "backend"),
                QuantumToolDescriptor("integration_builder", "Integration Builder", "Plan secure third-party integrations.", "integration")
            )),
            QuantumCategoryDescriptor("devops", "DevOps", "Build, deploy, monitor and operate software systems.", listOf(
                QuantumToolDescriptor("deployment_plan", "Deployment Planner", "Create a safe deployment and rollback plan.", "deploy"),
                QuantumToolDescriptor("ci_assistant", "CI Assistant", "Design build and test automation.", "ci"),
                QuantumToolDescriptor("observability", "Observability Assistant", "Plan logs, metrics, tracing and alerts.", "observability")
            )),
            QuantumCategoryDescriptor("research", "Research", "Explore technical sources with provenance and review gates.", listOf(
                QuantumToolDescriptor("technical_research", "Technical Researcher", "Research a technical question using available connectors.", "research"),
                QuantumToolDescriptor("evidence_review", "Evidence Review", "Assess source quality and evidence gaps.", "evidence")
            ))
        )
    )

    val modules = QuantumModuleCatalog(
        modules = listOf(
            QuantumModuleDescriptor("provider_orchestration", "AI provider orchestration", listOf("ai", "alis.foundation", "deployment"), "server-native", listOf("/api/v1/chat/completions", "/api/v1/models", "/api/v1/providers/status", "/api/v1/council/completions"), "Provider credentials, model routing, chat inference and multi-role review are owned by the Ktor server."),
            QuantumModuleDescriptor("agent_management", "Multi-agent intelligence", listOf("agent", "orchestration", "alis.teachers"), "server-native", listOf("/api/v1/agents", "/api/v1/agents/capabilities", "/api/v1/agents/tasks", "/api/v1/council/roles"), "Agent execution, task records and approval decisions are server-controlled."),
            QuantumModuleDescriptor("developer_workspace", "Developer workspace and project engineering", listOf("workspace", "engineering", "upgrade"), "server-native", listOf("/api/v1/workspace/jobs", "/api/v1/workspace/brain", "/api/v1/workspace/supervisor"), "Project inspection, engineering jobs, archive intake and build-artifact reporting are handled by Quantum. Code is not executed outside the configured sandbox/worker."),
            QuantumModuleDescriptor("knowledge_and_learning", "Knowledge, learning and review", listOf("alis.learning", "autolearn", "review", "curriculum"), "server-backed", listOf("/api/v1/learning", "/api/v1/curriculum", "/api/v1/knowledge/approved", "/api/v1/quality"), "Consent, feedback, reviewed curriculum and approved knowledge are server-managed. Local trainer queues remain migration sources until their sync flow is retired."),
            QuantumModuleDescriptor("research_connectors", "Research and source discovery", listOf("web", "connectors", "research"), "server-native", listOf("/api/v1/research/connectors", "/api/v1/research/runs", "/api/v1/research/candidates"), "Server-side connectors run under explicit budgets and place candidates in human review."),
            QuantumModuleDescriptor("memory_and_retrieval", "Memory, retrieval and knowledge graph", listOf("memory", "retrieval", "rag", "alis.knowledge"), "server-backed", listOf("/api/v1/unified-memory", "/api/v1/memory-index", "/api/v1/memory-lifecycle", "/api/v1/personal-memory"), "Shared and account memory services are server-side; strictly private vault content remains client-encrypted."),
            QuantumModuleDescriptor("quality_and_evaluation", "Quality, evaluation and governance", listOf("ai.quality", "alis.learning", "engineering"), "server-backed", listOf("/api/v1/quality", "/api/v1/quality/languages", "/api/v1/quality/domains", "/api/v1/council/review-queue"), "Language metadata, domain policies, review queues and quality controls are managed by Quantum."),
            QuantumModuleDescriptor("training_control_plane", "Training datasets and training-job control plane", listOf("alis.training", "alis.checkpoints", "alis.models"), "partial-worker-required", listOf("/api/v1/learning", "/api/v1/curriculum", "/api/v1/training/jobs"), "Dataset curation and approval are server-backed. Actual model-weight training requires a separately deployed trusted worker configured on the server; the API returns an explicit unavailable error when no worker is configured.", "On-device weight training is retained only as legacy/reference code and is not part of the canonical production runtime."),
            QuantumModuleDescriptor("security_and_identity", "Authentication, permissions and security", listOf("security", "auth", "vault"), "server-native", listOf("/api/v1/auth/google", "/api/v1/auth/me", "/api/v1/me/encrypted-vault", "/api/v1/security"), "Account validation, app installation identity, key scopes, security operations and encrypted-vault storage are server-managed."),
            QuantumModuleDescriptor("monitoring_and_operations", "Monitoring, backups and operations", listOf("engineering", "telemetry", "deployment"), "server-native", listOf("/api/v1/health", "/api/v1/ready", "/api/v1/ops/status", "/api/v1/ops/metrics", "/api/v1/ops/backups"), "Server health, audit, metrics, backup and restore-test controls are operated by the Ktor service."),
            QuantumModuleDescriptor("device_capabilities", "Android device integrations", listOf("voice", "notifications", "file-picker", "widgets"), "client-side-by-design", emptyList(), "Microphone permission, speech capture, notifications, local files, widgets and Android UI stay in Vyron X because they require device APIs."),
            QuantumModuleDescriptor("offline_model_runtime", "Offline and on-device model runtime", listOf("ai.offline", "alis.training.runtime"), "legacy-reference", emptyList(), "Not included in the central cloud runtime. Retain the source until an explicit supported offline-client design and model-distribution policy are approved.")
        ),
        note = "The old Quantum Android project is retained as a migration/reference source. Server-native and server-backed features are accessed through this Ktor API; client-side device functions stay in Vyron X. Partial-worker-required items are never reported as completed without a configured, tested worker."
    )

    get("/api/v1/categories") {
        call.response.headers.append("Cache-Control", "public, max-age=60", safeOnly = false)
        call.respond(catalog)
    }
    get("/api/v1/tools") {
        val categoryId = call.request.queryParameters["categoryId"]?.trim().orEmpty()
        val category = catalog.categories.firstOrNull { it.id == categoryId }
        if (category == null) {
            call.respond(mapOf("error" to "Unknown category", "categories" to catalog.categories.map { it.id }))
        } else {
            call.response.headers.append("Cache-Control", "public, max-age=60", safeOnly = false)
            call.respond(mapOf("categoryId" to category.id, "tools" to category.tools))
        }
    }
    get("/api/v1/quantum/modules") {
        call.response.headers.append("Cache-Control", "public, max-age=60", safeOnly = false)
        call.respond(modules)
    }
    get("/api/v1/quantum/migration/status") {
        val counts = modules.modules.groupingBy { it.status }.eachCount()
        call.response.headers.append("Cache-Control", "public, max-age=60", safeOnly = false)
        call.respond(mapOf("canonicalEngine" to modules.canonicalEngine, "primaryAndroidClient" to modules.primaryAndroidClient, "moduleCount" to modules.modules.size, "statusCounts" to counts, "complete" to modules.modules.all { it.status in setOf("server-native", "server-backed", "client-side-by-design") }, "note" to modules.note))
    }
}
