package `in`.aicortex.iso8583studio.domain.service.posSimulatorService

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import `in`.aicortex.iso8583studio.logging.LogEntry
import `in`.aicortex.iso8583studio.logging.LogType
import `in`.aicortex.iso8583studio.ui.navigation.stateConfigs.pos.POSSimulatorConfig
import `in`.aicortex.iso8583studio.ui.screens.hostSimulator.Transaction
import `in`.aicortex.iso8583studio.domain.service.apduSimulatorService.profile.samples.SampleProfiles
import `in`.aicortex.iso8583studio.domain.service.apduSimulatorService.runtime.CardRuntime
import `in`.aicortex.iso8583studio.domain.service.apduSimulatorService.runtime.handlers.GenerateAcHandler
import `in`.aicortex.iso8583studio.domain.service.apduSimulatorService.runtime.handlers.GetChallengeHandler
import `in`.aicortex.iso8583studio.domain.service.apduSimulatorService.runtime.handlers.GetDataHandler
import `in`.aicortex.iso8583studio.domain.service.apduSimulatorService.runtime.handlers.GpoHandler
import `in`.aicortex.iso8583studio.domain.service.apduSimulatorService.runtime.handlers.ReadRecordHandler
import `in`.aicortex.iso8583studio.domain.service.apduSimulatorService.runtime.handlers.SelectHandler
import `in`.aicortex.iso8583studio.domain.service.apduSimulatorService.runtime.handlers.VerifyHandler
import `in`.aicortex.iso8583studio.domain.service.apduSimulatorService.terminal.TerminalProfiles
import `in`.aicortex.iso8583studio.domain.service.apduSimulatorService.terminal.TerminalRuntime
import `in`.aicortex.iso8583studio.domain.service.apduSimulatorService.terminal.TransactionRequest
import `in`.aicortex.iso8583studio.domain.service.apduSimulatorService.terminal.TransactionStep
import `in`.aicortex.iso8583studio.domain.service.apduSimulatorService.terminal.TransactionType
import `in`.aicortex.iso8583studio.domain.service.apduSimulatorService.transport.LoopbackTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicInteger

/**
 * Hardware-free POS terminal service.
 *
 * This is the seam between the UI and a terminal implementation. It has three host modes:
 *
 *  - EMBEDDED: a deterministic acquirer/issuer stub in the same process (default);
 *  - TCP: a framed ISO 8583 connection to a switch or Host Simulator;
 *  - REST: a raw ISO 8583 POST for HTTP test benches.
 *
 * A real card reader, PIN pad, NFC antenna and vendor SDK are never required. The selected device
 * profile changes the terminal capabilities and entry mode while the same host-facing contract is
 * exercised for every supported device family.
 */
