package net.lockney.tabula.ha

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue

/**
 * `uv run python -m mockdevice serve`, started from the repo checkout: the
 * in-process Python test server the socket-level JVM tests drive (P2.1's
 * pattern; shared by [PythonServerTest] and [EntityDataServerTest]).
 */
internal class TestServerProcess(root: File) {
    val process: Process = ProcessBuilder("uv", "run", "python", "-m", "mockdevice", "serve")
        .directory(root)
        .apply {
            environment()["UV_CONFIG_FILE"] = File(root, "tools/uv.toml").absolutePath
            // mirror the Makefile's isolation (docs/DEVELOPMENT.md)
            environment().remove("UV_EXCLUDE_NEWER")
            environment().remove("UV_RESOLUTION")
            environment().remove("UV_PRERELEASE")
            environment().remove("UV_FORK_STRATEGY")
        }
        .start()

    var url: String = ""
        private set

    fun awaitListening(timeoutMs: Long = 30_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        val lines = sequence {
            while (true) yield(process.inputStream.bufferedReader().readLine() ?: return@sequence)
        }
        for (line in lines) {
            assertTrue(System.currentTimeMillis() < deadline, "test server did not start; last: $line")
            if (line.startsWith("LISTENING ")) {
                url = line.substringAfter("LISTENING ")
                return
            }
        }
        error("test server output ended before LISTENING")
    }

    fun close() {
        process.destroy()
        process.waitFor(5, TimeUnit.SECONDS)
        if (process.isAlive) process.destroyForcibly()
    }
}

/** The repo checkout above the working directory, or null (the socket tests then skip). */
internal fun findRepoRoot(): File? {
    var dir: File? = File(".").absoluteFile.parentFile ?: File(".")
    while (dir != null) {
        if (File(dir, "pyproject.toml").isFile && File(dir, "mockdevice").isDirectory) return dir
        dir = dir.parentFile
    }
    return null
}

internal fun uvAvailable(): Boolean =
    runCatching { ProcessBuilder("uv", "--version").start().waitFor(5, TimeUnit.SECONDS) }.getOrDefault(false)
