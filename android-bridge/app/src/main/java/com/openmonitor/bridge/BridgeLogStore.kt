package com.openmonitor.bridge

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class BridgeLogEntry(
    val timestampMillis: Long,
    val level: String,
    val message: String,
) {
    fun formatLine(): String {
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(timestampMillis))
        return "[$timestamp][$level] $message"
    }
}

object BridgeLogStore {
    private const val MAX_ENTRIES = 200
    private val lock = Any()
    @Volatile
    private var entries = emptyList<BridgeLogEntry>()
    @Volatile
    private var logFile: File? = null

    fun initialize(cacheRoot: File) {
        synchronized(lock) {
            val file = File(cacheRoot, "bridge.log")
            logFile = file
            entries = if (file.exists()) {
                file.readLines()
                    .mapNotNull { parseLine(it) }
                    .takeLast(MAX_ENTRIES)
            } else {
                emptyList()
            }
        }
    }

    fun info(message: String) = append("INFO", message)

    fun warn(message: String) = append("WARN", message)

    fun error(message: String) = append("ERROR", message)

    fun snapshot(limit: Int = MAX_ENTRIES): List<BridgeLogEntry> {
        return synchronized(lock) { entries.takeLast(limit) }
    }

    fun exportEntries(): List<BridgeLogEntry> {
        return synchronized(lock) {
            val file = logFile
            if (file == null || !file.exists()) {
                entries
            } else {
                file.readLines()
                    .mapNotNull { parseLine(it) }
            }
        }
    }

    fun exportText(filter: LogQuery? = null): String {
        val source = exportEntries()
        val filtered = filter?.let { query -> filterEntries(source, query) } ?: source
        return filtered.joinToString("\n") { it.formatLine() }.ifBlank { "No logs yet." }
    }

    private fun append(level: String, message: String) {
        val entry = BridgeLogEntry(
            timestampMillis = System.currentTimeMillis(),
            level = level,
            message = message.trim().ifBlank { "(blank)" },
        )
        synchronized(lock) {
            entries = (entries + entry).takeLast(MAX_ENTRIES)
            logFile?.appendText(formatPersistedLine(entry) + "\n")
        }
    }

    private fun formatPersistedLine(entry: BridgeLogEntry): String {
        return buildString {
            append(entry.timestampMillis)
            append('\t')
            append(entry.level)
            append('\t')
            append(entry.message.replace("\t", " ").replace("\n", "\\n"))
        }
    }

    private fun parseLine(line: String): BridgeLogEntry? {
        val parts = line.split('\t', limit = 3)
        if (parts.size < 3) return null
        val timestamp = parts[0].toLongOrNull() ?: return null
        val level = parts[1]
        val message = parts[2].replace("\\n", "\n")
        return BridgeLogEntry(timestamp, level, message)
    }

    private fun filterEntries(entries: List<BridgeLogEntry>, query: LogQuery): List<BridgeLogEntry> {
        return entries.filter { entry -> query.matches(entry) }
    }
}
