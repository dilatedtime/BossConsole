package ai.rever.boss.services.passkey

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PowerShellExecutorTest {
    private val originalProcessRunner = PowerShellExecutor.processRunner
    private val originalProbeRunner = PowerShellExecutor.probeRunner

    @BeforeTest
    fun setUp() {
        PowerShellExecutor.processRunner = originalProcessRunner
        PowerShellExecutor.probeRunner = originalProbeRunner
    }

    @AfterTest
    fun tearDown() {
        PowerShellExecutor.processRunner = originalProcessRunner
        PowerShellExecutor.probeRunner = originalProbeRunner
    }

    @Test
    fun `executeCommand returns trimmed output when process exits successfully`() {
        val fakeProcess = FakeProcess(exitCode = 0)
        PowerShellExecutor.processRunner = { _, outputFile ->
            outputFile.writeText("  hello world  \n")
            fakeProcess
        }

        val result =
            PowerShellExecutor.executeCommand(
                command = listOf("powershell", "-Command", "echo 'hello'"),
                operationDescription = "test-success",
                timeoutSeconds = 5L,
            )

        assertEquals("hello world", result)
        assertFalse(fakeProcess.destroyedForcibly)
        assertTrue(fakeProcess.exitValueCalled)
    }

    @Test
    fun `executeCommand forcibly terminates process on timeout and does not call exitValue`() {
        val fakeProcess = FakeProcess(exitCode = 0, shouldTimeout = true)
        PowerShellExecutor.processRunner = { _, outputFile ->
            outputFile.writeText("partial progress before hang")
            fakeProcess
        }

        val error =
            assertFailsWith<IOException> {
                PowerShellExecutor.executeCommand(
                    command = listOf("powershell", "-Command", "Start-Sleep -Seconds 100"),
                    operationDescription = "test-timeout",
                    timeoutSeconds = 1L,
                )
            }

        assertTrue(fakeProcess.destroyedForcibly)
        assertFalse(fakeProcess.exitValueCalled)
        assertTrue(error.message?.contains("timed out after 1s: test-timeout") == true)
        assertTrue(error.message?.contains("partial progress before hang") == true)
    }

    @Test
    fun `executeCommand throws IllegalStateException on non-zero exit code`() {
        val fakeProcess = FakeProcess(exitCode = 1)
        PowerShellExecutor.processRunner = { _, outputFile ->
            outputFile.writeText("Access denied")
            fakeProcess
        }

        val error =
            assertFailsWith<IllegalStateException> {
                PowerShellExecutor.executeCommand(
                    command = listOf("powershell", "-Command", "exit 1"),
                    operationDescription = "test-fail",
                    timeoutSeconds = 5L,
                )
            }

        assertTrue(error.message?.contains("failed with exit code: 1") == true)
        assertTrue(error.message?.contains("Access denied") == true)
    }

    @Test
    fun `executeCommand restores interrupt status and destroys process on InterruptedException`() {
        val fakeProcess = FakeProcess(exitCode = 0, shouldInterrupt = true)
        PowerShellExecutor.processRunner = { _, _ -> fakeProcess }

        val error =
            assertFailsWith<IOException> {
                PowerShellExecutor.executeCommand(
                    command = listOf("powershell", "-Command", "dummy"),
                    operationDescription = "test-interrupt",
                    timeoutSeconds = 5L,
                )
            }

        assertTrue(fakeProcess.destroyedForcibly)
        assertTrue(Thread.interrupted()) // Verifies interrupt status was restored and clears it
        assertTrue(error.message?.contains("interrupted: test-interrupt") == true)
    }

    @Test
    fun `probeRunner timeout destroys probe process and returns false`() {
        val fakeProbe = FakeProcess(exitCode = 0, shouldTimeout = true)
        PowerShellExecutor.probeRunner = { fakeProbe }

        val isWindows = System.getProperty("os.name").lowercase().contains("windows")
        val available = PowerShellExecutor.isPowerShellAvailable()

        if (isWindows) {
            assertFalse(available)
            assertTrue(fakeProbe.destroyedForcibly)
        } else {
            assertFalse(available)
        }
    }

    @Test
    fun `runScript throws IOException when script file does not exist`() {
        val tempDir = Files.createTempDirectory("ps-test").toFile()
        try {
            val error =
                assertFailsWith<IOException> {
                    PowerShellExecutor.runScript(
                        scriptName = "non_existent.ps1",
                        args = emptyArray(),
                        scriptsDir = tempDir.absolutePath,
                    )
                }
            assertTrue(error.message?.contains("PowerShell script not found") == true)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `runScript executes script successfully when file exists`() {
        val tempDir = Files.createTempDirectory("ps-test-ok").toFile()
        File(tempDir, "sample.ps1").writeText("Write-Output 'OK'")
        val fakeProcess = FakeProcess(exitCode = 0)
        PowerShellExecutor.processRunner = { _, outputFile ->
            outputFile.writeText("OK")
            fakeProcess
        }

        try {
            val result =
                PowerShellExecutor.runScript(
                    scriptName = "sample.ps1",
                    args = arrayOf("-Verbose"),
                    scriptsDir = tempDir.absolutePath,
                )
            assertEquals("OK", result)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private class FakeProcess(
        private val exitCode: Int,
        private val shouldTimeout: Boolean = false,
        private val shouldInterrupt: Boolean = false,
    ) : Process() {
        var destroyedForcibly = false
        var exitValueCalled = false

        override fun waitFor(
            timeout: Long,
            unit: TimeUnit,
        ): Boolean {
            if (shouldInterrupt) {
                throw InterruptedException("Simulated interruption")
            }
            return !shouldTimeout
        }

        override fun waitFor(): Int {
            if (shouldInterrupt) {
                throw InterruptedException("Simulated interruption")
            }
            return exitCode
        }

        override fun exitValue(): Int {
            exitValueCalled = true
            if (shouldTimeout && !destroyedForcibly) {
                throw IllegalThreadStateException("Process has not finished")
            }
            return exitCode
        }

        override fun destroy() {
            destroyedForcibly = true
        }

        override fun destroyForcibly(): Process {
            destroyedForcibly = true
            return this
        }

        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()

        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))

        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
    }
}
