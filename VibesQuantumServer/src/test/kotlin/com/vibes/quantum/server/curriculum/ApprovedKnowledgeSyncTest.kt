package com.vibes.quantum.server.curriculum

import com.vibes.quantum.server.ai.ApprovedKnowledgeRetriever
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApprovedKnowledgeSyncTest {
    private fun hash(question: String, answer: String): String {
        val normalized = { value: String -> value.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim() }
        return MessageDigest.getInstance("SHA-256")
            .digest((normalized(question) + "\u0001" + normalized(answer)).toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun submission(id: String = "item-1", question: String = "What are Kotlin coroutines?", answer: String = "Kotlin coroutines support asynchronous programming and structured concurrency.") =
        TrainerKnowledgeSubmission(
            sourceItemId = id,
            contentHash = hash(question, answer),
            question = question,
            answer = answer,
            sourceUrl = "https://kotlinlang.org/docs/coroutines-overview.html",
            reviewedAt = "2026-10-30T08:00:00Z"
        )

    @Test fun publishIsIdempotentAndRetrievableOnlyWhileApproved() {
        val store = CurriculumStore(null) { "2026-10-30T09:00:00Z" }
        val input = submission()
        val first = store.publishFromTrainerApp(CurriculumStore.TRAINER_APP_ID, input)
        assertTrue(first.second)
        assertEquals("APPROVED", first.first.status)

        val duplicate = store.publishFromTrainerApp(CurriculumStore.TRAINER_APP_ID, input)
        assertFalse(duplicate.second)
        assertEquals(first.first.id, duplicate.first.id)

        val retriever = ApprovedKnowledgeRetriever(store)
        assertTrue(retriever.retrieve("Kotlin coroutines asynchronous programming").isNotEmpty())
        assertTrue(store.withdrawFromTrainerApp(CurriculumStore.TRAINER_APP_ID, input.sourceItemId))
        assertTrue(retriever.retrieve("Kotlin coroutines asynchronous programming").isEmpty())
        assertFalse(store.withdrawFromTrainerApp(CurriculumStore.TRAINER_APP_ID, input.sourceItemId))
    }

    @Test fun hashSourceAndAppIdentityAreValidated() {
        val store = CurriculumStore(null)
        val valid = submission()
        assertFailsWith<IllegalArgumentException> {
            store.publishFromTrainerApp("vibes-vyron-x", valid)
        }
        assertFailsWith<IllegalArgumentException> {
            store.publishFromTrainerApp(CurriculumStore.TRAINER_APP_ID, valid.copy(contentHash = "0".repeat(64)))
        }
        assertFailsWith<IllegalArgumentException> {
            store.publishFromTrainerApp(CurriculumStore.TRAINER_APP_ID, valid.copy(sourceUrl = "https://evil.example/guide"))
        }
        assertFailsWith<IllegalArgumentException> {
            store.publishFromTrainerApp(CurriculumStore.TRAINER_APP_ID, valid.copy(sourceUrl = "https://kotlinlang.org/" + "x".repeat(2_050)))
        }
        assertEquals(0, store.snapshot().approvedClaims)
    }

    @Test fun batchWithdrawalRevokesMultipleItemsInOneOperation() {
        val store = CurriculumStore(null)
        val first = submission(id = "item-batch-1")
        val second = submission(
            id = "item-batch-2",
            question = "What is structured concurrency?",
            answer = "Structured concurrency scopes related asynchronous tasks under a parent lifecycle."
        )
        store.publishFromTrainerApp(CurriculumStore.TRAINER_APP_ID, first)
        store.publishFromTrainerApp(CurriculumStore.TRAINER_APP_ID, second)
        val withdrawn = store.withdrawFromTrainerApp(
            CurriculumStore.TRAINER_APP_ID,
            listOf(first.sourceItemId, second.sourceItemId)
        )
        assertEquals(setOf(first.sourceItemId, second.sourceItemId), withdrawn)
        assertTrue(ApprovedKnowledgeRetriever(store).retrieve("Kotlin coroutines structured concurrency").isEmpty())
        assertEquals(0, store.snapshot().approvedClaims)
    }

    @Test fun changedContentReplacesExistingTrainerRecordAndWithdrawalCanBeReversed() {
        val store = CurriculumStore(null)
        val original = submission()
        val first = store.publishFromTrainerApp(CurriculumStore.TRAINER_APP_ID, original).first
        store.withdrawFromTrainerApp(CurriculumStore.TRAINER_APP_ID, original.sourceItemId)
        val revised = submission(question = "What do Kotlin coroutines support?", answer = "Kotlin coroutines support cancellable asynchronous work and structured concurrency.")
            .copy(sourceItemId = original.sourceItemId)
        val republished = store.publishFromTrainerApp(CurriculumStore.TRAINER_APP_ID, revised).first
        assertTrue(first.id != republished.id)
        assertFalse(store.allApprovedClaims().any { it.id == first.id })
        assertEquals(1, store.snapshot().approvedClaims)
        assertEquals("APPROVED", republished.status)
        assertEquals(revised.contentHash, republished.sourceContentHash)
    }
}
