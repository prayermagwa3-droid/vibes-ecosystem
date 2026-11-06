package com.vibes.quantum.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RealtimeVoiceProtocolTest {
    @Test fun liveTranscriptionConfigUsesCurrentRealtimeContract() {
        val config = Json.parseToJsonElement(buildLiveTranscriptionSessionUpdate("gpt-live-transcribe", "en-US")).jsonObject
        assertEquals("session.update", config["type"]?.jsonPrimitive?.content)
        val session = config["session"]!!.jsonObject
        assertEquals("transcription", session["type"]?.jsonPrimitive?.content)
        val audioInput = session["audio"]!!.jsonObject["input"]!!.jsonObject
        val format = audioInput["format"]!!.jsonObject
        assertEquals("audio/pcm", format["type"]?.jsonPrimitive?.content)
        assertEquals("24000", format["rate"]?.jsonPrimitive?.content)
        val transcription = audioInput["transcription"]!!.jsonObject
        assertEquals("gpt-live-transcribe", transcription["model"]?.jsonPrimitive?.content)
        assertEquals("low", transcription["delay"]?.jsonPrimitive?.content)
        assertEquals("en", transcription["languages"]!!.let { it as kotlinx.serialization.json.JsonArray }.first().jsonPrimitive.content)
    }

    @Test fun liveTranscriptionOnlyAllowsAudioBufferOperations() {
        val chunk = Base64.getEncoder().encodeToString(ByteArray(4_800))
        val validAppend = validateLiveTranscriptionClientEvent("""{"type":"input_audio_buffer.append","audio":"$chunk"}""")
        assertEquals(null, validAppend.error)
        assertEquals(4_800L, validAppend.audioBytes)
        assertEquals(null, validateLiveTranscriptionClientEvent("""{"type":"input_audio_buffer.commit"}""").error)
        assertEquals(null, validateLiveTranscriptionClientEvent("""{"type":"input_audio_buffer.clear"}""").error)
        assertEquals(null, validateLiveTranscriptionClientEvent("""{"type":"ping"}""").error)
        assertNotNull(validateLiveTranscriptionClientEvent("""{"type":"session.update","session":{"model":"attacker-model"}}""").error)
    }

    @Test fun liveTranscriptionRejectsBadAndOversizedPcmChunks() {
        assertNotNull(validateLiveTranscriptionClientEvent("not-json").error)
        assertNotNull(validateLiveTranscriptionClientEvent("""{"type":"input_audio_buffer.append","audio":"%%%"}""").error)
        val tooLarge = Base64.getEncoder().encodeToString(ByteArray(72 * 1024 + 2))
        assertNotNull(validateLiveTranscriptionClientEvent("""{"type":"input_audio_buffer.append","audio":"$tooLarge"}""").error)
        assertNotNull(validateLiveTranscriptionClientEvent("""{"type":"response.create"}""").error)
    }

    @Test fun liveTranscriptionConfigurationIgnoresInvalidLanguageHints() {
        val config = Json.parseToJsonElement(buildLiveTranscriptionSessionUpdate("gpt-live-transcribe", "not a locale!" )).jsonObject
        val input = config["session"]!!.jsonObject["audio"]!!.jsonObject["input"]!!.jsonObject
        assertEquals(null, input["transcription"]!!.jsonObject["languages"])
        assertEquals("null", input["turn_detection"]?.toString())
    }

    @Test fun realtimeTtsUsesOnlyTheReplacementModelAndCurrentAudioConfig() {
        assertTrue(isSupportedRealtimeTtsModel("gpt-realtime-2.1-mini"))
        assertFalse(isSupportedRealtimeTtsModel("gpt-4o-mini-tts"))
        assertFalse(isSupportedRealtimeTtsModel("tts-1"))
        val config = Json.parseToJsonElement(realtimeSpeechSessionUpdate("marin", "Speak naturally.")).jsonObject
        val session = config["session"]!!.jsonObject
        assertEquals("realtime", session["type"]?.jsonPrimitive?.content)
        assertEquals("audio", session["output_modalities"]!!.let { (it as kotlinx.serialization.json.JsonArray).first().jsonPrimitive.content })
        val output = session["audio"]!!.jsonObject["output"]!!.jsonObject
        assertEquals("marin", output["voice"]?.jsonPrimitive?.content)
        assertEquals("audio/pcm", output["format"]!!.jsonObject["type"]?.jsonPrimitive?.content)
    }

    @Test fun pcmAudioIsWrappedAsValidWav() {
        val pcm = byteArrayOf(1, 0, 2, 0, 3, 0, 4, 0)
        val wav = pcm16Mono24KhzToWav(pcm)
        assertEquals("RIFF", wav.copyOfRange(0, 4).toString(Charsets.US_ASCII))
        assertEquals("WAVE", wav.copyOfRange(8, 12).toString(Charsets.US_ASCII))
        assertEquals("fmt ", wav.copyOfRange(12, 16).toString(Charsets.US_ASCII))
        assertEquals("data", wav.copyOfRange(36, 40).toString(Charsets.US_ASCII))
        assertEquals(pcm.size + 44, wav.size)
        assertTrue(wav.copyOfRange(44, wav.size).contentEquals(pcm))
    }
}
