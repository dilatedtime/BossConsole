package ai.rever.boss.services.passkey

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import java.io.File
import java.io.IOException
import java.nio.charset.Charset
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * Executes PowerShell scripts for Windows Hello authentication.
 * Similar to SwiftScriptExecutor but for Windows PowerShell.
 */
object PowerShellExecutor {
    private val logger = BossLogger.forComponent("PowerShellExecutor")

    const val SCRIPT_TIMEOUT_SECONDS = 30L
    const val PROBE_TIMEOUT_SECONDS = 5L

    internal var processRunner: (List<String>, File) -> Process = { command, outputFile ->
        ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(outputFile)
            .start()
    }

    internal var probeRunner: () -> Process = {
        ProcessBuilder("powershell", "-Command", "echo 'test'")
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
    }

    private val powerShellScriptsDir: String by lazy {
        findPowerShellScriptsDirectory()
    }

    /**
     * Check if PowerShell is available on this system.
     */
    fun isPowerShellAvailable(): Boolean {
        return try {
            val os = System.getProperty("os.name").lowercase()
            if (!os.contains("windows")) return false

            val process = probeRunner()
            val finished = process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (finished) {
                process.exitValue() == 0
            } else {
                process.destroyForcibly()
                logger.warn(LogCategory.PASSKEY, "PowerShell availability probe timed out")
                false
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            logger.debug(
                LogCategory.PASSKEY,
                "PowerShell availability check interrupted",
                mapOf("error" to e.toString()),
            )
            false
        } catch (e: Exception) {
            logger.debug(LogCategory.PASSKEY, "PowerShell not available", mapOf("error" to e.toString()))
            false
        }
    }

    /**
     * Execute a PowerShell script file with arguments.
     */
    fun executePowerShellScript(
        scriptName: String,
        vararg args: String,
    ): String {
        // Reject traversal before any filesystem or process work: Paths.get(dir,
        // "../x.ps1") resolves outside the script directory and would then be
        // executed with -ExecutionPolicy Bypass. Must precede the lazy
        // powerShellScriptsDir access, which can create directories.
        ScriptFileGuard.requireSimpleName(scriptName)

        return try {
            runScript(scriptName, args)
        } catch (e: Exception) {
            logger.warn(LogCategory.PASSKEY, "Error executing PowerShell script", error = e)
            throw e
        }
    }

    internal fun runScript(
        scriptName: String,
        args: Array<out String>,
        scriptsDir: String = powerShellScriptsDir,
        timeoutSeconds: Long = SCRIPT_TIMEOUT_SECONDS,
    ): String {
        val scriptPath = ScriptFileGuard.resolveInside(File(scriptsDir), scriptName).toPath()

        if (!Files.exists(scriptPath)) {
            throw IOException("PowerShell script not found: $scriptPath")
        }

        val command = mutableListOf("powershell", "-ExecutionPolicy", "Bypass", "-File", scriptPath.toString())
        command.addAll(args)

        logger.debug(LogCategory.PASSKEY, "Executing PowerShell script", mapOf("script" to scriptName))

        return executeCommand(command, scriptName, timeoutSeconds)
    }

    internal fun executeCommand(
        command: List<String>,
        operationDescription: String,
        timeoutSeconds: Long = SCRIPT_TIMEOUT_SECONDS,
    ): String {
        val outputFile =
            File.createTempFile("boss-ps-output", ".txt").apply {
                deleteOnExit()
            }

        val process =
            try {
                processRunner(command, outputFile)
            } catch (e: Exception) {
                outputFile.delete()
                throw e
            }

        return waitForProcessAndReadOutput(process, outputFile, operationDescription, timeoutSeconds)
    }

    private fun waitForProcessAndReadOutput(
        process: Process,
        outputFile: File,
        operationDescription: String,
        timeoutSeconds: Long,
    ): String =
        try {
            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                val partialOutput = readTextSafely(outputFile)
                throw IOException(
                    "PowerShell script timed out after ${timeoutSeconds}s: " +
                        "$operationDescription. Output: $partialOutput",
                )
            }

            val exitCode = process.exitValue()
            val output = readTextSafely(outputFile)

            if (exitCode != 0) {
                error("PowerShell script failed with exit code: $exitCode, output: $output")
            }

            output
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            process.destroyForcibly()
            throw IOException("PowerShell script execution interrupted: $operationDescription", e)
        } finally {
            outputFile.delete()
        }

    private fun consoleCharset(): Charset =
        try {
            System.getProperty("native.encoding")?.let { Charset.forName(it) } ?: Charset.defaultCharset()
        } catch (e: IllegalArgumentException) {
            logger.debug(
                LogCategory.PASSKEY,
                "Unknown native encoding, using default charset",
                mapOf("error" to e.toString()),
            )
            Charset.defaultCharset()
        }

    private fun readTextSafely(file: File): String =
        try {
            file.readText(consoleCharset()).trim()
        } catch (e: IOException) {
            logger.debug(
                LogCategory.PASSKEY,
                "Failed to read process output file",
                mapOf("error" to e.toString()),
            )
            ""
        } catch (e: SecurityException) {
            logger.debug(
                LogCategory.PASSKEY,
                "Security exception reading process output file",
                mapOf("error" to e.toString()),
            )
            ""
        }

    /**
     * Find the PowerShell scripts directory in the project.
     */
    private fun findPowerShellScriptsDirectory(): String {
        val projectDir = System.getProperty("user.dir")
        logger.debug(LogCategory.PASSKEY, "Project dir", mapOf("path" to projectDir))

        val possiblePaths =
            listOf(
                "$projectDir/composeApp/src/desktopMain/kotlin/ai/rever/boss/services/passkey/powershell",
                "$projectDir/src/desktopMain/kotlin/ai/rever/boss/services/passkey/powershell",
            )

        for (path in possiblePaths) {
            logger.debug(LogCategory.PASSKEY, "Checking path", mapOf("path" to path))
            val dir = File(path)
            if (dir.exists() && dir.isDirectory) {
                logger.debug(LogCategory.PASSKEY, "Found PowerShell directory", mapOf("path" to path))
                return path
            }
        }

        val defaultPath = possiblePaths.first()
        val dir = File(defaultPath)
        if (dir.mkdirs() || dir.isDirectory) {
            logger.debug(LogCategory.PASSKEY, "Created PowerShell directory", mapOf("path" to defaultPath))
            return defaultPath
        } else {
            error("Could not find or create PowerShell scripts directory: $defaultPath")
        }
    }
}
