package com.openmonitor.bridge

import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

object BaseusTokenProbe {
    private const val TARGET_PACKAGE = "com.baseus.security.ipc"

    fun probe(onProgress: (String) -> Unit = {}): List<String> {
        val commands = listOf(
            "su -c 'find /data/data/$TARGET_PACKAGE -maxdepth 3 \\( -path \"*/shared_prefs/*.xml\" -o -path \"*/files/*\" -o -path \"*/databases/*\" -o -path \"*/mmkv*\" -o -path \"*/thingmmkv*\" \\) -type f -exec sh -c \"p=\\\"\\$1\\\"; echo \\\"===== \$p =====\\\"; cat \\\"\$p\\\" 2>/dev/null; echo\" sh {} \\;'",
            "su -c 'grep -aR -n -E \"xm_token|xm_host|xm_host_code|xm_app_id|access_token|accessToken|auth\" /data/data/$TARGET_PACKAGE 2>/dev/null'",
            "run-as $TARGET_PACKAGE sh -c 'find . -maxdepth 4 \\( -path \"./shared_prefs/*.xml\" -o -path \"./files/*\" -o -path \"./databases/*\" -o -path \"./mmkv*\" -o -path \"./thingmmkv*\" \\) -type f -exec sh -c \"p=\\\"\\$1\\\"; echo \\\"===== \$p =====\\\"; cat \\\"\$p\\\" 2>/dev/null; echo\" sh {} \\;'",
            "run-as $TARGET_PACKAGE sh -c 'grep -aR -n -E \"xm_token|xm_host|xm_host_code|xm_app_id|access_token|accessToken|auth\" . 2>/dev/null'",
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