class POSSimulatorService(
    private val isoConfig: POSSimulatorConfig,
) {
    var hostAddress by mutableStateOf(isoConfig.hostAddress)
    var hostPort by mutableStateOf(isoConfig.hostPort)

    private val stanCounter = AtomicInteger(100000)
    private var socket: Socket? = null
    private var tcpInput: BufferedInputStream? = null
    private var tcpOutput: BufferedOutputStream? = null

    var isConnected by mutableStateOf(false)
        private set
    var state by mutableStateOf(POSDeviceState.DISCONNECTED)
        private set
    var selectedProfile by mutableStateOf(POSDeviceProfiles.byId(isoConfig.deviceProfileId))
        private set
    var hostMode by mutableStateOf(isoConfig.hostTransportMode)
    var frameFormat by mutableStateOf(isoConfig.hostFrameFormat)
    var metrics by mutableStateOf(POSMetrics())
        private set
    var lastResult by mutableStateOf<POSAuthorizationResult?>(null)
        private set

    /** Built-in scenarios remain available even when an imported POS profile has no saved templates. */
    val scenarios: List<POSScenario> = POSScenario.builtIns() +
        isoConfig.simulatedTransactionsToDest.mapIndexed { index, transaction -> legacyScenario(index, transaction) }

    // Existing UI callbacks are retained for compatibility with old saved sessions.
    var onLog: (LogEntry) -> Unit = {}
    var onRequestSent: (String) -> Unit = {}
    var onResponseReceived: (String) -> Unit = {}
    var onConnectionStateChange: (Boolean) -> Unit = {}
    var onEvent: (POSDeviceEvent) -> Unit = {}

    private val hostConfig: POSHostSimulatorConfig
        get() = POSHostSimulatorConfig(
            mode = hostMode,
            address = hostAddress,
            port = hostPort,
            frameFormat = frameFormat,
            timeoutMs = isoConfig.hostTimeoutMs.coerceIn(250, 120_000),
            responseCode = isoConfig.embeddedResponseCode,
            approvalLimit = isoConfig.embeddedApprovalLimit,
            latencyMs = isoConfig.embeddedLatencyMs.coerceAtLeast(0),
        )

    fun selectDevice(profileId: String) {
        selectedProfile = POSDeviceProfiles.byId(profileId)
        log(LogType.INFO, "Device profile selected", "${selectedProfile.vendor} ${selectedProfile.model}")
        onEvent(POSDeviceEvent.DeviceReady(selectedProfile.id, selectedProfile.model))
    }

    /** Connects to the configured host, or starts a local virtual host session. */
    suspend fun connect() = withContext(Dispatchers.IO) {
        if (isConnected) return@withContext
        state = POSDeviceState.CONNECTING
        onEvent(POSDeviceEvent.StateChanged(state))
        try {
            when (hostConfig.mode) {
                POSHostTransportMode.EMBEDDED -> Unit
                POSHostTransportMode.TCP -> {
                    val newSocket = Socket()
                    newSocket.connect(InetSocketAddress(hostAddress, hostPort), hostConfig.timeoutMs)
                    newSocket.soTimeout = hostConfig.timeoutMs
                    socket = newSocket
                    tcpInput = BufferedInputStream(newSocket.getInputStream())
                    tcpOutput = BufferedOutputStream(newSocket.getOutputStream())
                }
                POSHostTransportMode.REST -> validateRestEndpoint()
            }
            isConnected = true
            state = POSDeviceState.READY
            onConnectionStateChange(true)
            onEvent(POSDeviceEvent.StateChanged(state))
            log(LogType.CONNECTION, "POS device ready", "${selectedProfile.vendor} ${selectedProfile.model}; host=${hostConfig.mode.label}")
        } catch (t: Throwable) {
            state = POSDeviceState.ERROR
            onEvent(POSDeviceEvent.Failure("Unable to connect POS host: ${t.message}", t))
            onEvent(POSDeviceEvent.StateChanged(state))
            log(LogType.ERROR, "POS host connection failed", t.message)
            closeConnection()
            throw t
        }
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        closeConnection()
        state = POSDeviceState.DISCONNECTED
        onConnectionStateChange(false)
        onEvent(POSDeviceEvent.StateChanged(state))
        log(LogType.CONNECTION, "POS device disconnected")
    }

    /** Run one built-in or imported scenario. */
    suspend fun sendScenario(scenario: POSScenario): POSAuthorizationResult {
        val imported = isoConfig.simulatedTransactionsToDest.firstOrNull { transaction ->
            scenario.id.endsWith("-${transaction.id}")
        }
        return if (imported != null) {
            sendTransaction(imported)
        } else {
            authorize(
                POSPaymentRequest(
                    amountMinor = scenario.amountMinor,
                    kind = scenario.kind,
                    input = scenario.input,
                    card = scenario.card,
                    forceDecline = scenario.forceDecline,
                )
            )
        }
    }

    /**
     * Compatibility adapter for the old ISO template list. A transaction's populated bits are
     * used when available; otherwise it becomes a normal synthetic purchase.
     */
    suspend fun sendTransaction(transaction: Transaction): POSAuthorizationResult {
        val values = transaction.fields.orEmpty().mapIndexedNotNull { index, field ->
            if (field.isSet) index + 1 to runCatching { field.getString() }.getOrNull().orEmpty() else null
        }.toMap()
        val amount = values[4]?.filter(Char::isDigit)?.toLongOrNull() ?: 1250L
        val input = when (values[22]?.takeLast(1)) {
            "1" -> POSCardInput.CONTACT_EMV
            "2", "7" -> POSCardInput.CONTACTLESS_EMV
            "0" -> POSCardInput.MANUAL
            else -> POSCardInput.CONTACTLESS_EMV
        }
        return authorize(POSPaymentRequest(amountMinor = amount, input = input, forceDecline = false))
    }

    /** Execute a complete terminal-to-host authorisation exchange. */
    suspend fun authorize(request: POSPaymentRequest): POSAuthorizationResult = withContext(Dispatchers.IO) {
        check(isConnected) { "POS device is not connected to a host" }
        require(request.amountMinor >= 0) { "Amount cannot be negative" }
        require(selectedProfile.supports(request.input)) {
            "${selectedProfile.vendor} ${selectedProfile.model} does not support ${request.input.label}"
        }

        state = POSDeviceState.PROCESSING
        onEvent(POSDeviceEvent.StateChanged(state))
        val started = System.currentTimeMillis()
        val effectiveInput = if (
            request.input in setOf(POSCardInput.CONTACTLESS_EMV, POSCardInput.NFC_MOBILE) &&
            request.amountMinor > selectedProfile.contactlessLimitMinor
        ) {
            log(LogType.WARNING, "Contactless limit exceeded; terminal requests chip fallback", "amount=${request.amountMinor}, limit=${selectedProfile.contactlessLimitMinor}")
            POSCardInput.CONTACT_EMV
        } else request.input

        val requestMessage = buildRequest(request.copy(input = effectiveInput))
        val requestBytes = Iso8583Codec.encode(requestMessage)
        onEvent(POSDeviceEvent.RequestBuilt(requestMessage, requestBytes))
        onRequestSent(formatExchange(requestMessage, requestBytes))
        log(LogType.MESSAGE, "ISO 8583 request sent", "${requestMessage.mti} STAN=${requestMessage.fields[11]} bytes=${requestBytes.size}")

        try {
            val responseBytes = exchange(requestBytes, request)
            val responseMessage = Iso8583Codec.decode(responseBytes)
            onEvent(POSDeviceEvent.ResponseReceived(responseMessage, responseBytes))
            onResponseReceived(formatExchange(responseMessage, responseBytes))
            val code = responseMessage.fields[39] ?: "96"
            val result = POSAuthorizationResult(
                approved = code == "00" || code == "08" || code == "10",
                responseCode = code,
                responseMessage = responseDescription(code),
                request = requestMessage,
                response = responseMessage,
                requestBytes = requestBytes,
                responseBytes = responseBytes,
                latencyMs = System.currentTimeMillis() - started,
            )
            metrics = metrics.record(result)
            lastResult = result
            state = POSDeviceState.READY
            onEvent(POSDeviceEvent.TransactionCompleted(result))
            onEvent(POSDeviceEvent.StateChanged(state))
            log(if (result.approved) LogType.AUTHORIZATION else LogType.WARNING, "${result.responseMessage} — ${request.kind.label}", "STAN=${result.stan}, RRN=${result.rrn}")
            result
        } catch (t: Throwable) {
            val result = POSAuthorizationResult(
                approved = false,
                responseCode = "96",
                responseMessage = "Host communication failed",
                request = requestMessage,
                response = null,
                requestBytes = requestBytes,
                responseBytes = ByteArray(0),
                latencyMs = System.currentTimeMillis() - started,
                error = t.message ?: t::class.simpleName,
            )
            metrics = metrics.record(result)
            lastResult = result
            state = POSDeviceState.ERROR
            onEvent(POSDeviceEvent.Failure(result.error ?: "Host communication failed", t))
            onEvent(POSDeviceEvent.TransactionCompleted(result))
            onEvent(POSDeviceEvent.StateChanged(state))
            log(LogType.ERROR, "POS transaction failed", result.error)
            result
        }
    }

    private fun legacyScenario(index: Int, transaction: Transaction): POSScenario {
        val values = transaction.fields.orEmpty().mapIndexedNotNull { fieldIndex, field ->
            if (field.isSet) fieldIndex + 1 to runCatching { field.getString() }.getOrNull().orEmpty() else null
        }.toMap()
        val input = when (values[22]?.takeLast(1)) {
            "1" -> POSCardInput.CONTACT_EMV
            "0" -> POSCardInput.MANUAL
            "9" -> POSCardInput.MAGSTRIPE
            else -> POSCardInput.CONTACTLESS_EMV
        }
        val kind = when (transaction.mti) {
            "0100" -> POSTransactionKind.PREAUTH
            "0400" -> POSTransactionKind.REVERSAL
            "0220" -> POSTransactionKind.COMPLETION
            else -> if (transaction.proCode.startsWith("20")) POSTransactionKind.REFUND else POSTransactionKind.PURCHASE
        }
        return POSScenario(
            id = "legacy-$index-${transaction.id}",
            name = transaction.description.ifBlank { "Imported ${kind.label}" },
            description = "Imported ISO 8583 template ${transaction.id}",
            kind = kind,
            amountMinor = values[4]?.filter(Char::isDigit)?.toLongOrNull() ?: 1250L,
            input = input,
        )
    }

    private suspend fun buildRequest(request: POSPaymentRequest): Iso8583Message {
        val stan = nextStan()
        val now = LocalDateTime.now()
        val dateTime = now.format(DateTimeFormatter.ofPattern("MMddHHmmss"))
        val localTime = now.format(DateTimeFormatter.ofPattern("HHmmss"))
        val localDate = now.format(DateTimeFormatter.ofPattern("MMdd"))
        val terminalId = terminalId()
        val merchantId = merchantId()
        val fields = linkedMapOf<Int, String>()
        fields[2] = request.card.pan
        fields[3] = request.kind.processingCode
        fields[4] = request.amountMinor.toString().padStart(12, '0')
        fields[7] = dateTime
        fields[11] = stan
        fields[12] = localTime
        fields[13] = localDate
        fields[22] = request.input.entryMode
        fields[25] = if (request.input == POSCardInput.MAGSTRIPE) "00" else "00"
        fields[37] = rrn(stan)
        fields[41] = terminalId
        fields[42] = merchantId
        fields[49] = request.currency.padStart(3, '0').takeLast(3)
        if (request.input == POSCardInput.MAGSTRIPE) fields[35] = request.card.track2Equivalent
        if (request.input in setOf(POSCardInput.CONTACT_EMV, POSCardInput.CONTACTLESS_EMV, POSCardInput.NFC_MOBILE)) {
            val cardFlow = simulateEmvCard(request)
            fields[55] = emvData(request, now, cardFlow)
        }
        fields[60] = "POSSIM${selectedProfile.id.take(6).uppercase()}"
        return Iso8583Message(request.kind.requestMti, fields)
    }

    private suspend fun simulateEmvCard(request: POSPaymentRequest): EmvCardFlowResult? {
        val cardProfile = when {
            request.card.aid.startsWith("A000000004", ignoreCase = true) -> SampleProfiles.mastercardDebitTest()
            request.card.aid.startsWith("A000000003", ignoreCase = true) -> SampleProfiles.visaCreditTest()
            else -> {
                log(LogType.WARNING, "No bundled EMV card profile for AID", request.card.aid)
                return null
            }
        }
        return try {
            val runtime = CardRuntime(
                cardProfile,
                listOf(
                    SelectHandler(),
                    GpoHandler(),
                    ReadRecordHandler(),
                    GetDataHandler(),
                    GetChallengeHandler(),
                    VerifyHandler(),
                    GenerateAcHandler(),
                ),
            )
            val transport = LoopbackTransport(runtime)
            transport.connect()
            val now = LocalDateTime.now()
            val terminalRuntime = TerminalRuntime(transport, TerminalProfiles.attendedRetailIN())
            val transactionType = when (request.kind) {
                POSTransactionKind.REFUND -> TransactionType.REFUND
                POSTransactionKind.PREAUTH -> TransactionType.PURCHASE
                POSTransactionKind.VOID -> TransactionType.PURCHASE
                POSTransactionKind.REVERSAL -> TransactionType.PURCHASE
                POSTransactionKind.COMPLETION -> TransactionType.PURCHASE
                POSTransactionKind.PURCHASE -> TransactionType.PURCHASE
            }
            val steps = terminalRuntime.run(
                TransactionRequest(
                    amount = request.amountMinor,
                    type = transactionType,
                    date = now.format(DateTimeFormatter.ofPattern("yyMMdd")),
                    time = now.format(DateTimeFormatter.ofPattern("HHmmss")),
                ),
            ).toList()
            val outcome = steps.filterIsInstance<TransactionStep.Outcome>().lastOrNull()
            if (outcome == null) {
                val reason = steps.filterIsInstance<TransactionStep.Aborted>().lastOrNull()?.reason
                    ?: "card runtime did not produce a cryptogram"
                log(LogType.WARNING, "EMV card flow did not complete", reason)
                null
            } else {
                log(LogType.DEBUG, "EMV card flow completed", "AID=${request.card.aid}, AC=${Iso8583Codec.hex(outcome.ac)}, ATC=${outcome.atc}")
                EmvCardFlowResult(
                    ac = outcome.ac,
                    cid = outcome.cid,
                    atc = outcome.atc,
                    iad = outcome.iad,
                    tvr = outcome.tvr,
                    tsi = outcome.tsi,
                )
            }
        } catch (t: Throwable) {
            // A host-facing test should still be useful when a custom card profile is incomplete;
            // the request falls back to a deterministic synthetic EMV payload and the problem is
            // visible in the POS log rather than being silently swallowed.
            log(LogType.WARNING, "EMV card runtime fallback", t.message ?: t::class.simpleName)
            null
        }
    }

    private fun emvData(
        request: POSPaymentRequest,
        now: LocalDateTime,
        cardFlow: EmvCardFlowResult?,
    ): String {
        val amount = request.amountMinor.toString().padStart(12, '0')
        val date = now.format(DateTimeFormatter.ofPattern("yyMMdd"))
        val time = now.format(DateTimeFormatter.ofPattern("HHmmss"))
        // Synthetic values are test-only. When available, 9F26/9F27/9F36/9F10 and the TVR/TSI
        // come from the existing in-process EMV card runtime, not from a placeholder constant.
        fun tlv(tag: String, value: String): String =
            tag + (value.length / 2).toString(16).padStart(2, '0') + value
        val dynamic = cardFlow?.let {
            tlv("82", "1980") +
                tlv("95", Iso8583Codec.hex(it.tvr)) +
                tlv("9B", Iso8583Codec.hex(it.tsi)) +
                tlv("9F26", Iso8583Codec.hex(it.ac)) +
                tlv("9F27", "%02X".format(it.cid and 0xFF)) +
                tlv("9F36", "%04X".format(it.atc and 0xFFFF)) +
                if (it.iad.isNotEmpty()) tlv("9F10", Iso8583Codec.hex(it.iad)) else ""
        } ?: (
            // Keep custom/unknown AIDs testable even when no matching sample card profile exists.
            // This is explicitly synthetic and is called out in the log by simulateEmvCard.
            tlv("9F26", "A1B2C3D4E5F60718") +
                tlv("9F27", "80") + tlv("9F36", "0001")
            )
        return tlv("9F02", amount) + tlv("9F03", "000000000000") + tlv("9F1A", "0356") +
            tlv("5F2A", "0356") + tlv("9A", date) + tlv("9F21", time) +
            tlv("9F37", "A1B2C3D4") + tlv("9F35", selectedProfile.terminalType) +
            tlv("9F33", selectedProfile.terminalCapabilities) + tlv("4F", request.card.aid) + dynamic
    }

    private data class EmvCardFlowResult(
        val ac: ByteArray,
        val cid: Int,
        val atc: Int,
        val iad: ByteArray,
        val tvr: ByteArray,
        val tsi: ByteArray,
    )

    private suspend fun exchange(payload: ByteArray, request: POSPaymentRequest): ByteArray {
        return when (hostConfig.mode) {
            POSHostTransportMode.EMBEDDED -> EmbeddedPOSHost.respond(payload, request, hostConfig, selectedProfile)
            POSHostTransportMode.TCP -> exchangeTcp(payload)
            POSHostTransportMode.REST -> exchangeRest(payload)
        }
    }

    private suspend fun exchangeTcp(payload: ByteArray): ByteArray {
        val output = tcpOutput ?: error("TCP output is not open")
        val input = tcpInput ?: error("TCP input is not open")
        val frame = POSFrameCodec.encode(payload, hostConfig.frameFormat)
        output.write(frame)
        output.flush()
        return readFrame(input, hostConfig.frameFormat, hostConfig.timeoutMs)
    }

    private fun exchangeRest(payload: ByteArray): ByteArray {
        val connection = (URL("http://${hostAddress}:${hostPort}/iso8583").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = hostConfig.timeoutMs
            readTimeout = hostConfig.timeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/octet-stream")
        }
        return try {
            connection.outputStream.use { it.write(payload) }
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            requireNotNull(stream) { "REST host returned HTTP ${connection.responseCode}" }.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    private fun readFrame(input: BufferedInputStream, format: POSFrameFormat, timeoutMs: Int): ByteArray {
        val headerSize = POSFrameCodec.headerSize(format)
        if (format == POSFrameFormat.NONE) {
            val first = input.read()
            require(first >= 0) { "host closed the connection" }
            val output = java.io.ByteArrayOutputStream()
            output.write(first)
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (input.available() > 0) output.write(input.read()) else Thread.sleep(5)
            }
            return output.toByteArray()
        }
        val header = input.readExactly(headerSize)
        val length = POSFrameCodec.readLength(header, format)
        require(length in 1..1_000_000) { "invalid host frame length $length" }
        return input.readExactly(length)
    }

    private fun validateRestEndpoint() {
        require(hostAddress.isNotBlank()) { "REST host address is blank" }
        require(hostPort in 1..65535) { "REST host port is invalid" }
    }

    private fun closeConnection() {
        runCatching { tcpInput?.close() }
        runCatching { tcpOutput?.close() }
        runCatching { socket?.close() }
        tcpInput = null
        tcpOutput = null
        socket = null
        isConnected = false
    }

    private fun nextStan(): String {
        val next = stanCounter.updateAndGet { current -> if (current >= 999999) 1 else current + 1 }
        return next.toString().padStart(6, '0')
    }

    private fun rrn(stan: String): String = (LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyMMdd")) + stan).takeLast(12)

    private fun terminalId(): String {
        val configured = isoConfig.terminalid
        return if (configured > 0) configured.toString().padStart(8, '0').takeLast(8) else "SIM" + selectedProfile.id.filter(Char::isLetterOrDigit).take(5).uppercase().padEnd(5, '0')
    }

    private fun merchantId(): String {
        val configured = isoConfig.merchantid
        return if (configured > 0) configured.toString().padStart(15, '0').takeLast(15) else "SIMULATOR MERCHANT".padEnd(15, ' ').take(15)
    }

    private fun formatExchange(message: Iso8583Message, bytes: ByteArray): String =
        Iso8583Codec.display(message) + "Raw hex: " + Iso8583Codec.hex(bytes)

    private fun log(type: LogType, message: String, details: String? = null) {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS"))
        onLog(LogEntry(timestamp, type, message, details, source = "POS/${selectedProfile.id}"))
    }

    private fun BufferedInputStream.readExactly(size: Int): ByteArray {
        val result = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val read = read(result, offset, size - offset)
            require(read >= 0) { "host closed connection after $offset of $size bytes" }
            offset += read
        }
        return result
    }
}

/** Deterministic issuer/acquirer used by the default no-hardware test mode. */
private object EmbeddedPOSHost {
    suspend fun respond(
        payload: ByteArray,
        request: POSPaymentRequest,
        config: POSHostSimulatorConfig,
        profile: POSDeviceProfile,
    ): ByteArray {
        if (config.latencyMs > 0) delay(config.latencyMs)
        val decoded = Iso8583Codec.decode(payload)
        val configuredCode = config.responseCode.filter(Char::isDigit).padStart(2, '0').takeLast(2)
        val code = when {
            request.forceDecline -> "51"
            config.approvalLimit != null && request.amountMinor > config.approvalLimit -> "51"
            !profile.supportsOffline && request.kind == POSTransactionKind.PURCHASE && configuredCode == "00" -> "00"
            else -> configuredCode
        }
        val responseFields = linkedMapOf<Int, String>()
        decoded.fields[3]?.let { responseFields[3] = it }
        decoded.fields[4]?.let { responseFields[4] = it }
        decoded.fields[7]?.let { responseFields[7] = it }
        decoded.fields[11]?.let { responseFields[11] = it }
        decoded.fields[12]?.let { responseFields[12] = it }
        decoded.fields[13]?.let { responseFields[13] = it }
        responseFields[37] = decoded.fields[37] ?: "000000000000"
        responseFields[39] = code
        if (code == "00" || code == "08" || code == "10") responseFields[38] = "A${decoded.fields[11]?.takeLast(5) ?: "00000"}"
        decoded.fields[41]?.let { responseFields[41] = it }
        decoded.fields[42]?.let { responseFields[42] = it }
        decoded.fields[49]?.let { responseFields[49] = it }
        if (config.echoEmvData) decoded.fields[55]?.let { responseFields[55] = it }
        return Iso8583Codec.encode(Iso8583Message(responseMti(decoded.mti), responseFields))
    }

    private fun responseMti(request: String): String = when (request) {
        "0100" -> "0110"
        "0200" -> "0210"
        "0220" -> "0230"
        "0400" -> "0410"
        else -> request.dropLast(1) + "1"
    }
}
