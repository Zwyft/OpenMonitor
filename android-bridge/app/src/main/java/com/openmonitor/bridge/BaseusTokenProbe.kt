package com.openmonitor.bridge

import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

object BaseusTokenProbe {
    private const val TARGET_PACKAGE = "com.baseus.security.ipc"

    fun probe(onProgress: (String) -> Unit = {}): List<String> {
        val commands = listOf(
            "su -c 'cat /data/data/$TARGET_PACKAGE/shared_prefs/xmapi.xml'",
            "su -c 'find /data/data/$TARGET_PACKAGE/shared_prefs -maxdepth 1 -name \"*.xml\" -exec cat {} \\;'",
            "run-as $TARGET_PACKAGE sh -c 'cat shared_prefs/xmapi.xml'",
            "run-as $TARGET_PACKAGE sh -c 'find shared_prefs -maxdepth 1 -name \"*.xml\" -exec cat {} \\;'",
        )
        for (command in commands) {
            try {
                onProgress("Probing Baseus prefs: $command")
                val output = runShell(command)
                if (output.isNotBlank()) {
                    TokenHarvestStore.recordFromText("Baseus prefs", output, note = command)
                }
            } catch (exception: Exception) {
                BridgeLogStore.warn("Baseus prefs probe failed for `$command`: ${exception.message ?: "unknown error"}")
            }
        }
        return TokenHarvestStore.snapshot()
            .map { it.token }
            .distinct()
    }

    private fun runShell(command: String): String {
        val process = ProcessBuilder("sh", "-c", command)
            .redirectErrorStream(true)
            .start()
        if (!process.waitFor(6, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw IllegalStateException("command timed out")
        }
        val output = BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
            buildString {
                while (true) {
                    val line = reader.readLine() ?: break
                    append(line)
                    append('\n')
                }
            }
        }
        if (process.exitValue() != 0 && output.isBlank()) {
            throw IllegalStateException("exit ${process.exitValue()}")
        }
        return output.trim()
    }
}
