package com.openmonitor.bridge

data class TokenHarvestEntry(
    val timestampMillis: Long,
    val source: String,
    val token: String,
    val note: String = "",
)

object TokenHarvestStore {
    private const val MAX_ENTRIES = 200
    private val lock = Any()
    @Volatile
    private var entries = emptyList<TokenHarvestEntry>()

    private val tokenKeyRegexes = listOf(
        Regex("""(?i)\b(access_token|accessToken|xm_token|auth|authorization|bearer)\b\s*[:=]\s*["']?([A-Za-z0-9._\-+/=]{8,})["']?"""),
        Regex("""(?i)\bBearer\s+([A-Za-z0-9._\-+/=]{16,})"""),
        Regex("""(?i)\b(xm_token|access_token|accessToken|auth)\b[^A-Za-z0-9._\-+/=]{0,12}([A-Za-z0-9._\-+/=]{8,})"""),
        Regex("""\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\b"""),
    )

    fun record(source: String, token: String, note: String = "") {
        val normalized = token.trim()
        if (normalized.isBlank()) return
        synchronized(lock) {
            val candidate = TokenHarvestEntry(
                timestampMillis = System.currentTimeMillis(),
                source = source,
                token = normalized,
                note = note,
            )
            if (entries.lastOrNull()?.token != normalized || entries.lastOrNull()?.source != source) {
                entries = (entries + candidate).takeLast(MAX_ENTRIES)
            }
        }
        BridgeLogStore.info(
            buildString {
                append("Token candidate captured from ")
                append(source)
                append(" => ")
                append(maskToken(normalized))
                if (note.isNotBlank()) {
                    append(" (")
                    append(note)
                    append(')')
                }
            }
        )
    }

    fun recordFromText(source: String, text: String, note: String = "") {
        if (text.isBlank()) return
        tokenKeyRegexes.forEach { regex ->
            regex.findAll(text).forEach { match ->
                val candidate = sequenceOf(
                    match.groupValues.getOrNull(2).orEmpty(),
                    match.groupValues.getOrNull(1).orEmpty(),
                    match.value,
                ).firstOrNull { it.isNotBlank() }.orEmpty()
                if (candidate.isNotBlank()) {
                    record(source, candidate, note.ifBlank { "pattern=${regex.pattern}" })
                }
            }
        }
    }

    fun snapshot(limit: Int = MAX_ENTRIES): List<TokenHarvestEntry> {
        return synchronized(lock) { entries.takeLast(limit) }
    }

    fun exportText(limit: Int = MAX_ENTRIES): String {
        return snapshot(limit).joinToString("\n") { entry ->
            buildString {
                append('[')
                append(entry.timestampMillis)
                append("] ")
                append(entry.source)
                if (entry.note.isNotBlank()) {
                    append(" (")
                    append(entry.note)
                    append(')')
                }
                append(": ")
                append(entry.token)
            }
        }.ifBlank { "No token candidates yet." }
    }

    fun summary(): String {
        val count = synchronized(lock) { entries.size }
        return if (count == 0) {
            "No token candidates yet."
        } else {
            "$count token candidate(s) collected"
        }
    }

    fun combinedSearchText(): String {
        return snapshot().joinToString("\n") { buildString { append(it.source).append(' ').append(it.token).append(' ').append(it.note) } }
    }

    private fun maskToken(token: String): String {
        if (token.length <= 10) return token
        return buildString {
            append(token.take(4))
            append("…")
            append(token.takeLast(4))
        }
    }
}
