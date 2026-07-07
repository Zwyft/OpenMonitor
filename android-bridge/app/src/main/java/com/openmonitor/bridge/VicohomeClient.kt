package com.openmonitor.bridge

import org.json.JSONObject
import org.json.JSONArray
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID

private const val BASEUS_APP_VERSION_NAME = "1.2.4.0"
private const val BASEUS_APP_VERSION_CODE = "32"
private const val BASEUS_XM_APP_KEY = "YGnzWsirKf55MxrjG5"
private const val BASEUS_XM_APP_SECRET = "Teyi6OmXJZxVDiZY4k"
private const val BASEUS_XM_SERVICE_VERSION = "1.0"
private const val BASEUS_THING_SDK_VERSION = "6.2.0"
class VicohomeClient(
    private val email: String,
    private val password: String,
    private val regionChoice: VicohomeRegionChoice = VicohomeRegionChoice.AUTO,
) {
    private data class ResolvedVicohomeRegion(
        val region: VicohomeRegion,
        val serviceCatalogEntries: List<VicohomeServiceCatalog>,
    )

    private data class ActionServerHint(
        val name: String,
        val baseServer: String,
        val authRequired: Boolean,
        val raw: String,
    )

    fun syncRecentData(onProgress: (String) -> Unit): VicohomeSyncResult {
        var lastFailure: Exception? = null
        val regions = VicohomeRegionCatalog.choicesFor(regionChoice)
        for ((index, region) in regions.withIndex()) {
            try {
                onProgress("Logging into Baseus cloud (${region.label})")
                val accountLogin = loginBaseusAccount(region, onProgress)
                val privacyConsentUpdated = updatePrivacyConsent(accountLogin.authToken, region, onProgress)
                val resolved = resolveBaseusServiceRegion(accountLogin, region, onProgress)
                val resolvedRegion = resolved.region
                val serviceCatalogEntries = resolved.serviceCatalogEntries
                if (resolvedRegion != region) {
                    onProgress("Resolved Baseus service registry for ${region.label}")
                }
                val xmToken = try {
                    loginXmSession(accountLogin, resolvedRegion, serviceCatalogEntries, onProgress)
                } catch (exception: Exception) {
                    val fallbackToken = firstNonBlank(accountLogin.xmTokenHint, accountLogin.authToken)
                    if (fallbackToken.isNotBlank()) {
                        onProgress("Using Baseus token hint for device access after XM login failed: ${exception.message ?: "unknown error"}")
                        fallbackToken
                    } else {
                        throw exception
                    }
                }
                onProgress("Loading Baseus cloud devices (${resolvedRegion.label})")
                val devices = listDevices(
                    tokens = listOf(xmToken, accountLogin.xmTokenHint, accountLogin.authToken).filter { it.isNotBlank() }.distinct(),
                    region = resolvedRegion,
                    serviceCatalogEntries = serviceCatalogEntries,
                    onProgress = onProgress,
                )
                return VicohomeSyncResult(
                    devices = devices,
                    events = emptyList(),
                    message = buildString {
                        append("Loaded ")
                        append(devices.size)
                        append(" device(s) from Baseus ")
                        append(resolvedRegion.label)
                        append(if (privacyConsentUpdated) " with privacy consent updated" else " without privacy consent update")
                    },
                    session = VicohomeSession(
                        email = email,
                        accountAuthToken = accountLogin.authToken,
                        xmToken = xmToken,
                        region = resolvedRegion,
                        privacyConsentUpdated = privacyConsentUpdated,
                        serviceCatalogEntries = serviceCatalogEntries,
                    ),
                )
            } catch (exception: Exception) {
                lastFailure = exception
                val attemptLabel = "${region.label} attempt ${index + 1}/${regions.size}"
                onProgress("$attemptLabel failed: ${exception.message ?: "unknown error"}")
                if (regionChoice != VicohomeRegionChoice.AUTO || index == regions.lastIndex) {
                    throw exception
                }
            }
        }
        throw lastFailure ?: IllegalStateException("Vicohome sync failed")
    }

    fun fetchLiveTicket(
        session: VicohomeSession,
        serialNumber: String,
        onProgress: (String) -> Unit = {},
    ): VicohomeLiveTicket {
        val payload = JSONObject()
            .put("serialNumber", serialNumber)
            .put("countryNo", session.region.countryNo)
            .put("requestId", generateRequestID())
            .put("language", "en")
            .put("supportUnlimitedWebsocket", true)
            .put("list", org.json.JSONArray())
            .put(
                "app",
                JSONObject()
                    .put("versionName", BASEUS_APP_VERSION_NAME)
                    .put("bundle", "addx.ai.vicoo")
                    .put("timeZone", java.util.TimeZone.getDefault().id)
                    .put("appName", "VicoHome")
                    .put("tenantId", "vicoo")
                    .put("env", "prod-k8s")
                    .put("version", BASEUS_APP_VERSION_CODE.toInt())
                    .put("appType", "iOS"),
            )

        var lastFailure: Exception? = null
        val baseUrlCandidates = (
            session.region.webrtcApiBaseCandidates +
                session.region.apiBaseCandidates +
                session.serviceCatalogEntries.flatMap { listOf(it.bsServer, it.oauthServer, it.globalServer) }
        ).filter { it.isNotBlank() }.distinct()
        for (baseUrl in baseUrlCandidates) {
            try {
                onProgress("Trying Baseus live host $baseUrl")
                val response = postJson(
                    baseUrl,
                    "/device/getWebrtcTicket",
                    payload,
                    session.region,
                    session.xmToken.ifBlank { session.accountAuthToken },
                    extraHeaders = mapOf("Domain-Name" to "global_domain"),
                )
                val responseObject = JSONObject(response)
                val resultCode = responseObject.optInt("code", responseObject.optInt("result", -1))
                if (resultCode != 0) {
                    val message = responseObject.optString("msg", responseObject.optString("message", "unknown error"))
                    throw IllegalStateException("Live ticket request failed (${session.region.label} @ $baseUrl): $message")
                }
                val data = responseObject.optJSONObject("data") ?: throw IllegalStateException("Live ticket response missing data")
                val ticket = parseLiveTicket(data)
                return ticket.copy(
                    accessToken = ticket.accessToken.ifBlank { session.xmToken.ifBlank { session.accountAuthToken } },
                )
            } catch (exception: Exception) {
                lastFailure = exception
            }
        }
        throw lastFailure ?: IllegalStateException("Live ticket request failed (${session.region.label})")
    }

    fun probeThingRtc(
        session: VicohomeSession,
        targetSerialNumber: String,
        targetIp: String = "",
        targetName: String = "",
        clientDeviceId: String = "",
        onProgress: (String) -> Unit = {},
    ): ThingRtcProbeResult {
        val normalizedSerial = targetSerialNumber.trim()
        val normalizedIp = targetIp.trim()
        val normalizedName = targetName.trim()
        val targetIdentity = firstNonBlank(normalizedSerial, normalizedIp, normalizedName)
        val probeClientDeviceId = firstNonBlank(
            clientDeviceId,
            android.os.Build.FINGERPRINT,
            android.os.Build.MODEL,
            "android",
        )
        val tokenCandidates = listOf(
            session.xmToken,
            session.accountAuthToken,
            TokenHarvestStore.latestDecodedTokenFromSource("Baseus auth response"),
            TokenHarvestStore.latestTokenFromSource("Baseus auth response"),
            TokenHarvestStore.latestDecodedTokenFromSource("Baseus auth"),
            TokenHarvestStore.latestTokenFromSource("Baseus auth"),
        ).mapNotNull { it?.trim()?.takeIf { token -> token.isNotBlank() } }.distinct().take(3)
        val baseUrls = (
            session.region.webrtcApiBaseCandidates +
                session.region.apiBaseCandidates +
                session.serviceCatalogEntries.flatMap { listOf(it.bsServer, it.oauthServer, it.globalServer) }
        ).filter { it.isNotBlank() }.distinct().take(4)
        val bodyVariants = ThingProbeBodyVariant.entries
        val attempts = mutableListOf<ThingRtcProbeAttempt>()
        val bestFields = linkedMapOf<String, String>()
        var bestSummary = ""
        var latestSessionTid = ""
        var foundUsefulProbe = false

        if (tokenCandidates.isEmpty()) {
            onProgress("Thing RTC probe has no token candidates yet; using anonymous probe requests")
        }

        for (baseUrl in baseUrls) {
            if (foundUsefulProbe) break
            for (token in tokenCandidates.ifEmpty { listOf("") }) {
                if (foundUsefulProbe) break
                val tokenLabel = token.takeIf { it.isNotBlank() }?.let { "token" } ?: "anonymous"
                for (bodyVariant in bodyVariants) {
                    if (foundUsefulProbe) break
                    val abilityAttempt = performThingRtcProbeRequest(
                        baseUrl = baseUrl,
                        region = session.region,
                        apiName = "thing.m.ipc.device.ability.get",
                        sid = token,
                        devId = targetIdentity,
                        clientDeviceId = probeClientDeviceId,
                        ctId = "",
                        includeBizDm = false,
                        extraPostData = JSONObject().put("devId", targetIdentity),
                        targetIdentity = targetIdentity,
                        tokenLabel = tokenLabel,
                        bodyVariant = bodyVariant,
                        onProgress = onProgress,
                    )
                    attempts += abilityAttempt
                    mergeThingProbeFields(bestFields, abilityAttempt.parsedFields)
                    if (abilityAttempt.parsedSummary.isNotBlank()) {
                        bestSummary = chooseThingProbeSummary(bestSummary, abilityAttempt.parsedSummary)
                    }

                    val probeCtId = buildThingProbeClientTid(targetIdentity.ifBlank { targetName.ifBlank { targetIp } })
                    val sessionInitAttempt = performThingRtcProbeRequest(
                        baseUrl = baseUrl,
                        region = session.region,
                        apiName = "thing.m.rtc.session.init",
                        sid = token,
                        devId = targetIdentity,
                        clientDeviceId = probeClientDeviceId,
                        ctId = probeCtId,
                        includeBizDm = true,
                        extraPostData = JSONObject().put("devId", targetIdentity),
                        targetIdentity = targetIdentity,
                        tokenLabel = tokenLabel,
                        bodyVariant = bodyVariant,
                        onProgress = onProgress,
                    )
                    attempts += sessionInitAttempt
                    mergeThingProbeFields(bestFields, sessionInitAttempt.parsedFields)
                    val sessionTid = firstNonBlank(
                        sessionInitAttempt.parsedFields["data.sessionTid"],
                        sessionInitAttempt.parsedFields["sessionTid"],
                        sessionInitAttempt.parsedFields["data.session_tid"],
                        sessionInitAttempt.parsedFields["session_tid"],
                        sessionInitAttempt.parsedFields["data.session_id"],
                        sessionInitAttempt.parsedFields["session_id"],
                        sessionInitAttempt.parsedFields["data.sessionId"],
                        sessionInitAttempt.parsedFields["sessionId"],
                    )
                    if (sessionTid.isNotBlank()) {
                        latestSessionTid = sessionTid
                        bestFields["sessionTid"] = sessionTid
                        if (sessionInitAttempt.parsedSummary.isNotBlank()) {
                            bestSummary = chooseThingProbeSummary(bestSummary, sessionInitAttempt.parsedSummary)
                        }
                    }

                    val configCtId = firstNonBlank(
                        if (latestSessionTid.isNotBlank()) buildThingProbeClientTid(latestSessionTid) else "",
                        probeCtId,
                    )
                    val configAttempt = performThingRtcProbeRequest(
                        baseUrl = baseUrl,
                        region = session.region,
                        apiName = "thing.m.rtc.config.get",
                        sid = token,
                        devId = targetIdentity,
                        clientDeviceId = probeClientDeviceId,
                        ctId = configCtId,
                        includeBizDm = true,
                        extraPostData = JSONObject().put("devId", targetIdentity),
                        targetIdentity = targetIdentity,
                        tokenLabel = tokenLabel,
                        bodyVariant = bodyVariant,
                        onProgress = onProgress,
                    )
                    attempts += configAttempt
                    mergeThingProbeFields(bestFields, configAttempt.parsedFields)
                    if (configAttempt.parsedSummary.isNotBlank()) {
                        bestSummary = chooseThingProbeSummary(bestSummary, configAttempt.parsedSummary)
                    }

                    if (configAttempt.responseCode in 200..299 && configAttempt.parsedFields.isNotEmpty()) {
                        onProgress("Thing RTC probe found ${configAttempt.parsedFields.size} parsed field(s) on $baseUrl")
                    }
                    if (isUsefulThingProbeHit(configAttempt.parsedFields)) {
                        foundUsefulProbe = true
                        break
                    }
                }
            }
        }

        val message = when {
            latestSessionTid.isNotBlank() -> "Thing RTC probe captured sessionTid $latestSessionTid"
            bestFields.isNotEmpty() -> "Thing RTC probe captured ${bestFields.size} field(s)"
            attempts.isNotEmpty() -> attempts.last().parsedSummary.ifBlank { attempts.last().responseMessage.ifBlank { "Thing RTC probe complete" } }
            else -> "Thing RTC probe complete"
        }

        return ThingRtcProbeResult(
            targetSerialNumber = normalizedSerial,
            targetIp = normalizedIp,
            targetName = normalizedName,
            region = session.region,
            tokenSource = firstNonBlank(
                if (session.xmToken.isNotBlank()) "session.xmToken" else "",
                if (session.accountAuthToken.isNotBlank()) "session.accountAuthToken" else "",
                if (TokenHarvestStore.latestDecodedTokenFromSource("Baseus auth response").orEmpty().isNotBlank()) "Baseus auth response (decoded)" else "",
                if (TokenHarvestStore.latestTokenFromSource("Baseus auth response").orEmpty().isNotBlank()) "Baseus auth response" else "",
                if (TokenHarvestStore.latestDecodedTokenFromSource("Baseus auth").orEmpty().isNotBlank()) "Baseus auth (decoded)" else "",
                if (TokenHarvestStore.latestTokenFromSource("Baseus auth").orEmpty().isNotBlank()) "Baseus auth" else "",
            ),
            attempts = attempts,
            message = message,
            bestParsedSummary = bestSummary,
            bestParsedFields = bestFields,
        )
    }

    private fun performThingRtcProbeRequest(
        baseUrl: String,
        region: VicohomeRegion,
        apiName: String,
        sid: String,
        devId: String,
        clientDeviceId: String,
        ctId: String,
        includeBizDm: Boolean,
        extraPostData: JSONObject,
        targetIdentity: String,
        tokenLabel: String,
        bodyVariant: ThingProbeBodyVariant,
        onProgress: (String) -> Unit = {},
    ): ThingRtcProbeAttempt {
        val envelope = buildThingProbeEnvelope(
            apiName = apiName,
            region = region,
            sid = sid,
            devId = devId,
            clientDeviceId = clientDeviceId,
            ctId = ctId,
            includeBizDm = includeBizDm,
            extraPostData = extraPostData,
        )
        val requestUrl = buildThingApiUrl(baseUrl)
        val requestBody = when (bodyVariant) {
            ThingProbeBodyVariant.FORM -> buildThingFormBody(envelope)
            ThingProbeBodyVariant.JSON -> JSONObject().apply {
                envelope.forEach { (key, value) -> put(key, value) }
            }.toString()
        }
        val headers = linkedMapOf(
            "User-Agent" to buildThingUserAgent(),
            "Accept" to "application/json",
            "Domain-Name" to "host_domain",
            "Connection" to "keep-alive",
            "Content-Type" to when (bodyVariant) {
                ThingProbeBodyVariant.FORM -> "application/x-www-form-urlencoded; charset=utf-8"
                ThingProbeBodyVariant.JSON -> "application/json; charset=utf-8"
            },
        )
        val connection = (URL(requestUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 5000
            readTimeout = 7000
            doOutput = true
            setRequestProperty("User-Agent", headers["User-Agent"].orEmpty())
            setRequestProperty("Accept", headers["Accept"].orEmpty())
            setRequestProperty("Domain-Name", headers["Domain-Name"].orEmpty())
            setRequestProperty("Connection", headers["Connection"].orEmpty())
            setRequestProperty("Content-Type", headers["Content-Type"].orEmpty())
            if (requestBody.isNotBlank()) {
                setRequestProperty("Content-Length", requestBody.toByteArray(StandardCharsets.UTF_8).size.toString())
            }
        }

        var responseCode = -1
        var responseMessage = ""
        var responseBody = ""
        try {
            connection.outputStream.use { output ->
                output.write(requestBody.toByteArray(StandardCharsets.UTF_8))
            }
            responseCode = connection.responseCode
            responseMessage = connection.responseMessage.orEmpty()
            val responseStream = if (responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            responseBody = responseStream?.use { readAll(it) }.orEmpty()
            recordTokenArtifacts("Thing RTC $apiName @ $baseUrl (${bodyVariant.name.lowercase(Locale.US)})", connection.headerFields, responseBody)
            val (parsedSummary, parsedFields) = parseThingProbeResponse(responseBody)
            onProgress(
                "Thing RTC $apiName @ $baseUrl (${bodyVariant.name.lowercase(Locale.US)}) for ${targetIdentity.ifBlank { devId }} via $tokenLabel • HTTP $responseCode${if (responseMessage.isNotBlank()) " $responseMessage" else ""}${if (parsedSummary.isNotBlank()) " • $parsedSummary" else ""}"
            )
            return ThingRtcProbeAttempt(
                apiName = apiName,
                baseUrl = baseUrl,
                requestUrl = requestUrl,
                requestMethod = "POST/${bodyVariant.name.lowercase(Locale.US)}",
                requestEnvelope = envelope,
                requestHeaders = headers,
                responseCode = responseCode,
                responseMessage = responseMessage,
                responseBody = responseBody,
                parsedSummary = parsedSummary,
                parsedFields = parsedFields,
            )
        } catch (exception: Exception) {
            responseMessage = exception.message.orEmpty()
            responseBody = responseBody.ifBlank { exception.stackTraceToString() }
            recordTokenArtifacts("Thing RTC $apiName @ $baseUrl (${bodyVariant.name.lowercase(Locale.US)})", connection.headerFields, responseBody)
            val parsedFields = linkedMapOf<String, String>()
            parsedFields["error"] = responseMessage.ifBlank { exception.javaClass.simpleName }
            return ThingRtcProbeAttempt(
                apiName = apiName,
                baseUrl = baseUrl,
                requestUrl = requestUrl,
                requestMethod = "POST/${bodyVariant.name.lowercase(Locale.US)}",
                requestEnvelope = envelope,
                requestHeaders = headers,
                responseCode = responseCode,
                responseMessage = responseMessage.ifBlank { exception.javaClass.simpleName },
                responseBody = responseBody,
                parsedSummary = responseMessage.ifBlank { exception.javaClass.simpleName },
                parsedFields = parsedFields,
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun buildThingProbeEnvelope(
        apiName: String,
        region: VicohomeRegion,
        sid: String,
        devId: String,
        clientDeviceId: String,
        ctId: String,
        includeBizDm: Boolean,
        extraPostData: JSONObject,
    ): LinkedHashMap<String, String> {
        val envelope = linkedMapOf<String, String>()
        envelope["a"] = apiName
        envelope["v"] = "1.0"
        envelope["clientId"] = BASEUS_XM_APP_KEY
        envelope["os"] = "Android"
        envelope["appVersion"] = BASEUS_APP_VERSION_NAME
        envelope["lang"] = "en"
        envelope["sdkVersion"] = BASEUS_THING_SDK_VERSION
        envelope["deviceCoreVersion"] = BASEUS_THING_SDK_VERSION
        envelope["ttid"] = "Android"
        envelope["osSystem"] = android.os.Build.VERSION.RELEASE.orEmpty()
        envelope["requestId"] = buildThingProbeRequestId()
        envelope["platform"] = sanitizeHeaderValue(android.os.Build.MODEL)
        envelope["timeZoneId"] = java.util.TimeZone.getDefault().id
        envelope["et"] = "3"
        envelope["cp"] = "gzip"
        envelope["channel"] = "sdk"
        envelope["bizData"] = buildThingBizData(region)
        envelope["deviceId"] = clientDeviceId
        if (sid.isNotBlank()) {
            envelope["sid"] = sid
        }
        if (devId.isNotBlank()) {
            envelope["devId"] = devId
        }
        if (includeBizDm) {
            envelope["bizDM"] = "ipc"
        }
        if (ctId.isNotBlank()) {
            envelope["ctId"] = ctId
        }
        envelope["postData"] = extraPostData.toString()
        envelope["time"] = System.currentTimeMillis().toString()
        envelope["sign"] = buildThingProbeSignature(envelope)
        return envelope
    }

    private fun buildThingBizData(region: VicohomeRegion): String {
        return JSONObject()
            .put("customDomainSupport", "1")
            .put("sdkInt", android.os.Build.VERSION.SDK_INT.toString())
            .put("brand", sanitizeHeaderValue(android.os.Build.BRAND))
            .put("region", region.shortName)
            .toString()
    }

    private fun buildThingApiUrl(baseUrl: String): String {
        return if (baseUrl.endsWith("/api.json")) {
            baseUrl
        } else {
            baseUrl.trimEnd('/') + "/api.json"
        }
    }

    private fun buildThingProbeClientTid(reference: String): String {
        val cleanedReference = reference.trim().ifBlank { "thing" }
        return "${cleanedReference}_${System.currentTimeMillis()}"
    }

    private fun buildThingProbeRequestId(): String {
        return UUID.randomUUID().toString()
    }

    private fun buildThingUserAgent(): String {
        return "Thing-UA=APP/Android/$BASEUS_APP_VERSION_NAME/SDK/$BASEUS_THING_SDK_VERSION"
    }

    private fun buildThingFormBody(fields: Map<String, String>): String {
        return fields.entries.joinToString("&") { (key, value) ->
            "${java.net.URLEncoder.encode(key, StandardCharsets.UTF_8.name())}=${
                java.net.URLEncoder.encode(value, StandardCharsets.UTF_8.name())
            }"
        }
    }

    private fun buildThingProbeSignature(fields: Map<String, String>): String {
        val signableKeys = listOf(
            "a",
            "v",
            "clientId",
            "os",
            "appVersion",
            "lang",
            "sdkVersion",
            "deviceCoreVersion",
            "ttid",
            "osSystem",
            "requestId",
            "platform",
            "timeZoneId",
            "et",
            "cp",
            "channel",
            "bizData",
            "deviceId",
            "sid",
            "bizDM",
            "ctId",
            "devId",
            "time",
            "postData",
        )
        val canonical = signableKeys.mapNotNull { key ->
            val value = fields[key].orEmpty().trim()
            if (value.isBlank()) {
                null
            } else {
                val canonicalValue = if (key == "postData") {
                    MessageDigest.getInstance("MD5")
                        .digest(value.toByteArray(StandardCharsets.UTF_8))
                        .joinToString(separator = "") { byte ->
                            byte.toInt().and(0xff).toString(16).padStart(2, '0')
                        }
                } else {
                    value
                }
                "$key=$canonicalValue"
            }
        }.sorted().joinToString("&")
        val digest = MessageDigest.getInstance("MD5").digest(canonical.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString(separator = "") { byte ->
            byte.toInt().and(0xff).toString(16).padStart(2, '0')
        }
    }

    private fun parseThingProbeResponse(responseBody: String): Pair<String, Map<String, String>> {
        val trimmed = responseBody.trim()
        if (trimmed.isBlank()) {
            return "empty response" to emptyMap()
        }
        return try {
            val root = when {
                trimmed.startsWith("{") -> JSONObject(trimmed)
                trimmed.startsWith("[") -> JSONObject().put("items", JSONArray(trimmed))
                else -> JSONObject().put("body", trimmed)
            }
            val flattened = linkedMapOf<String, String>()
            flattenThingJson(root, "", flattened)
            val interesting = extractInterestingThingFields(flattened)
            val summaryParts = mutableListOf<String>()
            listOf(
                "code",
                "result",
                "msg",
                "message",
                "data.sessionTid",
                "sessionTid",
                "data.p2pConfig.session.sessionId",
                "data.p2pConfig.session.icePassword",
                "data.p2pConfig.session.iceUfrag",
                "data.p2pId",
                "data.password",
                "data.skill",
                "data.p2pPolicy",
                "data.p2pSpecifiedType",
                "data.audioAttributes",
                "data.vedioClarity",
                "data.maxZoomInTimes",
                "data.signalServer",
                "data.signalServerIpAddress",
                "data.websocketPath",
                "data.accessToken",
            ).forEach { key ->
                val value = interesting[key].orEmpty().trim()
                if (value.isNotBlank()) {
                    summaryParts += "$key=$value"
                }
            }
            if (summaryParts.isEmpty()) {
                summaryParts += interesting.entries.take(6).joinToString(", ") { (key, value) -> "$key=$value" }
            }
            val summary = summaryParts.joinToString(" • ").ifBlank { trimmed.take(240) }
            summary to interesting
        } catch (exception: Exception) {
            "non-json response: ${exception.message ?: "unknown error"}" to linkedMapOf(
                "body" to trimmed.take(1000),
            )
        }
    }

    private fun extractInterestingThingFields(flattened: Map<String, String>): Map<String, String> {
        val keys = listOf(
            "code",
            "result",
            "msg",
            "message",
            "data.sessionTid",
            "sessionTid",
            "data.session_tid",
            "session_tid",
            "data.sessionId",
            "sessionId",
            "data.session_id",
            "session_id",
            "data.p2pConfig",
            "data.p2pConfig.session",
            "data.p2pConfig.session.sessionId",
            "data.p2pConfig.session.icePassword",
            "data.p2pConfig.session.iceUfrag",
            "data.p2pConfig.session.aesKey",
            "data.p2pConfig.session.devId",
            "data.p2pConfig.ices",
            "data.p2pId",
            "data.password",
            "data.skill",
            "data.p2pPolicy",
            "data.p2pSpecifiedType",
            "data.audioAttributes",
            "data.vedioClarity",
            "data.maxZoomInTimes",
            "data.signalServer",
            "data.signalServerIpAddress",
            "data.websocketPath",
            "data.sign",
            "data.accessToken",
            "payload",
            "payload.sessionTid",
            "payload.p2pConfig",
            "payload.accessToken",
        )
        val selected = linkedMapOf<String, String>()
        keys.forEach { key ->
            val value = flattened[key].orEmpty().trim()
            if (value.isNotBlank()) {
                selected[key] = value.take(1000)
            }
        }
        if (selected.isEmpty()) {
            flattened.entries.take(32).forEach { (key, value) ->
                if (value.isNotBlank()) {
                    selected[key] = value.take(1000)
                }
            }
        }
        return selected
    }

    private fun flattenThingJson(
        value: Any?,
        prefix: String = "",
        out: MutableMap<String, String>,
    ) {
        when (value) {
            is JSONObject -> {
                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val childPrefix = if (prefix.isBlank()) key else "$prefix.$key"
                    flattenThingJson(value.opt(key), childPrefix, out)
                }
            }
            is JSONArray -> {
                for (index in 0 until value.length()) {
                    val childPrefix = if (prefix.isBlank()) index.toString() else "$prefix[$index]"
                    flattenThingJson(value.opt(index), childPrefix, out)
                }
            }
            null, JSONObject.NULL -> {
                if (prefix.isNotBlank()) {
                    out[prefix] = ""
                }
            }
            else -> {
                if (prefix.isNotBlank()) {
                    out[prefix] = value.toString().take(1000)
                }
            }
        }
    }

    private fun mergeThingProbeFields(target: MutableMap<String, String>, source: Map<String, String>) {
        source.forEach { (key, value) ->
            if (value.isNotBlank() && !target.containsKey(key)) {
                target[key] = value
            }
        }
    }

    private fun chooseThingProbeSummary(current: String, candidate: String): String {
        val currentScore = thingProbeSummaryScore(current)
        val candidateScore = thingProbeSummaryScore(candidate)
        return if (candidateScore >= currentScore) candidate else current
    }

    private fun isUsefulThingProbeHit(fields: Map<String, String>): Boolean {
        return fields.keys.any { key ->
            key.contains("sessionTid", ignoreCase = true) ||
                key.contains("p2pConfig", ignoreCase = true) ||
                key.contains("signalServer", ignoreCase = true) ||
                key.contains("password", ignoreCase = true) ||
                key.contains("p2pId", ignoreCase = true) ||
                key.contains("websocketPath", ignoreCase = true) ||
                key.contains("accessToken", ignoreCase = true) ||
                key.contains("traceId", ignoreCase = true) ||
                key.contains("groupId", ignoreCase = true)
        }
    }

    private fun thingProbeSummaryScore(summary: String): Int {
        val lower = summary.lowercase(Locale.US)
        return when {
            lower.contains("sessiontid") -> 5
            lower.contains("p2pconfig") -> 4
            lower.contains("signalserver") -> 3
            lower.contains("password") -> 2
            lower.isNotBlank() -> 1
            else -> 0
        }
    }

    private enum class ThingProbeBodyVariant {
        FORM,
        JSON,
    }

    private fun generateRequestID(): String {
        return "uuid:${UUID.randomUUID()}"
    }

    private fun loginBaseusAccount(
        region: VicohomeRegion,
        onProgress: (String) -> Unit = {},
    ): VicohomeAccountLogin {
        val payload = JSONObject()
            .put("type", 0)
            .put("account", email)
            .put("password", BaseusCrypto.encryptLoginPassword(password))

        var lastFailure: Exception? = null
        for (baseUrl in region.authBaseCandidates) {
            try {
                onProgress("Trying Baseus auth host $baseUrl")
                val response = postJson(
                    baseUrl = baseUrl,
                    path = "/api/auth/account/login",
                    payload = payload,
                    region = region,
                    queryParams = mapOf("countryCode" to region.loginCountryCode),
                    extraHeaders = mapOf("Domain-Name" to "Authorize_domain"),
                )
                val responseObject = JSONObject(response)
                val code = responseObject.optInt("code", responseObject.optInt("result", -1))
                if (code != 0) {
                    val message = responseObject.optString("msg", responseObject.optString("message", "unknown error"))
                    throw IllegalStateException("Login failed (${region.label} @ $baseUrl): $message")
                }
                val data = responseObject.optJSONObject("data") ?: throw IllegalStateException("Login failed (${region.label} @ $baseUrl): data missing")
                val accountInfoObject = data.optJSONObject("accountInfo") ?: throw IllegalStateException("Login failed (${region.label} @ $baseUrl): accountInfo missing")
                val auth = data.optString("auth").orEmpty()
                require(auth.isNotBlank()) { "Login failed (${region.label} @ $baseUrl): auth token missing" }
                val accountLogin = VicohomeAccountLogin(
                    accountInfo = VicohomeAccountInfo(
                        account = accountInfoObject.optString("account").ifBlank { email },
                        accountId = accountInfoObject.optLong("accountId"),
                        nickname = accountInfoObject.optString("nickname"),
                    ),
                    authToken = auth,
                    xmTokenHint = firstNonBlank(
                        data.optString("access_token").orEmpty(),
                        data.optString("accessToken").orEmpty(),
                        data.optString("xm_token").orEmpty(),
                        data.optJSONObject("payload")?.let { extractXmAccessToken(it) }.orEmpty(),
                        data.optJSONObject("data")?.let { extractXmAccessToken(it) }.orEmpty(),
                    ),
                    pwd = data.optString("pwd").orEmpty(),
                )
                TokenHarvestStore.record("Baseus auth", accountLogin.authToken, "account token")
                TokenHarvestStore.recordFromText("Baseus auth response", response)
                val responseTokenHint = firstNonBlank(
                    TokenHarvestStore.latestDecodedTokenFromSource("Baseus auth response"),
                    TokenHarvestStore.latestTokenFromSource("Baseus auth response"),
                )
                if (responseTokenHint.isNotBlank()) {
                    TokenHarvestStore.record("Baseus auth", responseTokenHint, "xm token hint")
                    return accountLogin.copy(xmTokenHint = responseTokenHint)
                }
                return accountLogin
            } catch (exception: Exception) {
                lastFailure = exception
            }
        }
        throw lastFailure ?: IllegalStateException("Login failed (${region.label})")
    }

    private fun resolveBaseusServiceRegion(
        accountLogin: VicohomeAccountLogin,
        region: VicohomeRegion,
        onProgress: (String) -> Unit = {},
    ): ResolvedVicohomeRegion {
        val candidateTokens = listOf(
            TokenHarvestStore.latestDecodedTokenFromSource("Baseus auth response"),
            TokenHarvestStore.latestTokenFromSource("Baseus auth response"),
            accountLogin.xmTokenHint,
            accountLogin.authToken,
        ).mapNotNull { it?.trim()?.takeIf { token -> token.isNotBlank() } }.distinct()
        if (candidateTokens.isEmpty()) {
            return ResolvedVicohomeRegion(region, emptyList())
        }

        for (baseUrl in (region.consentBaseCandidates + region.authBaseCandidates + region.apiBaseCandidates).distinct()) {
            for (token in candidateTokens) {
                try {
                    onProgress("Loading Baseus service registry from $baseUrl")
                    val response = getJson(
                        baseUrl = baseUrl,
                        path = "/api/app/homepage/global/dictByName",
                        region = region,
                        token = token,
                        queryParams = mapOf(
                            "dictName" to "global_constants",
                            "dictDetailNames" to "base_cloud_service_list",
                        ),
                        extraHeaders = mapOf("Domain-Name" to "global_domain"),
                    )
                    val responseObject = JSONObject(response)
                    val catalogResolution = extractServiceCatalogResolution(responseObject, region)
                    val catalog = catalogResolution.selected
                    if (catalog != null) {
                        TokenHarvestStore.recordFromText("Baseus service registry", response)
                        onProgress(
                            "Baseus service registry resolved: ${catalog.label} ${catalog.value} bs=${catalog.bsServer} auth=${catalog.oauthServer} global=${catalog.globalServer}"
                        )
                        return ResolvedVicohomeRegion(
                            region = region.copy(
                            apiBase = firstNonBlank(catalog.bsServer, region.apiBase),
                            apiBaseCandidates = listOf(catalog.bsServer, region.apiBaseCandidates.firstOrNull().orEmpty(), "https://api-${region.shortName.lowercase(Locale.US)}.vicohome.io")
                                .filter { it.isNotBlank() }
                                .distinct(),
                            authBaseCandidates = listOf(catalog.oauthServer, region.authBaseCandidates.firstOrNull().orEmpty())
                                .filter { it.isNotBlank() }
                                .distinct(),
                            consentBaseCandidates = listOf(catalog.oauthServer, catalog.bsServer, region.consentBaseCandidates.firstOrNull().orEmpty())
                                .filter { it.isNotBlank() }
                                .distinct(),
                            webrtcApiBase = firstNonBlank(catalog.globalServer, region.webrtcApiBase),
                            webrtcApiBaseCandidates = listOf(catalog.globalServer, region.webrtcApiBaseCandidates.firstOrNull().orEmpty(), "https://api-${region.shortName.lowercase(Locale.US)}.vicoo.tech")
                                .filter { it.isNotBlank() }
                                .distinct(),
                            serverCode = firstNonBlank(catalog.value, region.serverCode),
                        ),
                            serviceCatalogEntries = catalogResolution.entries,
                        )
                    }
                } catch (exception: Exception) {
                    onProgress("Baseus service registry lookup failed at $baseUrl: ${exception.message ?: "unknown error"}")
                }
            }
        }
        return ResolvedVicohomeRegion(region, emptyList())
    }

    private fun loginXmSession(
        accountLogin: VicohomeAccountLogin,
        region: VicohomeRegion,
        serviceCatalogEntries: List<VicohomeServiceCatalog> = emptyList(),
        onProgress: (String) -> Unit = {},
    ): String {
        val hostCandidates = (
            region.authBaseCandidates +
                region.apiBaseCandidates +
                serviceCatalogEntries.flatMap { listOf(it.bsServer, it.oauthServer, it.globalServer) }
        ).filter { it.isNotBlank() }.distinct()
        val bootstrapToken = firstNonBlank(
            TokenHarvestStore.latestDecodedTokenFromSource("Baseus auth"),
            accountLogin.xmTokenHint,
            accountLogin.authToken,
        )
        val loginVariants = listOf(
            XmLoginVariant(
                action = "UserLoginXn",
                payload = JSONObject()
                    .put("account", email)
                    .put("password", accountLogin.authToken)
                    .put("app_id", BASEUS_XM_APP_KEY)
                    .put("country_ode", region.shortName)
                    .put("grant_type", "password")
                    .put("scope", "base"),
            ),
            XmLoginVariant(
                action = "UserLoginXn",
                payload = JSONObject()
                    .put("account", accountLogin.accountInfo.accountId.toString())
                    .put("password", accountLogin.authToken)
                    .put("app_id", BASEUS_XM_APP_KEY)
                    .put("country_ode", region.shortName)
                    .put("grant_type", "password")
                    .put("scope", "base"),
            ),
            XmLoginVariant(
                action = "UserLogin",
                payload = JSONObject()
                    .put("user_id", email)
                    .put("password", accountLogin.authToken)
                    .put("region", region.shortName)
                    .put("type", "password"),
            ),
            XmLoginVariant(
                action = "UserLogin",
                payload = JSONObject()
                    .put("user_id", accountLogin.authToken)
                    .put("password", password)
                    .put("region", region.shortName)
                    .put("type", "password"),
            ),
            XmLoginVariant(
                action = "UserLogin",
                payload = JSONObject()
                    .put("user_id", accountLogin.accountInfo.accountId.toString())
                    .put("password", accountLogin.pwd.ifBlank { password })
                    .put("region", region.shortName)
                    .put("type", "password"),
            ),
        )
        var lastFailure: Exception? = null
        for (baseUrl in hostCandidates) {
            for ((variantIndex, variant) in loginVariants.withIndex()) {
                try {
                    onProgress("Trying Baseus XM session host $baseUrl (${variant.action} variant ${variantIndex + 1}/${loginVariants.size})")
                    val response = postXmAction(
                        baseUrl = baseUrl,
                        action = variant.action,
                        payload = variant.payload,
                        region = region,
                        token = bootstrapToken,
                        domainNameHeader = "host_domain",
                    )
                    val responseObject = JSONObject(response)
                    val resultCode = responseObject.optInt("code", responseObject.optInt("result", -1))
                    if (resultCode != 0) {
                        val message = responseObject.optString("msg", responseObject.optString("message", "unknown error"))
                        throw IllegalStateException("XM session login failed (${region.label} @ $baseUrl, variant ${variantIndex + 1}): $message")
                    }
                    val token = extractXmAccessToken(responseObject)
                    if (token.isNotBlank()) {
                        TokenHarvestStore.record("Baseus XM session", token, "XM token")
                        TokenHarvestStore.recordFromText("Baseus XM session response", response)
                        onProgress("Baseus XM session token acquired from $baseUrl")
                        return token
                    }
                    TokenHarvestStore.recordFromText("Baseus XM session response", response)
                    throw IllegalStateException("XM session login returned no access token: ${response.take(240)}")
                } catch (exception: Exception) {
                    lastFailure = exception
                }
            }
        }
        throw lastFailure ?: IllegalStateException("XM session login failed (${region.label})")
    }

    private fun listDevices(
        tokens: List<String>,
        region: VicohomeRegion,
        serviceCatalogEntries: List<VicohomeServiceCatalog> = emptyList(),
        onProgress: (String) -> Unit = {},
    ): List<VicohomeDevice> {
        val headerModes = listOf(TokenHeaderMode.BOTH, TokenHeaderMode.AUTH_ONLY, TokenHeaderMode.AUTHORIZATION_ONLY)
        var lastFailure: Exception? = null
        var sawSuccessfulResponse = false
        var lastEmptyShape: String? = null
        val baseUrlQueue = ArrayDeque<String>()
        val enqueuedBaseUrls = linkedSetOf<String>()
        fun enqueueBaseUrl(value: String?) {
            val normalized = value?.trim().orEmpty()
            if (normalized.isBlank() || !enqueuedBaseUrls.add(normalized)) {
                return
            }
            baseUrlQueue.addLast(normalized)
        }
        region.apiBaseCandidates.forEach { enqueueBaseUrl(it) }
        serviceCatalogEntries.forEach { catalog ->
            listOf(catalog.bsServer, catalog.oauthServer, catalog.globalServer).forEach { enqueueBaseUrl(it) }
        }

        while (baseUrlQueue.isNotEmpty()) {
            val baseUrl = baseUrlQueue.removeFirst()
            for (token in tokens) {
                for (headerMode in headerModes) {
                    try {
                        onProgress("Trying Baseus device host $baseUrl")
                        val response = getXmAction(
                            baseUrl = baseUrl,
                            action = "GetUserDeviceList",
                            region = region,
                            token = token,
                            headerMode = headerMode,
                            domainNameHeader = "host_domain",
                        )
                        val responseObject = JSONObject(response)
                        val resultCode = responseObject.optInt("code", responseObject.optInt("result", 0))
                        if (resultCode != 0) {
                            val message = responseObject.optString("msg", responseObject.optString("message", "unknown error"))
                            maybeEnqueueActionServerHosts(
                                response = response,
                                message = message,
                                baseUrlQueue = baseUrlQueue,
                                enqueuedBaseUrls = enqueuedBaseUrls,
                                serviceCatalogEntries = serviceCatalogEntries,
                                onProgress = onProgress,
                            )
                            throw IllegalStateException("Device list request failed (${region.label} @ $baseUrl): $message")
                        }

                        TokenHarvestStore.recordFromText("Baseus device list response", response)
                        sawSuccessfulResponse = true
                        val devices = parseDeviceList(responseObject)
                        if (devices.isNotEmpty()) {
                            onProgress("Baseus device host $baseUrl returned ${devices.size} device(s)")
                            return devices
                        }
                        lastEmptyShape = describeDeviceListShape(responseObject)
                        onProgress("Baseus device host $baseUrl returned no devices: $lastEmptyShape")
                    } catch (exception: Exception) {
                        lastFailure = exception
                        onProgress("Baseus device host $baseUrl failed: ${exception.message ?: "unknown error"}")
                    }
                    try {
                        onProgress("Trying Baseus device host $baseUrl via POST fallback")
                        val response = postXmAction(
                            baseUrl = baseUrl,
                            action = "GetUserDeviceList",
                            payload = JSONObject(),
                            region = region,
                            token = token,
                            headerMode = headerMode,
                            domainNameHeader = "host_domain",
                        )
                        val responseObject = JSONObject(response)
                        val resultCode = responseObject.optInt("code", responseObject.optInt("result", 0))
                        if (resultCode != 0) {
                            val message = responseObject.optString("msg", responseObject.optString("message", "unknown error"))
                            maybeEnqueueActionServerHosts(
                                response = response,
                                message = message,
                                baseUrlQueue = baseUrlQueue,
                                enqueuedBaseUrls = enqueuedBaseUrls,
                                serviceCatalogEntries = serviceCatalogEntries,
                                onProgress = onProgress,
                            )
                            throw IllegalStateException("Device list request failed (${region.label} @ $baseUrl): $message")
                        }

                        TokenHarvestStore.recordFromText("Baseus device list response", response)
                        sawSuccessfulResponse = true
                        val devices = parseDeviceList(responseObject)
                        if (devices.isNotEmpty()) {
                            onProgress("Baseus device host $baseUrl returned ${devices.size} device(s)")
                            return devices
                        }
                        lastEmptyShape = describeDeviceListShape(responseObject)
                        onProgress("Baseus device host $baseUrl returned no devices: $lastEmptyShape")
                    } catch (exception: Exception) {
                        lastFailure = exception
                        onProgress("Baseus device host $baseUrl failed: ${exception.message ?: "unknown error"}")
                    }
                }
            }
        }
        if (sawSuccessfulResponse) {
            if (!lastEmptyShape.isNullOrBlank()) {
                onProgress("Baseus cloud returned an empty device list: $lastEmptyShape")
            }
            return emptyList()
        }
        throw lastFailure ?: IllegalStateException("Device list request failed (${region.label})")
    }

    private fun postXmAction(
        baseUrl: String,
        action: String,
        payload: JSONObject,
        region: VicohomeRegion,
        token: String? = null,
        headerMode: TokenHeaderMode = TokenHeaderMode.BOTH,
        domainNameHeader: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
    ): String {
        val requestVariants = listOf(
            XmRequestVariant(
                contentType = "application/json; charset=utf-8",
                requestBody = if (payload.length() == 0) "{}" else payload.toString(),
                bodyLabel = "json",
            ),
            XmRequestVariant(
                contentType = "application/x-www-form-urlencoded; charset=utf-8",
                requestBody = buildXmFormBody(payload),
                bodyLabel = "form",
            ),
        )
        var lastFailure: Exception? = null
        for (variant in requestVariants) {
            val timestamp = System.currentTimeMillis()
            val connection = (URL(buildRequestUrl(baseUrl, "", emptyMap())).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5000
                readTimeout = 7000
                doOutput = true
                setRequestProperty("User-Agent", buildXmUserAgent())
                setRequestProperty("Timestamp", timestamp.toString())
                setRequestProperty("Version", BASEUS_XM_SERVICE_VERSION)
                setRequestProperty("SecretId", BASEUS_XM_APP_KEY)
                setRequestProperty("Signature", buildXmSignature(timestamp))
                setRequestProperty("RequestId", UUID.randomUUID().toString())
                setRequestProperty("Action", action)
                if (!token.isNullOrBlank()) {
                    when (headerMode) {
                        TokenHeaderMode.BOTH -> {
                            setRequestProperty("Authorization", token)
                            setRequestProperty("auth", token)
                        }
                        TokenHeaderMode.AUTH_ONLY -> setRequestProperty("auth", token)
                        TokenHeaderMode.AUTHORIZATION_ONLY -> setRequestProperty("Authorization", token)
                    }
                }
                setRequestProperty("Lang", "en")
                setRequestProperty("Content-Type", variant.contentType)
                setRequestProperty("Accept", "application/json")
                if (!domainNameHeader.isNullOrBlank()) {
                    setRequestProperty("Domain-Name", domainNameHeader)
                }
                extraHeaders.forEach { (name, value) ->
                    setRequestProperty(name, value)
                }
                if (variant.requestBody.isNotBlank()) {
                    setRequestProperty("Content-Length", variant.requestBody.toByteArray(StandardCharsets.UTF_8).size.toString())
                }
            }

            try {
                val bodyBytes = variant.requestBody.toByteArray(StandardCharsets.UTF_8)
                connection.outputStream.use { output ->
                    output.write(bodyBytes)
                }
                val responseStream = if (connection.responseCode in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                } ?: throw IllegalStateException("Vicohome request failed with HTTP ${connection.responseCode}")
                val response = readAll(responseStream)
                recordTokenArtifacts("XM action $action @ $baseUrl (${variant.bodyLabel})", connection.headerFields, response)
                return response
            } catch (exception: Exception) {
                lastFailure = exception
            } finally {
                connection.disconnect()
            }
        }
        throw lastFailure ?: IllegalStateException("Vicohome request failed ($action @ $baseUrl)")
    }

    private fun getXmAction(
        baseUrl: String,
        action: String,
        region: VicohomeRegion,
        token: String? = null,
        headerMode: TokenHeaderMode = TokenHeaderMode.BOTH,
        domainNameHeader: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
    ): String {
        var lastFailure: Exception? = null
        for (queryShape in listOf(emptyMap<String, String>(), mapOf("action" to action))) {
            val timestamp = System.currentTimeMillis()
            val requestUrl = buildRequestUrl(baseUrl, "", queryShape)
            val connection = (URL(requestUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 7000
                setRequestProperty("User-Agent", buildXmUserAgent())
                setRequestProperty("Timestamp", timestamp.toString())
                setRequestProperty("Version", BASEUS_XM_SERVICE_VERSION)
                setRequestProperty("SecretId", BASEUS_XM_APP_KEY)
                setRequestProperty("Signature", buildXmSignature(timestamp))
                setRequestProperty("RequestId", UUID.randomUUID().toString())
                setRequestProperty("Action", action)
                if (!token.isNullOrBlank()) {
                    when (headerMode) {
                        TokenHeaderMode.BOTH -> {
                            setRequestProperty("Authorization", token)
                            setRequestProperty("auth", token)
                        }
                        TokenHeaderMode.AUTH_ONLY -> setRequestProperty("auth", token)
                        TokenHeaderMode.AUTHORIZATION_ONLY -> setRequestProperty("Authorization", token)
                    }
                }
                setRequestProperty("Lang", "en")
                setRequestProperty("Accept", "application/json")
                if (!domainNameHeader.isNullOrBlank()) {
                    setRequestProperty("Domain-Name", domainNameHeader)
                }
                extraHeaders.forEach { (name, value) ->
                    setRequestProperty(name, value)
                }
            }

            try {
                val responseStream = if (connection.responseCode in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                } ?: throw IllegalStateException("Vicohome request failed with HTTP ${connection.responseCode}")
                val response = readAll(responseStream)
                recordTokenArtifacts("XM GET action $action @ $baseUrl", connection.headerFields, response)
                return response
            } catch (exception: Exception) {
                lastFailure = exception
            } finally {
                connection.disconnect()
            }
        }
        throw lastFailure ?: IllegalStateException("Vicohome request failed ($action @ $baseUrl)")
    }

    private enum class TokenHeaderMode {
        BOTH,
        AUTH_ONLY,
        AUTHORIZATION_ONLY,
    }

    private fun extractXmAccessToken(responseObject: JSONObject): String {
        val directToken = firstNonBlank(
            responseObject.optString("access_token").orEmpty(),
            responseObject.optString("accessToken").orEmpty(),
            responseObject.optString("token").orEmpty(),
        )
        if (directToken.isNotBlank()) return directToken
        val dataObject = responseObject.optJSONObject("data")
        if (dataObject != null) {
            val dataToken = firstNonBlank(
                dataObject.optString("access_token").orEmpty(),
                dataObject.optString("accessToken").orEmpty(),
                dataObject.optString("token").orEmpty(),
            )
            if (dataToken.isNotBlank()) return dataToken
            val nestedToken = dataObject.optJSONObject("payload")?.let { extractXmAccessToken(it) }.orEmpty()
            if (nestedToken.isNotBlank()) return nestedToken
        }
        val payloadObject = responseObject.optJSONObject("payload")
        if (payloadObject != null) {
            val payloadToken = firstNonBlank(
                payloadObject.optString("access_token").orEmpty(),
                payloadObject.optString("accessToken").orEmpty(),
                payloadObject.optString("token").orEmpty(),
            )
            if (payloadToken.isNotBlank()) return payloadToken
            val payloadAltToken = payloadObject.optJSONObject("data")?.let { extractXmAccessToken(it) }.orEmpty()
            if (payloadAltToken.isNotBlank()) return payloadAltToken
        }
        return firstNonBlank(
            responseObject.optString("token").orEmpty(),
            responseObject.optString("accessToken").orEmpty(),
        )
    }

    private fun buildXmFormBody(payload: JSONObject): String {
        if (payload.length() == 0) {
            return ""
        }
        val builder = StringBuilder()
        val keys = payload.keys()
        var first = true
        while (keys.hasNext()) {
            val key = keys.next()
            if (!first) {
                builder.append('&')
            }
            first = false
            builder.append(java.net.URLEncoder.encode(key, StandardCharsets.UTF_8.name()))
            builder.append('=')
            builder.append(
                java.net.URLEncoder.encode(
                    when (val value = payload.opt(key)) {
                        null, JSONObject.NULL -> ""
                        is JSONObject -> value.toString()
                        is JSONArray -> value.toString()
                        else -> value.toString()
                    },
                    StandardCharsets.UTF_8.name(),
                )
            )
        }
        return builder.toString()
    }

    private data class XmLoginVariant(
        val action: String,
        val payload: JSONObject,
    )

    private data class XmRequestVariant(
        val contentType: String,
        val requestBody: String,
        val bodyLabel: String,
    )

    private fun updatePrivacyConsent(
        token: String,
        region: VicohomeRegion,
        onProgress: (String) -> Unit = {},
    ): Boolean {
        var lastFailure: Exception? = null
        for (baseUrl in region.consentBaseCandidates) {
            for (serverName in region.userVisitServerCandidates) {
                try {
                    onProgress("Checking Baseus login consent at $baseUrl (server=$serverName)")
                    val visitResponse = getJson(
                        baseUrl,
                        "/api/app/userVisit",
                        region,
                        token,
                        queryParams = mapOf(
                            "server" to serverName,
                            "account" to email,
                        ),
                        extraHeaders = mapOf("Domain-Name" to "global_domain"),
                    )
                    val visitResponseObject = JSONObject(visitResponse)
                    val code = visitResponseObject.optInt("code", visitResponseObject.optInt("result", -1))
                    if (code != 0) {
                        continue
                    }
                    val visitData = visitResponseObject.optJSONObject("data") ?: return true
                    val visitCardId = visitData.optLong("visitCardId").takeIf { it > 0L }
                    if (visitCardId == null) {
                        return true
                    }

                    onProgress("Accepting Baseus login consent card $visitCardId")
                    val commitPayload = JSONObject()
                        .put("state", 5)
                        .put("visitCardId", visitCardId)
                    val commitResponse = postJson(
                        baseUrl,
                        "/api/app/userVisit/readVisit",
                        commitPayload,
                        region,
                        token,
                        extraHeaders = mapOf("Domain-Name" to "global_domain"),
                    )
                    val commitResponseObject = JSONObject(commitResponse)
                    val commitCode = commitResponseObject.optInt("code", commitResponseObject.optInt("result", -1))
                    if (commitCode != 0) {
                        val message = commitResponseObject.optString("msg", commitResponseObject.optString("message", "unknown error"))
                        throw IllegalStateException("Consent update failed (${region.label} @ $baseUrl): $message")
                    }
                    return true
                } catch (exception: Exception) {
                    lastFailure = exception
                }
            }
        }

        if (lastFailure != null) {
            onProgress("Baseus consent update unavailable: ${lastFailure.message ?: "unknown error"}")
        }
        return false
    }

    private fun getJson(
        baseUrl: String,
        path: String,
        region: VicohomeRegion,
        token: String? = null,
        queryParams: Map<String, String> = emptyMap(),
        extraHeaders: Map<String, String> = emptyMap(),
    ): String {
        val connection = (URL(buildRequestUrl(baseUrl, path, queryParams)).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5000
            readTimeout = 7000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "OpenMonitorBridge/1.0")
            setRequestHeaders(this, region, token, "")
            extraHeaders.forEach { (name, value) ->
                setRequestProperty(name, value)
            }
            val timestamp = System.currentTimeMillis().toString()
            setRequestProperty("timestamp", timestamp)
            setRequestProperty("sign", buildRequestSign("", timestamp))
        }

        return try {
            val responseStream = if (connection.responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            } ?: throw IllegalStateException("Vicohome request failed with HTTP ${connection.responseCode}")
            val response = readAll(responseStream)
            recordTokenArtifacts("Baseus GET $path @ $baseUrl", connection.headerFields, response)
            response
        } finally {
            connection.disconnect()
        }
    }

    private fun listRecentEvents(
        token: String,
        region: VicohomeRegion,
        onProgress: (String) -> Unit = {},
    ): List<VicohomeEvent> {
        val end = System.currentTimeMillis() / 1000L
        val start = end - 24 * 60 * 60
        val payload = JSONObject()
            .put("startTimestamp", start.toString())
            .put("endTimestamp", end.toString())
            .put("language", "en")
            .put("countryNo", region.countryNo)

        var lastFailure: Exception? = null
        for (baseUrl in region.apiBaseCandidates) {
            try {
                onProgress("Trying Baseus event host $baseUrl")
                val response = postJson(
                    baseUrl,
                    "/library/newselectlibrary",
                    payload,
                    region,
                    token,
                )
                val responseObject = JSONObject(response)
                if (responseObject.optInt("code", responseObject.optInt("result", 0)) != 0) {
                    return emptyList()
                }

                val list = responseObject
                    .optJSONObject("data")
                    ?.optJSONArray("list")
                    ?: return emptyList()

                val events = mutableListOf<VicohomeEvent>()
                for (index in 0 until list.length()) {
                    val event = list.optJSONObject(index) ?: continue
                    events += VicohomeEvent(
                        traceId = event.optString("traceId"),
                        timestamp = normalizeTimestamp(event.opt("timestamp")),
                        deviceName = event.optString("deviceName"),
                        serialNumber = event.optString("serialNumber"),
                        adminName = event.optString("adminName"),
                        period = normalizePeriod(event.opt("period")),
                        birdName = event.optString("birdName").ifBlank { "Unidentified" },
                        birdLatin = event.optString("birdLatin"),
                        birdConfidence = event.optDouble("birdConfidence"),
                        keyShotUrl = event.optString("keyShotUrl"),
                        imageUrl = event.optString("imageUrl"),
                        videoUrl = event.optString("videoUrl"),
                    )
                }
                return events
            } catch (exception: Exception) {
                lastFailure = exception
            }
        }
        throw lastFailure ?: IllegalStateException("Event list request failed (${region.label})")
    }

    private fun postJson(
        baseUrl: String,
        path: String,
        payload: JSONObject,
        region: VicohomeRegion,
        token: String? = null,
        queryParams: Map<String, String> = emptyMap(),
        extraHeaders: Map<String, String> = emptyMap(),
    ): String {
        val requestBody = payload.toString()
        val connection = (URL(buildRequestUrl(baseUrl, path, queryParams)).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 5000
            readTimeout = 7000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "OpenMonitorBridge/1.0")
            setRequestHeaders(this, region, token, requestBody)
            extraHeaders.forEach { (name, value) ->
                setRequestProperty(name, value)
            }
            if (requestBody.isNotBlank()) {
                val timestamp = System.currentTimeMillis().toString()
                setRequestProperty("timestamp", timestamp)
                setRequestProperty("sign", buildRequestSign(requestBody, timestamp))
            }
        }

        return try {
            connection.outputStream.use { output ->
                output.write(requestBody.toByteArray(StandardCharsets.UTF_8))
            }
            val responseStream = if (connection.responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            } ?: throw IllegalStateException("Vicohome request failed with HTTP ${connection.responseCode}")
            val response = readAll(responseStream)
            recordTokenArtifacts("Baseus JSON $path @ $baseUrl", connection.headerFields, response)
            response
        } finally {
            connection.disconnect()
        }
    }

    private fun recordTokenArtifacts(
        source: String,
        headers: Map<String, List<String>>?,
        body: String,
    ) {
        headers?.forEach { (name, values) ->
            val headerName = name.orEmpty()
            if (headerName.isBlank()) return@forEach
            val lowerName = headerName.lowercase(Locale.US)
            if (lowerName.contains("token") || lowerName.contains("auth") || lowerName.contains("cookie") || lowerName.contains("authorization")) {
                values.filter { it.isNotBlank() }.forEach { value ->
                    TokenHarvestStore.record(source, value, "header=$headerName")
                    TokenHarvestStore.recordFromText(source, value, "header=$headerName")
                }
            }
        }
        TokenHarvestStore.recordFromText(source, body)
    }

    private fun buildRequestUrl(baseUrl: String, path: String, queryParams: Map<String, String>): String {
        val builder = StringBuilder(baseUrl.trimEnd('/')).append(path)
        if (queryParams.isNotEmpty()) {
            builder.append('?')
            queryParams.entries.forEachIndexed { index, entry ->
                if (index > 0) {
                    builder.append('&')
                }
                builder.append(entry.key)
                builder.append('=')
                builder.append(java.net.URLEncoder.encode(entry.value, StandardCharsets.UTF_8.name()))
            }
        }
        return builder.toString()
    }

    private fun setRequestHeaders(
        connection: HttpURLConnection,
        region: VicohomeRegion,
        token: String?,
        requestBody: String,
    ) {
        connection.setRequestProperty("platform", "1")
        connection.setRequestProperty("auth", token.orEmpty())
        connection.setRequestProperty("osVersion", android.os.Build.VERSION.RELEASE.orEmpty())
        connection.setRequestProperty("brand", sanitizeHeaderValue(android.os.Build.BRAND))
        connection.setRequestProperty("model", sanitizeHeaderValue(android.os.Build.MODEL))
        connection.setRequestProperty("appLang", "en")
        connection.setRequestProperty("appVersion", BASEUS_APP_VERSION_NAME)
        connection.setRequestProperty("versionCode", BASEUS_APP_VERSION_CODE)
        connection.setRequestProperty("channel", "")
        connection.setRequestProperty("region", region.shortName)
        connection.setRequestProperty("appType", "40")
        if (requestBody.isNotBlank()) {
            connection.setRequestProperty("Content-Length", requestBody.toByteArray(StandardCharsets.UTF_8).size.toString())
        }
    }

    private fun extractServiceCatalogResolution(
        responseObject: JSONObject,
        region: VicohomeRegion,
    ): ServiceCatalogResolution {
        val candidates = mutableListOf<JSONObject>()
        fun collectObjects(value: Any?) {
            when (value) {
                is JSONObject -> {
                    candidates += value
                    value.keys().forEachRemaining { key ->
                        val nested = value.opt(key)
                        if (nested is JSONObject || nested is JSONArray) {
                            collectObjects(nested)
                        }
                    }
                }
                is JSONArray -> {
                    for (index in 0 until value.length()) {
                        collectObjects(value.opt(index))
                    }
                }
            }
        }

        collectObjects(responseObject.opt("data"))
        collectObjects(responseObject.optJSONArray("data"))
        collectObjects(responseObject.optJSONObject("data")?.opt("base_cloud_service_list"))
        collectObjects(responseObject.opt("base_cloud_service_list"))

        val normalizedRegion = region.label.lowercase(Locale.US)
        val entries = mutableListOf<VicohomeServiceCatalog>()
        var selected: VicohomeServiceCatalog? = null
        for (candidate in candidates) {
            val label = candidate.optString("label").orEmpty()
            val description = candidate.optString("description").orEmpty()
            val value = candidate.optString("value").orEmpty()
            val mergedText = listOf(label, description, value).joinToString(" ").lowercase(Locale.US)
            val serversObject = candidate.optJSONObject("servers")
                ?: candidate.optJSONObject("value")?.takeIf { it.has("bsServer") || it.has("oauthServer") || it.has("globalServer") }
                ?: value.takeIf { it.trim().startsWith("{") }?.let { runCatching { JSONObject(it) }.getOrNull() }
            if (serversObject != null) {
                val bsServer = firstNonBlank(
                    serversObject.optString("bsServer").orEmpty(),
                    serversObject.optString("host_domain").orEmpty(),
                    serversObject.optString("hostDomain").orEmpty(),
                )
                val oauthServer = firstNonBlank(
                    serversObject.optString("oauthServer").orEmpty(),
                    serversObject.optString("auth_domain").orEmpty(),
                    serversObject.optString("authDomain").orEmpty(),
                )
                val globalServer = firstNonBlank(
                    serversObject.optString("globalServer").orEmpty(),
                    serversObject.optString("global_domain").orEmpty(),
                    serversObject.optString("globalDomain").orEmpty(),
                )
                if (bsServer.isNotBlank() || oauthServer.isNotBlank() || globalServer.isNotBlank()) {
                    val entry = VicohomeServiceCatalog(
                        label = label.ifBlank { region.label },
                        value = value.ifBlank { region.serverCode.orEmpty() },
                        bsServer = bsServer.ifBlank { region.apiBase },
                        oauthServer = oauthServer.ifBlank { region.authBaseCandidates.firstOrNull().orEmpty() },
                        globalServer = globalServer.ifBlank { region.webrtcApiBase },
                    )
                    entries += entry
                    if (selected == null && (normalizedRegion in mergedText || mergedText.contains(normalizedRegion.take(2)))) {
                        selected = entry
                    }
                }
            }
        }
        if (selected == null) {
            selected = entries.firstOrNull()
        }
        return ServiceCatalogResolution(selected, entries)
    }

    private fun buildRequestSign(requestBody: String, timestamp: String): String {
        val md5 = MessageDigest.getInstance("MD5").digest(requestBody.toByteArray(StandardCharsets.UTF_8))
        val md5Hex = md5.joinToString(separator = "") { byte ->
            byte.toInt().and(0xff).toString(16).padStart(2, '0')
        }.uppercase(Locale.US)
        val sha1 = MessageDigest.getInstance("SHA1").digest("GSiPpcmX${md5Hex}${timestamp}".toByteArray(StandardCharsets.UTF_8))
        return "ipc#CElYvAkK#" + sha1.joinToString(separator = "") { byte ->
            byte.toInt().and(0xff).toString(16).padStart(2, '0')
        }
    }

    private fun buildXmUserAgent(): String {
        return "Android/$BASEUS_APP_VERSION_NAME/${sanitizeHeaderValue(android.os.Build.BRAND)}/${sanitizeHeaderValue(android.os.Build.MODEL)}/${android.os.Build.VERSION.RELEASE.orEmpty()}"
    }

    private fun buildXmSignature(timestamp: Long): String {
        val digest = MessageDigest.getInstance("MD5").digest(
            "$timestamp$BASEUS_XM_APP_KEY$BASEUS_XM_APP_SECRET".toByteArray(StandardCharsets.UTF_8)
        )
        return digest.joinToString(separator = "") { byte ->
            byte.toInt().and(0xff).toString(16).padStart(2, '0')
        }
    }

    private fun sanitizeHeaderValue(value: String?): String {
        if (value.isNullOrBlank()) {
            return ""
        }
        val builder = StringBuilder(value.length)
        for (character in value) {
            if (character.code <= 31 && character != '\t' || character.code >= 127) {
                builder.append("unknow")
            } else {
                builder.append(character)
            }
        }
        return builder.toString()
    }

    private fun parseLiveTicket(data: JSONObject): VicohomeLiveTicket {
        val iceServers = mutableListOf<VicohomeIceServer>()
        val servers = data.optJSONArray("iceServer")
        if (servers != null) {
            for (index in 0 until servers.length()) {
                val server = servers.optJSONObject(index) ?: continue
                iceServers += VicohomeIceServer(
                    url = server.optString("url"),
                    username = server.optString("username"),
                    credential = server.optString("credential"),
                    ipAddress = server.optString("ipAddress"),
                )
            }
        }
        return VicohomeLiveTicket(
            traceId = data.optString("traceId"),
            groupId = data.optString("groupId"),
            role = data.optString("role"),
            id = data.optString("id"),
            iceServer = iceServers,
            signalServer = data.optString("signalServer"),
            signalServerIpAddress = data.optString("signalServerIpAddress"),
            sign = data.optString("sign"),
            signalPingInterval = data.optInt("signalPingInterval"),
            maxAllocationLimit = data.optInt("maxAllocationLimit"),
            appStopLiveTimeout = data.optInt("appStopLiveTimeout"),
            deviceSleepTimeout = data.optInt("deviceSleepTimeout"),
            time = data.optLong("time"),
            expirationTime = data.optLong("expirationTime"),
            websocketPath = data.optString("websocketPath"),
            accessToken = data.optString("accessToken"),
            realCxSerialNumber = data.optString("realCxSerialNumber").takeIf { it.isNotBlank() },
            countryNo = data.optString("countryNo").takeIf { it.isNotBlank() },
        )
    }

    private fun maybeEnqueueActionServerHosts(
        response: String,
        message: String,
        baseUrlQueue: ArrayDeque<String>,
        enqueuedBaseUrls: MutableSet<String>,
        serviceCatalogEntries: List<VicohomeServiceCatalog>,
        onProgress: (String) -> Unit,
    ) {
        val hint = extractActionServerHint(response) ?: extractActionServerHint(message) ?: return
        onProgress(
            "Baseus ActionServer hint: name=${hint.name} baseServer=${hint.baseServer} auth=${hint.authRequired} raw=${hint.raw}"
        )
        TokenHarvestStore.record("Baseus ActionServer", hint.raw, "action server hint")
        val matchingEntries = serviceCatalogEntries.filter { catalog ->
            catalog.matchesServiceHint(hint.name) || catalog.matchesServiceHint(hint.baseServer)
        }
        if (matchingEntries.isEmpty()) {
            val guessedHost = buildGuessHostFromActionServer(hint.baseServer)
            if (guessedHost.isNotBlank() && enqueuedBaseUrls.add(guessedHost)) {
                baseUrlQueue.addLast(guessedHost)
                onProgress("Queued guessed Baseus host for ActionServer $guessedHost")
            }
            return
        }
        matchingEntries.forEach { catalog ->
            listOf(catalog.bsServer, catalog.oauthServer, catalog.globalServer).forEach { candidate ->
                val normalized = candidate.trim()
                if (normalized.isNotBlank() && enqueuedBaseUrls.add(normalized)) {
                    baseUrlQueue.addLast(normalized)
                    onProgress("Queued Baseus host from ActionServer match: $normalized")
                }
            }
        }
    }

    private fun extractActionServerHint(text: String): ActionServerHint? {
        val regex = Regex(
            """ActionServer\s*\(\s*name\s*=\s*([^,\)]+)\s*,\s*baseServer\s*=\s*([^,\)]+)\s*,\s*auth\s*=\s*([^)]+)\)""",
            RegexOption.IGNORE_CASE,
        )
        val match = regex.find(text) ?: return null
        return ActionServerHint(
            name = match.groupValues.getOrNull(1).orEmpty().trim(),
            baseServer = match.groupValues.getOrNull(2).orEmpty().trim(),
            authRequired = match.groupValues.getOrNull(3).orEmpty().trim().equals("true", ignoreCase = true),
            raw = match.value,
        )
    }

    private fun VicohomeServiceCatalog.matchesServiceHint(hint: String): Boolean {
        val normalizedHint = normalizeServiceKey(hint)
        if (normalizedHint.isBlank()) return false
        return listOf(label, value, bsServer, oauthServer, globalServer).any { candidate ->
            val normalizedCandidate = normalizeServiceKey(candidate)
            normalizedCandidate.isNotBlank() && (normalizedCandidate.contains(normalizedHint) || normalizedHint.contains(normalizedCandidate))
        }
    }

    private fun buildGuessHostFromActionServer(baseServer: String): String {
        val normalized = normalizeServiceKey(baseServer)
        if (normalized.isBlank()) {
            return ""
        }
        return when {
            normalized.contains("userdeviceservice") -> "https://ipc-bu-us-gw.baseussecurity.com"
            normalized.contains("globalservice") -> "https://api-us.vicoo.tech"
            normalized.contains("oauthservice") -> "https://baseus-us-auth-gw.baseussecurity.com"
            else -> ""
        }
    }

    private fun normalizeServiceKey(value: String): String {
        if (value.isBlank()) return ""
        val builder = StringBuilder(value.length)
        for (character in value.lowercase(Locale.US)) {
            when {
                character.isLetterOrDigit() -> builder.append(character)
                else -> Unit
            }
        }
        return builder.toString()
    }

    private data class ServiceCatalogResolution(
        val selected: VicohomeServiceCatalog?,
        val entries: List<VicohomeServiceCatalog>,
    )

    private fun readAll(stream: java.io.InputStream): String {
        return BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { reader ->
            buildString {
                while (true) {
                    val line = reader.readLine() ?: break
                    append(line)
                }
            }
        }
    }

    private fun parseDeviceList(responseObject: JSONObject): List<VicohomeDevice> {
        val containers = listOfNotNull(
            responseObject.optJSONObject("data"),
            responseObject.optJSONObject("payload"),
            responseObject.optJSONObject("result"),
            responseObject,
        )
        for (container in containers) {
            val entries = firstArray(container, "device_list", "deviceList", "devices", "list")
                ?: continue
            val devices = mutableListOf<VicohomeDevice>()
            for (index in 0 until entries.length()) {
                val device = entries.optJSONObject(index) ?: continue
                val deviceInfo = device.optJSONObject("device_info")
                val family = device.optJSONObject("family")
                val serialNumber = firstNonBlank(
                    device.optString("device_sn"),
                    device.optString("serialNumber"),
                    device.optString("sn"),
                )
                if (serialNumber.isBlank()) continue
                val deviceName = firstNonBlank(
                    device.optString("device_name"),
                    device.optString("main_cam_name"),
                    device.optString("deviceName"),
                    deviceInfo?.optString("camera_name"),
                    deviceInfo?.optString("cusUIDeviceName"),
                    serialNumber,
                )
                val modelNo = firstNonBlank(
                    device.optString("device_model"),
                    device.optString("modelNo"),
                    deviceInfo?.optString("camera_model"),
                    deviceInfo?.optString("cusUIDeviceModel"),
                )
                val networkName = firstNonBlank(
                    deviceInfo?.optString("cur_ssid"),
                    device.optString("networkName"),
                    family?.optString("family_name"),
                )
                val ipAddress = firstIpv4Address(
                    deviceInfo?.optString("ip_addr"),
                    device.optString("ip"),
                    device.optString("ip_addr"),
                )
                val batteryLevel = firstInt(
                    deviceInfo?.opt("battery"),
                    device.opt("batteryLevel"),
                )
                val locationName = firstNonBlank(
                    family?.optString("family_name"),
                    device.optString("locationName"),
                )
                val signalStrength = firstInt(
                    deviceInfo?.opt("wifi_signal"),
                    device.opt("signalStrength"),
                )
                val wifiChannel = firstInt(
                    deviceInfo?.opt("WiFiChannel"),
                    device.opt("wifiChannel"),
                )
                val isCharging = firstInt(
                    deviceInfo?.opt("charge_state"),
                    device.opt("isCharging"),
                )
                val chargingMode = firstInt(
                    deviceInfo?.opt("network_type"),
                    device.opt("chargingMode"),
                )
                val macAddress = firstNonBlank(
                    deviceInfo?.optString("mac_addr"),
                    deviceInfo?.optString("mac"),
                    device.optString("macAddress"),
                )
                devices += VicohomeDevice(
                    serialNumber = serialNumber,
                    modelNo = modelNo,
                    deviceName = deviceName,
                    networkName = networkName,
                    ip = ipAddress,
                    batteryLevel = batteryLevel,
                    locationName = locationName,
                    signalStrength = signalStrength,
                    wifiChannel = wifiChannel,
                    isCharging = isCharging,
                    chargingMode = chargingMode,
                    macAddress = macAddress,
                )
            }
            if (devices.isNotEmpty()) {
                return devices.distinctBy { it.serialNumber }
            }
        }
        return emptyList()
    }

    private fun describeDeviceListShape(responseObject: JSONObject): String {
        val container = responseObject.optJSONObject("data")
            ?: responseObject.optJSONObject("payload")
            ?: responseObject.optJSONObject("result")
            ?: responseObject
        val keys = mutableListOf<String>()
        val iterator = container.keys()
        while (iterator.hasNext()) {
            keys += iterator.next()
        }
        val deviceCount = firstArray(container, "device_list", "deviceList", "devices", "list")?.length() ?: 0
        val total = when {
            container.has("total") -> container.optInt("total", -1)
            responseObject.has("total") -> responseObject.optInt("total", -1)
            else -> -1
        }
        return buildString {
            append("keys=")
            append(keys.joinToString(separator = ",").ifBlank { "none" })
            append(", total=")
            append(total)
            append(", deviceCount=")
            append(deviceCount)
        }
    }

    private fun firstArray(container: JSONObject, vararg names: String): JSONArray? {
        for (name in names) {
            val array = container.optJSONArray(name)
            if (array != null) {
                return array
            }
        }
        return null
    }

    private fun firstNonBlank(vararg values: String?): String {
        for (value in values) {
            val trimmed = value?.trim().orEmpty()
            if (trimmed.isNotBlank()) {
                return trimmed
            }
        }
        return ""
    }

    private fun firstIpv4Address(vararg values: String?): String {
        for (value in values) {
            val trimmed = value?.trim().orEmpty()
            if (isIpv4Address(trimmed)) {
                return trimmed
            }
        }
        return ""
    }

    private fun firstInt(vararg values: Any?): Int {
        for (value in values) {
            when (value) {
                is Number -> return value.toInt()
                is String -> value.toIntOrNull()?.let { return it }
            }
        }
        return 0
    }

    private fun isIpv4Address(value: String): Boolean {
        val parts = value.split('.')
        if (parts.size != 4) return false
        for (part in parts) {
            val number = part.toIntOrNull() ?: return false
            if (number !in 0..255) return false
        }
        return true
    }

    private fun normalizeTimestamp(value: Any?): String {
        return when (value) {
            is Number -> value.toLong().let { epochSeconds ->
                java.time.Instant.ofEpochSecond(epochSeconds).toString()
            }
            is String -> value
            else -> ""
        }
    }

    private fun normalizePeriod(value: Any?): String {
        return when (value) {
            is Number -> String.format("%.2fs", value.toDouble())
            is String -> value
            else -> ""
        }
    }
}
