package com.example.offlinetranscription

import com.example.offlinetranscription.audio.WavDecoder
import com.example.offlinetranscription.model.GgufValidator
import com.example.offlinetranscription.model.installModel
import com.example.offlinetranscription.model.recoverModel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ModelAndWavTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun gguf(architecture: String = "asr", type: Int = 0, payloadBytes: Int = 128): File {
        val b = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN)
        fun text(s: String) { val bytes = s.toByteArray(); b.putLong(bytes.size.toLong()); b.put(bytes) }
        b.putInt(0x46554747); b.putInt(3); b.putLong(1); b.putLong(3)
        text("general.architecture"); b.putInt(8); text(architecture)
        text("asr.preprocessor.sample_rate"); b.putInt(4); b.putInt(16000)
        text("asr.encoder.cache_supported"); b.putInt(7); b.put(0)
        text("weights"); b.putInt(1); b.putLong(32); b.putInt(type); b.putLong(0)
        val dataStart = (b.position() + 31) / 32 * 32
        return temporary.newFile().apply { writeBytes(b.array().copyOf(dataStart + payloadBytes)) }
    }

    @Test fun modelMetadataAndTensorExtentAreValidatedBeforeNativeLoading() {
        val valid = gguf()
        assertEquals(16000, GgufValidator.validate(valid).sampleRate)
        assertFalse(GgufValidator.validate(valid).cacheStreaming)
        assertEquals(16000, GgufValidator.validate(gguf(type = 9, payloadBytes = 36)).sampleRate)
        valid.writeBytes(valid.readBytes().dropLast(1).toByteArray())
        assertThrows(IllegalArgumentException::class.java) { GgufValidator.validate(valid) }
        assertThrows(IllegalArgumentException::class.java) { GgufValidator.validate(gguf("llama")) }
    }

    @Test fun failedNativeActivationPreservesTheInstalledFile() = runTest {
        val old = temporary.newFile().apply { writeText("working model") }
        val candidate = temporary.newFile().apply { writeText("bad model") }
        try {
            installModel(candidate, old) { error("invalid native model") }
            fail("Activation should fail")
        } catch (_: IllegalStateException) { }
        assertEquals("working model", old.readText())
        assertTrue(candidate.exists())
    }

    @Test fun successfulActivationUsesThePermanentPathAndCommits() = runTest {
        val old = temporary.newFile().apply { writeText("old") }
        val candidate = temporary.newFile().apply { writeText("new") }
        installModel(candidate, old) { assertEquals(old, it); assertEquals("new", it.readText()) }
        assertEquals("new", old.readText())
        assertFalse(candidate.exists())
    }

    @Test fun interruptedReplacementRestoresTheLastCommittedModel() {
        val installed = temporary.newFile().apply { writeText("uncommitted") }
        File(installed.parentFile, installed.name + ".rollback").writeText("working")
        recoverModel(installed)
        assertEquals("working", installed.readText())
    }

    @Test fun interruptionBeforePromotionRemovesTheUnusedRollbackLink() {
        val installed = temporary.newFile().apply { writeText("working") }
        val backup = File(installed.parentFile, installed.name + ".rollback")
        java.nio.file.Files.createLink(backup.toPath(), installed.toPath())
        recoverModel(installed)
        assertFalse(backup.exists())
        assertEquals("working", installed.readText())
    }

    @Test fun incompleteBackupNeverReplacesTheWorkingModel() {
        val installed = temporary.newFile().apply { writeText("working") }
        val incomplete = File(installed.parentFile, installed.name + ".rollback.tmp").apply { writeText("partial") }
        recoverModel(installed)
        assertEquals("working", installed.readText())
        assertFalse(incomplete.exists())
    }

    private fun wav(rate: Int = 16000, oddPadding: Boolean = false): ByteArray {
        val chunks = ByteArrayOutputStream()
        fun chunk(name: String, data: ByteArray) {
            chunks.write(name.toByteArray(Charsets.US_ASCII))
            chunks.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(data.size).array())
            chunks.write(data)
            if (data.size % 2 != 0) chunks.write(0)
        }
        if (oddPadding) chunk("JUNK", byteArrayOf(1))
        chunk("fmt ", ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16).array())
        chunk("data", ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putShort(16384).putShort(-16384).array())
        val body = chunks.toByteArray()
        return ByteBuffer.allocate(12 + body.size).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt(body.size + 4).put("WAVE".toByteArray()).put(body).array()
    }

    @Test fun wavParserHandlesPaddedChunksAndRejectsUnsupportedOrTruncatedAudio() {
        assertArrayEquals(floatArrayOf(0.5f, -0.5f), WavDecoder.mono16k(wav(oddPadding = true).inputStream()), 0f)
        assertThrows(IllegalArgumentException::class.java) { WavDecoder.mono16k(wav(48000).inputStream()) }
        assertThrows(IllegalArgumentException::class.java) { WavDecoder.mono16k(wav().dropLast(1).toByteArray().inputStream()) }
        assertThrows(IllegalArgumentException::class.java) { WavDecoder.mono16k(ByteArray(48).inputStream()) }
    }
}
