package com.vibes.quantum.server.curriculum

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID
import java.security.MessageDigest

/** Small bounded JSON store; practice examples and evidence claims are kept distinct. */
class CurriculumStore(fileName: String? = "data/vibes-curriculum.json", private val clock: () -> String = { Instant.now().toString() }, private val database: com.vibes.quantum.server.VibesDatabase? = null) {
    private val path: Path? = fileName?.trim()?.takeIf { it.isNotEmpty() }?.let { Paths.get(it) }
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = false }
    private var curricula = mutableListOf<Curriculum>()
    private var claims = mutableListOf<ApprovedLearningClaim>()
    private var questions = mutableListOf<SyntheticQuestion>()

    init { load() }

    @Synchronized fun snapshot(): CurriculumSnapshot = CurriculumSnapshot(
        curricula = curricula.sortedWith(compareBy<Curriculum> { it.sortOrder }.thenBy { it.createdAt }), approvedClaims = claims.count { it.status == "APPROVED" },
        questions = questions.toList().asReversed().take(500), candidateQuestions = questions.count { it.status == "CANDIDATE" || it.status == "NEEDS_LANGUAGE_REVIEW" },
        reviewedExamplesByDomain = questions.filter { it.status == "ACCEPTED" }.groupingBy { it.domain }.eachCount()
    )
    @Synchronized fun findCurriculum(id: String): Curriculum? = curricula.firstOrNull { it.id == id }
    @Synchronized fun findClaim(id: String): ApprovedLearningClaim? = claims.firstOrNull { it.id == id && it.status == "APPROVED" }
    @Synchronized fun allApprovedClaims(): List<ApprovedLearningClaim> = claims.filter { it.status == "APPROVED" }.toList()
    @Synchronized fun findQuestion(id: String): SyntheticQuestion? = questions.firstOrNull { it.id == id }

    @Synchronized fun addCurriculum(input: CurriculumSubmission): Curriculum {
        validateText(input.title, "title", 2, 120); validateText(input.domain, "domain", 2, 80)
        require(input.objectives.isNotEmpty() && input.objectives.size <= 30) { "Provide 1–30 learning objectives" }
        require(input.objectives.all { it.trim().length in 3..300 }) { "Each objective must be 3–300 characters" }
        require(input.difficulty in DIFFICULTIES) { "difficulty must be one of ${DIFFICULTIES.joinToString()}" }
        require(input.prerequisiteIds.size <= 20 && input.prerequisiteIds.all { id -> curricula.any { it.id == id } }) { "Every prerequisite must reference an existing curriculum" }
        val now = clock()
        val item = Curriculum(UUID.randomUUID().toString(), input.title.trim(), input.domain.trim(), input.objectives.map(String::trim), input.prerequisiteIds.distinct(), input.difficulty, input.enabled, input.sortOrder.coerceIn(-100_000, 100_000), now, now)
        val before = curricula
        curricula = (curricula + item).toMutableList()
        persistOrRollback { curricula = before }
        return item
    }

    @Synchronized fun updateCurriculum(id: String, input: CurriculumSubmission): Curriculum {
        val index = curricula.indexOfFirst { it.id == id }
        require(index >= 0) { "Curriculum not found" }
        validateText(input.title, "title", 2, 120); validateText(input.domain, "domain", 2, 80)
        require(input.objectives.isNotEmpty() && input.objectives.size <= 30 && input.objectives.all { it.trim().length in 3..300 }) { "Provide 1–30 objectives, each 3–300 characters" }
        require(input.difficulty in DIFFICULTIES) { "difficulty must be one of ${DIFFICULTIES.joinToString()}" }
        require(id !in input.prerequisiteIds && input.prerequisiteIds.size <= 20 && input.prerequisiteIds.all { prerequisite -> curricula.any { it.id == prerequisite } }) { "Prerequisites must reference existing curricula and cannot include this curriculum" }
        val proposedPrerequisites = curricula.associate { item -> item.id to if (item.id == id) input.prerequisiteIds else item.prerequisiteIds }
        fun reachesTarget(currentId: String, targetId: String, visited: MutableSet<String>): Boolean {
            if (currentId == targetId) return true
            if (!visited.add(currentId)) return false
            return proposedPrerequisites[currentId].orEmpty().any { reachesTarget(it, targetId, visited) }
        }
        require(input.prerequisiteIds.none { reachesTarget(it, id, mutableSetOf()) }) { "Prerequisites cannot create a curriculum dependency cycle" }
        val current = curricula[index]
        val updated = current.copy(title = input.title.trim(), domain = input.domain.trim(), objectives = input.objectives.map(String::trim), prerequisiteIds = input.prerequisiteIds.distinct(), difficulty = input.difficulty, enabled = input.enabled, sortOrder = input.sortOrder.coerceIn(-100_000, 100_000), updatedAt = clock())
        val before = curricula
        curricula = curricula.toMutableList().also { it[index] = updated }
        persistOrRollback { curricula = before }
        return updated
    }

    /**
     * Publish a Q&A item from the dedicated trainer app. The gateway key binds the caller to
     * vibes-quantum-engine; the server fixes the reviewer label and verifies the content hash,
     * source URL and review timestamp instead of trusting client-supplied approval metadata.
     */
    @Synchronized fun publishFromTrainerApp(
        appId: String,
        input: TrainerKnowledgeSubmission
    ): Pair<ApprovedLearningClaim, Boolean> {
        require(appId == TRAINER_APP_ID) { "Only the Vibes Quantum trainer app can publish shared knowledge" }
        require(input.sourceItemId.matches(Regex("[A-Za-z0-9._:-]{1,100}"))) { "Invalid source item ID" }
        require(input.contentHash.matches(Regex("[a-f0-9]{64}"))) { "Invalid content hash" }
        validateText(input.question, "question", 5, 180)
        validateText(input.answer, "answer", 20, 500)
        val suppliedHash = sha256(normalize(input.question) + "\u0001" + normalize(input.answer))
        require(MessageDigest.isEqual(suppliedHash.toByteArray(StandardCharsets.US_ASCII), input.contentHash.toByteArray(StandardCharsets.US_ASCII))) {
            "Content hash does not match the submitted question and answer"
        }
        require(input.sourceUrl.trim().length in 12..2_048) { "Source URL must be 12–2,048 characters" }
        val source = runCatching { java.net.URI(input.sourceUrl.trim()) }.getOrNull()
            ?: throw IllegalArgumentException("Source URL is invalid")
        val allowedHosts = setOf("en.wikipedia.org", "developer.android.com", "kotlinlang.org", "docs.python.org", "developer.mozilla.org", "docs.oracle.com")
        val host = source.host?.lowercase()
        require(source.scheme.equals("https", true) && host != null && allowedHosts.any { host == it || host.endsWith(".$it") } &&
            source.userInfo == null && source.fragment == null && (source.port == -1 || source.port == 443)) {
            "Source URL must use an approved HTTPS documentation host"
        }
        val sourceReviewedAt = runCatching { Instant.parse(input.reviewedAt) }.getOrNull()
            ?: throw IllegalArgumentException("Review timestamp must be ISO-8601")
        require(!sourceReviewedAt.isAfter(Instant.now().plusSeconds(300))) { "Review timestamp cannot be in the future" }

        val existingIndex = claims.indexOfLast { it.sourceAppId == appId && it.sourceItemId == input.sourceItemId }
        val existing = claims.getOrNull(existingIndex)
        if (existing != null && existing.status == "APPROVED" && existing.sourceContentHash == input.contentHash) {
            return existing to false
        }
        val item = ApprovedLearningClaim(
            id = "quantum_" + UUID.randomUUID().toString().replace("-", "").take(24),
            subject = input.question.trim(),
            relation = "is answered by",
            objectValue = input.answer.trim(),
            domain = "documentation",
            language = "en",
            scope = "Published from the Vibes Quantum trainer after explicit local approval",
            qualifiers = emptyList(),
            exceptions = emptyList(),
            evidenceRefs = listOf(input.sourceUrl.trim()),
            reviewedBy = "Vibes Quantum local approval (authenticated app assertion)",
            reviewedAt = clock(),
            status = "APPROVED",
            sourceAppId = appId,
            sourceItemId = input.sourceItemId,
            sourceContentHash = input.contentHash,
            sourceReviewedAt = sourceReviewedAt.toString()
        )
        val before = claims
        claims = claims.toMutableList().also { next ->
            // Preserve the previous approved version for audit instead of overwriting it.
            if (existingIndex >= 0 && existing != null && existing.status == "APPROVED") {
                next[existingIndex] = existing.copy(status = "SUPERSEDED")
            }
            next += item
        }
        persistOrRollback { claims = before }
        return item to true
    }

    /** Withdraws one trainer-origin record while retaining its audit history on the server. */
    @Synchronized fun withdrawFromTrainerApp(appId: String, sourceItemId: String): Boolean =
        sourceItemId in withdrawFromTrainerApp(appId, listOf(sourceItemId))

    /** Batch withdrawal writes the store once, even when many records are withdrawn together. */
    @Synchronized fun withdrawFromTrainerApp(appId: String, sourceItemIds: Collection<String>): Set<String> {
        require(appId == TRAINER_APP_ID) { "Only the Vibes Quantum trainer app can withdraw its published knowledge" }
        val requested = sourceItemIds.toSet()
        if (requested.isEmpty()) return emptySet()
        val before = claims
        val withdrawn = linkedSetOf<String>()
        val next = claims.toMutableList()
        next.indices.forEach { index ->
            val current = next[index]
            val sourceId = current.sourceItemId
            if (current.sourceAppId == appId && sourceId != null && sourceId in requested && current.status == "APPROVED") {
                next[index] = current.copy(status = "REVOKED")
                withdrawn += sourceId
            }
        }
        if (withdrawn.isEmpty()) return emptySet()
        claims = next
        persistOrRollback { claims = before }
        return withdrawn
    }

    private fun normalize(value: String): String = value.lowercase(java.util.Locale.ROOT).replace(Regex("\\s+"), " ").trim()
    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }

    @Synchronized fun addApprovedClaim(input: ClaimSubmission): ApprovedLearningClaim {
        validateText(input.subject, "subject", 1, 180); validateText(input.relation, "relation", 2, 80)
        validateText(input.objectValue, "objectValue", 1, 500); validateText(input.domain, "domain", 2, 80)
        validateText(input.reviewedBy, "reviewedBy", 2, 120)
        require(input.language in LANGUAGES) { "language must be en, sn (Shona), or nd (Ndebele)" }
        require(input.evidenceRefs.isNotEmpty() && input.evidenceRefs.size <= 20) { "At least one evidence reference is required" }
        require(input.evidenceRefs.all { ref ->
            ref.trim().length in 1..2048 && runCatching {
                val uri = java.net.URI(ref.trim())
                when (uri.scheme?.lowercase()) {
                    "https" -> !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null && (uri.port == -1 || uri.port == 443)
                    "urn" -> !uri.schemeSpecificPart.isNullOrBlank()
                    else -> false
                }
            }.getOrDefault(false)
        }) { "Evidence references must be HTTPS URLs or URNs" }
        require(input.qualifiers.size <= 20 && input.exceptions.size <= 20) { "Too many qualifiers or exceptions" }
        val normalized = listOf(input.subject, input.relation, input.objectValue, input.domain).joinToString("|").lowercase()
        require(claims.none { listOf(it.subject, it.relation, it.objectValue, it.domain).joinToString("|").lowercase() == normalized && it.status == "APPROVED" }) { "An equivalent approved claim already exists" }
        val item = ApprovedLearningClaim(UUID.randomUUID().toString(), input.subject.trim(), input.relation.trim(), input.objectValue.trim(), input.domain.trim(), input.language, input.scope.trim().take(300), input.qualifiers.map { it.trim().take(300) }, input.exceptions.map { it.trim().take(300) }, input.evidenceRefs.map(String::trim), input.reviewedBy.trim(), clock())
        val before = claims
        claims = (claims + item).toMutableList()
        persistOrRollback { claims = before }
        return item
    }

    @Synchronized fun saveGenerated(items: List<SyntheticQuestion>): List<SyntheticQuestion> {
        val existingKeys = questions.filter { it.status != "DISCARDED" }.map { questionKey(it) }.toMutableSet()
        val inserted = items.filter { existingKeys.add(questionKey(it)) }
        require(questions.size + inserted.size <= MAX_QUESTIONS) { "Synthetic question store is full; review or archive existing examples first" }
        if (inserted.isEmpty()) return emptyList()
        val before = questions
        questions = (questions + inserted).toMutableList()
        persistOrRollback { questions = before }
        return inserted
    }

    private fun questionKey(item: SyntheticQuestion) = listOf(item.curriculumId, item.sourceClaimId, item.questionType, item.requestedLanguage).joinToString("|")

    @Synchronized fun reviewQuestion(id: String, input: QuestionReviewRequest): SyntheticQuestion {
        val index = questions.indexOfFirst { it.id == id }
        require(index >= 0) { "Synthetic question not found" }
        val current = questions[index]
        require(current.status == "CANDIDATE" || current.status == "NEEDS_LANGUAGE_REVIEW") { "Only candidate questions can be reviewed" }
        val action = input.action.uppercase()
        require(action in setOf("ACCEPT", "EDIT", "DISCARD")) { "action must be ACCEPT, EDIT, or DISCARD" }
        val updated = when (action) {
            "ACCEPT" -> {
                require(!current.languageReviewRequired || input.trainerNote.trim().length >= 4) { "Add a note confirming human language review before accepting this translated question" }
                require(input.qualityRating != null && input.qualityRating in 1..5) { "A trainer quality rating from 1 to 5 is required when accepting a question" }
                current.copy(status = "ACCEPTED", languageReviewRequired = false, qualityRating = input.qualityRating, trainerNote = input.trainerNote.trim().take(1000))
            }
            "EDIT" -> {
                val question = input.question?.trim().orEmpty(); val answer = input.expectedAnswer?.trim().orEmpty()
                validateText(question, "question", 5, 700); validateText(answer, "expectedAnswer", 1, 2000)
                current.copy(question = question, expectedAnswer = answer, status = if (current.languageReviewRequired) "NEEDS_LANGUAGE_REVIEW" else "CANDIDATE", trainerNote = input.trainerNote.trim().take(1000))
            }
            else -> current.copy(status = "DISCARDED", trainerNote = input.trainerNote.trim().take(1000))
        }
        val before = questions
        questions = questions.toMutableList().also { it[index] = updated }
        persistOrRollback { questions = before }
        return updated
    }

    private fun load() {
        val rawFromDb = database?.readDocument("curriculum_store")
        val target = path
        val raw = rawFromDb ?: target?.takeIf { Files.exists(it) }?.let { Files.readString(it, StandardCharsets.UTF_8) }
        if (raw == null) {
            if (database != null) database.writeDocument("curriculum_store", "{\"schemaVersion\":$SCHEMA_VERSION,\"curricula\":[],\"claims\":[],\"questions\":[]}")
            return
        }
        try {
            require(raw.toByteArray(StandardCharsets.UTF_8).size <= MAX_STORE_BYTES) { "Curriculum store exceeds size limit" }
            val root = json.parseToJsonElement(raw).jsonObject
            val schema = root["schemaVersion"]?.jsonPrimitive?.intOrNull ?: 1
            require(schema == SCHEMA_VERSION) { "Unsupported curriculum schema version $schema" }
            curricula = json.decodeFromJsonElement(ListSerializer(Curriculum.serializer()), root.getValue("curricula")).toMutableList()
            claims = json.decodeFromJsonElement(ListSerializer(ApprovedLearningClaim.serializer()), root.getValue("claims")).toMutableList()
            questions = json.decodeFromJsonElement(ListSerializer(SyntheticQuestion.serializer()), root.getValue("questions")).toMutableList()
            require(curricula.size <= MAX_CURRICULA && claims.size <= MAX_CLAIMS && questions.size <= MAX_QUESTIONS) { "Curriculum store exceeds record limits" }
            require(questions.all { it.synthetic && !it.independentEvidence && it.split == "PRACTICE" }) { "Invalid synthetic/evaluation separation metadata" }
            if (rawFromDb == null && database != null) database.writeDocument("curriculum_store", raw)
        } catch (failure: Exception) { throw IllegalStateException("Could not load curriculum store; back up and repair the legacy file or database document before restarting.", failure) }
    }

    private fun persistOrRollback(rollback: () -> Unit) {
        try { persist() } catch (failure: Exception) { rollback(); throw IllegalStateException("Could not persist curriculum changes; no changes were committed", failure) }
    }
    private fun persist() {
        val content = """{"schemaVersion":$SCHEMA_VERSION,"curricula":${json.encodeToString(ListSerializer(Curriculum.serializer()), curricula)},"claims":${json.encodeToString(ListSerializer(ApprovedLearningClaim.serializer()), claims)},"questions":${json.encodeToString(ListSerializer(SyntheticQuestion.serializer()), questions)}}"""
        if (database != null) {
            database.writeDocument("curriculum_store", content)
            return
        }
        val target = path ?: return
        val parent = target.toAbsolutePath().parent
        Files.createDirectories(parent)
        val temporary = Files.createTempFile(parent, target.fileName.toString(), ".tmp")
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8)
            try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING) }
            runCatching { Files.setPosixFilePermissions(target, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")) }
        } finally { Files.deleteIfExists(temporary) }
    }
    private fun validateText(value: String, field: String, min: Int, max: Int) { require(value.trim().length in min..max) { "$field must be $min–$max characters" } }

    companion object {
        const val SCHEMA_VERSION = 1
        const val TRAINER_APP_ID = "vibes-quantum-engine"
        private const val MAX_STORE_BYTES = 32L * 1024 * 1024
        private const val MAX_CURRICULA = 1_000
        private const val MAX_CLAIMS = 100_000
        private const val MAX_QUESTIONS = 100_000
        val LANGUAGES = setOf("en", "sn", "nd")
        val DIFFICULTIES = setOf("FOUNDATION", "RELATIONSHIP", "EXCEPTION", "CAUSAL", "TEMPORAL", "SYNTHESIS")
    }
}
