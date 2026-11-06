package com.vibes.quantum.server.ai

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TheoryOfMindServiceTest {
    @Test fun explicitConsentAndReasonableBoundsAreRequired() {
        assertNotNull(validateTheoryOfMindRequest(TheoryOfMindRequest(text = "Hello", consentToAnalyze = false)))
        assertNotNull(validateTheoryOfMindRequest(TheoryOfMindRequest(text = " ", consentToAnalyze = true)))
        assertNotNull(validateTheoryOfMindRequest(TheoryOfMindRequest(text = "x".repeat(8_001), consentToAnalyze = true)))
        assertNotNull(validateTheoryOfMindRequest(TheoryOfMindRequest(text = "Hi", consentToAnalyze = true, maxCostUsd = Double.NaN)))
        assertNull(validateTheoryOfMindRequest(TheoryOfMindRequest(text = "I may need some time", consentToAnalyze = true)))
    }

    @Test fun serviceKeepsOnlyEvidenceQuotedFromSuppliedText() = runBlocking {
        val provider = AiProviderConfig(
            id = "local-test", baseUrl = "http://127.0.0.1:11434/v1", model = "test-model",
            apiKey = null, local = true, capabilities = setOf("chat")
        )
        val payload = """
            {
              "observedCues":["I may need some time","made-up phrase"],
              "possibleEmotions":[
                {"label":"uncertain","evidenceQuotes":["I may need some time"],"explanation":"Could be hesitation."},
                {"label":"angry","evidenceQuotes":["made-up phrase"],"explanation":"Unsupported."}
              ],
              "possibleIntentions":[{"label":"asking for space","evidenceQuotes":["I may need some time"],"explanation":"Possibly asking for time."}],
              "alternativeInterpretations":["They may simply be busy."],
              "uncertainties":["The surrounding conversation is unknown."],
              "clarifyingQuestion":"Would you like more time?",
              "respectfulReplySuggestion":"No rush; let me know when you are ready."
            }
        """.trimIndent()
        val router = ProviderRouter(listOf(provider), ProviderAdapter { _, _, _, _ -> ProviderPayload(payload) })
        val result = TheoryOfMindService(router).analyze(
            TheoryOfMindRequest(text = "I may need some time", consentToAnalyze = true)
        )
        assertNotNull(result)
        assertEquals(1, result.possibleEmotions.size)
        assertEquals("uncertain", result.possibleEmotions.single().label)
        assertEquals(listOf("I may need some time"), result.observedCues)
        assertTrue(result.note.contains("tentative"))
    }

    @Test fun cloudProviderIsNotCalledWithoutSeparateCloudOptIn() = runBlocking {
        var calls = 0
        val cloudProvider = AiProviderConfig(
            id = "cloud-test", baseUrl = "https://provider.example.test/v1", model = "test-model",
            apiKey = "test-only-key", local = false, capabilities = setOf("chat"),
            inputCostPer1kUsd = 0.001, outputCostPer1kUsd = 0.002
        )
        val router = ProviderRouter(listOf(cloudProvider), ProviderAdapter { _, _, _, _ ->
            calls++
            ProviderPayload("{}")
        })
        val result = TheoryOfMindService(router).analyze(
            TheoryOfMindRequest(text = "I need time", consentToAnalyze = true, allowCloudProcessing = false)
        )
        assertNull(result)
        assertEquals(0, calls)
    }

    @Test fun cloudProcessingMustBeExplicitAndLocalProviderRemainsEligible() = runBlocking {
        var calls = 0
        val provider = AiProviderConfig(
            id = "local-test", baseUrl = "http://127.0.0.1:11434/v1", model = "test-model",
            apiKey = null, local = true, capabilities = setOf("chat")
        )
        val router = ProviderRouter(listOf(provider), ProviderAdapter { _, _, _, _ ->
            calls++
            ProviderPayload("""{"observedCues":[],"possibleEmotions":[],"possibleIntentions":[],"alternativeInterpretations":[],"uncertainties":["Not enough wording to interpret."]}""")
        })
        val result = TheoryOfMindService(router).analyze(
            TheoryOfMindRequest(text = "Okay", consentToAnalyze = true, allowCloudProcessing = false)
        )
        assertNotNull(result)
        assertEquals(1, calls)
    }
}
