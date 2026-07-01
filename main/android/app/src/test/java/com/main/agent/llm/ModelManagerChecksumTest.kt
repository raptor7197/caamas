package com.main.agent.llm

import android.content.Context
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class ModelManagerChecksumTest {

    private lateinit var tempFile: File
    private lateinit var modelManager: ModelManager

    @Before
    fun setUp() {
        tempFile = File.createTempFile("checksum-test", ".bin")
        tempFile.writeBytes("hello caamas".toByteArray())

        val context = mockk<Context>(relaxed = true)
        val engine = mockk<LlamaEngine>(relaxed = true)
        val capability = DeviceCapability.Info(
            tier = DeviceCapability.Tier.LOW,
            totalRamMb = 2048L,
            availRamMb = 1024L,
            cpuCores = 4,
            hasVulkan = false,
            recommendedCtx = 2048,
            recommendedThreads = 4,
            maxModelTier = DeviceCapability.ModelTier.SMALL,
        )
        modelManager = ModelManager(context, engine, capability)
    }

    @After
    fun tearDown() {
        tempFile.delete()
    }

    @Test
    fun `verifyChecksum returns true for matching SHA-256`() = runTest {
        val digest = MessageDigest.getInstance("SHA-256")
        val expected = digest.digest(tempFile.readBytes()).joinToString("") { "%02x".format(it) }

        val result = modelManager.verifyChecksum(tempFile, expected)

        assertTrue(result)
    }

    @Test
    fun `verifyChecksum returns false for mismatching SHA-256`() = runTest {
        val result = modelManager.verifyChecksum(tempFile, "deadbeefdeadbeefdeadbeefdeadbeef")

        assertFalse(result)
    }

    @Test
    fun `verifyChecksum returns true when expected hash is blank`() = runTest {
        val result = modelManager.verifyChecksum(tempFile, "")

        assertTrue(result)
    }
}
