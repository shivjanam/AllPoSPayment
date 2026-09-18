package `in`.aicortex.iso8583studio.domain.service.posSimulatorService

import `in`.aicortex.iso8583studio.ui.navigation.stateConfigs.pos.PaymentMethod
import kotlinx.serialization.Serializable

/**
 * The transport used by a simulated terminal to reach an acquirer/host.
 *
 * EMBEDDED is deliberately the default: it gives developers a deterministic end-to-end test
 * without opening a port or connecting a physical terminal. TCP and REST are integration options
 * for a real switch or the existing Host Simulator.
 */
@Serializable
enum class POSHostTransportMode(val label: String) {
    EMBEDDED("Embedded host (no hardware)"),
    TCP("TCP/IP host"),
    REST("REST/HTTP host")
}

@Serializable
enum class POSFrameFormat(val label: String) {
    NONE("No length prefix"),
    ASCII_4("ASCII 4 digit length"),
    BINARY_2("Binary 2 byte length"),
    BCD_2("BCD 2 byte length")
}

@Serializable
data class POSHostSimulatorConfig(
    val mode: POSHostTransportMode = POSHostTransportMode.EMBEDDED,
    val address: String = "127.0.0.1",
    val port: Int = 8583,
    val frameFormat: POSFrameFormat = POSFrameFormat.NONE,
    val timeoutMs: Int = 5000,
    val responseCode: String = "00",
    val approvalLimit: Long? = null,
    val latencyMs: Long = 75,
    val echoEmvData: Boolean = true,
)

/** Common payment instruments that can be selected in the simulator UI. */
@Serializable
enum class POSCardInput(val label: String, val entryMode: String) {
    CONTACT_EMV("Contact chip", "051"),
    CONTACTLESS_EMV("Contactless", "071"),
    MAGSTRIPE("Magnetic stripe", "901"),
    MANUAL("Manual keyed", "010"),
    QR_CODE("QR / wallet", "081"),
    NFC_MOBILE("NFC mobile wallet", "071")
}

@Serializable
enum class POSTransactionKind(val label: String, val processingCode: String, val requestMti: String) {
    PURCHASE("Purchase", "000000", "0200"),
    PREAUTH("Pre-authorisation", "300000", "0100"),
    REFUND("Refund", "200000", "0200"),
    VOID("Void", "200000", "0200"),
    REVERSAL("Reversal", "000000", "0400"),
    COMPLETION("Completion", "000000", "0220")
}

/** A safe, synthetic card used by built-in scenarios. It is never a real PAN. */
@Serializable
data class POSCardData(
    val pan: String = "4111111111111111",
    val expiryYyMm: String = "2912",
    val serviceCode: String = "221",
    val cardholderName: String = "TEST CARDHOLDER",
    val aid: String = "A0000000031010",
    val applicationLabel: String = "VISA CREDIT",
    val track2Equivalent: String = "4111111111111111D29122210000000000000",
    val cvm: String = "PIN",
)

@Serializable
data class POSScenario(
    val id: String,
    val name: String,
    val description: String,
    val kind: POSTransactionKind = POSTransactionKind.PURCHASE,
    val amountMinor: Long = 1250,
    val input: POSCardInput = POSCardInput.CONTACTLESS_EMV,
    val card: POSCardData = POSCardData(),
    val forceDecline: Boolean = false,
) {
    companion object {
        fun builtIns(): List<POSScenario> = listOf(
            POSScenario(
                id = "purchase-contactless",
                name = "Contactless purchase",
                description = "Low-value tap using the synthetic Visa test card",
                amountMinor = 1250,
                input = POSCardInput.CONTACTLESS_EMV,
            ),
            POSScenario(
                id = "purchase-contact",
                name = "Chip and PIN purchase",
                description = "Contact EMV transaction with online PIN capability",
                amountMinor = 125000,
                input = POSCardInput.CONTACT_EMV,
            ),
            POSScenario(
                id = "purchase-magstripe",
                name = "Magstripe fallback",
                description = "Legacy magnetic-stripe entry path",
                amountMinor = 2500,
                input = POSCardInput.MAGSTRIPE,
            ),
            POSScenario(
                id = "wallet-purchase",
                name = "NFC wallet purchase",
                description = "Mobile wallet presented over the contactless kernel",
                amountMinor = 4999,
                input = POSCardInput.NFC_MOBILE,
                card = POSCardData(
                    pan = "5555555555554444",
                    aid = "A0000000041010",
                    applicationLabel = "MASTERCARD",
                    track2Equivalent = "5555555555554444D29122210000000000000",
                ),
            ),
            POSScenario(
                id = "refund",
                name = "Refund",
                description = "Merchant refund with a host response",
                kind = POSTransactionKind.REFUND,
                amountMinor = 1250,
                input = POSCardInput.MANUAL,
            ),
            POSScenario(
                id = "decline",
                name = "Issuer decline",
                description = "Deterministic decline path for negative testing",
                amountMinor = 9999,
                input = POSCardInput.CONTACT_EMV,
                forceDecline = true,
            ),
        )
    }
}

/** A high-level request presented by a POS application. Amounts are minor currency units. */
data class POSPaymentRequest(
    val amountMinor: Long,
    val kind: POSTransactionKind = POSTransactionKind.PURCHASE,
    val input: POSCardInput = POSCardInput.CONTACTLESS_EMV,
    val card: POSCardData = POSCardData(),
    val forceDecline: Boolean = false,
    val currency: String = "356",
)

/** Parsed ISO response returned by either the embedded host or a network host. */
data class POSAuthorizationResult(
    val approved: Boolean,
    val responseCode: String,
    val responseMessage: String,
    val request: Iso8583Message,
    val response: Iso8583Message?,
    val requestBytes: ByteArray,
    val responseBytes: ByteArray,
    val latencyMs: Long,
    val error: String? = null,
) {
    val stan: String get() = request.fields[11].orEmpty()
    val rrn: String get() = response?.fields?.get(37).orEmpty()
}

data class Iso8583Message(
    val mti: String,
    val fields: Map<Int, String>,
) {
    fun field(number: Int): String? = fields[number]
}

data class POSMetrics(
    val total: Int = 0,
    val approved: Int = 0,
    val declined: Int = 0,
    val failed: Int = 0,
    val averageLatencyMs: Long = 0,
) {
    fun record(result: POSAuthorizationResult): POSMetrics {
        val nextTotal = total + 1
        val nextLatency = ((averageLatencyMs * total) + result.latencyMs) / nextTotal
        return copy(
            total = nextTotal,
            approved = approved + if (result.approved) 1 else 0,
            declined = declined + if (!result.approved && result.error == null) 1 else 0,
            failed = failed + if (result.error != null) 1 else 0,
            averageLatencyMs = nextLatency,
        )
    }
}

sealed class POSDeviceEvent {
    data class StateChanged(val state: POSDeviceState) : POSDeviceEvent()
    data class DeviceReady(val profileId: String, val model: String) : POSDeviceEvent()
    data class RequestBuilt(val message: Iso8583Message, val bytes: ByteArray) : POSDeviceEvent()
    data class ResponseReceived(val message: Iso8583Message, val bytes: ByteArray) : POSDeviceEvent()
    data class TransactionCompleted(val result: POSAuthorizationResult) : POSDeviceEvent()
    data class Failure(val message: String, val cause: Throwable? = null) : POSDeviceEvent()
}

enum class POSDeviceState { DISCONNECTED, CONNECTING, READY, PROCESSING, ERROR }

/**
 * A terminal profile is data, not a vendor SDK. It describes the publicly visible EMV/ISO8583
 * behaviour needed by a host integration test. Vendor-specific proprietary kernels and production
 * keys are intentionally not bundled.
 */
@Serializable
data class POSDeviceProfile(
    val id: String,
    val vendor: String,
    val model: String,
    val firmware: String,
    val terminalType: String,
    val terminalCapabilities: String,
    val additionalCapabilities: String,
    val supportedPaymentMethods: Set<PaymentMethod>,
    val contactlessLimitMinor: Long,
    val supportsOffline: Boolean = true,
    val supportsReversal: Boolean = true,
    val supportsRefund: Boolean = true,
    val supportsVoid: Boolean = true,
) {
    fun supports(input: POSCardInput): Boolean = when (input) {
        POSCardInput.CONTACT_EMV -> PaymentMethod.CONTACT_EMV in supportedPaymentMethods
        POSCardInput.CONTACTLESS_EMV -> PaymentMethod.CONTACTLESS_EMV in supportedPaymentMethods
        POSCardInput.MAGSTRIPE -> PaymentMethod.MAGNETIC_STRIPE in supportedPaymentMethods
        POSCardInput.MANUAL -> true
        POSCardInput.QR_CODE -> PaymentMethod.QR_CODE in supportedPaymentMethods
        POSCardInput.NFC_MOBILE -> PaymentMethod.NFC in supportedPaymentMethods ||
            PaymentMethod.MOBILE_PAYMENT in supportedPaymentMethods
    }
}

/**
 * Built-in device families cover the most common acceptance estates. Add another device by adding a
 * profile here or loading the same shape from a future profile file; the transaction engine does
 * not contain vendor conditionals.
 */
object POSDeviceProfiles {
    const val DEFAULT_ID = "ingenico-telium"

    private val allProfiles = listOf(
        POSDeviceProfile(
            id = "ingenico-telium", vendor = "Ingenico", model = "TETRA / Telium", firmware = "RBA 1.x",
            terminalType = "22", terminalCapabilities = "E0F8C8", additionalCapabilities = "6000F0A001",
            supportedPaymentMethods = allMethods(), contactlessLimitMinor = 5000,
        ),
        POSDeviceProfile(
            id = "verifone-engage", vendor = "Verifone", model = "Engage P400", firmware = "E-Series",
            terminalType = "22", terminalCapabilities = "E0F8C8", additionalCapabilities = "600001F001",
            supportedPaymentMethods = allMethods(), contactlessLimitMinor = 5000,
        ),
        POSDeviceProfile(
            id = "pax-a-series", vendor = "PAX", model = "A920 / A-series", firmware = "PayDroid",
            terminalType = "22", terminalCapabilities = "E0F8C8", additionalCapabilities = "6000F0A001",
            supportedPaymentMethods = allMethods(), contactlessLimitMinor = 5000,
        ),
        POSDeviceProfile(
            id = "castles-saturn", vendor = "Castles Technology", model = "Saturn / VEGA", firmware = "CASTLES OS",
            terminalType = "22", terminalCapabilities = "E0F8C8", additionalCapabilities = "6000F0A001",
            supportedPaymentMethods = allMethods(), contactlessLimitMinor = 5000,
        ),
        POSDeviceProfile(
            id = "newland-n-series", vendor = "Newland", model = "N910 / N950", firmware = "Newland OS",
            terminalType = "22", terminalCapabilities = "E0F8C8", additionalCapabilities = "6000F0A001",
            supportedPaymentMethods = allMethods(), contactlessLimitMinor = 5000,
        ),
        POSDeviceProfile(
            id = "clover", vendor = "Clover", model = "Flex / Mini", firmware = "Clover Android",
            terminalType = "22", terminalCapabilities = "E0F8C8", additionalCapabilities = "6000F0A001",
            supportedPaymentMethods = allMethods() - PaymentMethod.MAGNETIC_STRIPE, contactlessLimitMinor = 5000,
        ),
        POSDeviceProfile(
            id = "square-terminal", vendor = "Square", model = "Terminal", firmware = "SquareOS",
            terminalType = "22", terminalCapabilities = "E0F8C8", additionalCapabilities = "6000F0A001",
            supportedPaymentMethods = setOf(PaymentMethod.CONTACT_EMV, PaymentMethod.CONTACTLESS_EMV, PaymentMethod.NFC, PaymentMethod.MOBILE_PAYMENT), contactlessLimitMinor = 5000,
            supportsOffline = false,
        ),
        POSDeviceProfile(
            id = "bbpos-wisepos", vendor = "BBPOS", model = "WisePOS E", firmware = "WisePOS",
            terminalType = "22", terminalCapabilities = "E0F8C8", additionalCapabilities = "6000F0A001",
            supportedPaymentMethods = setOf(PaymentMethod.CONTACT_EMV, PaymentMethod.CONTACTLESS_EMV, PaymentMethod.NFC, PaymentMethod.MOBILE_PAYMENT), contactlessLimitMinor = 5000,
            supportsOffline = false,
        ),
        POSDeviceProfile(
            id = "sunmi-p-series", vendor = "SUNMI", model = "P2 / P3", firmware = "SUNMI POS",
            terminalType = "22", terminalCapabilities = "E0F8C8", additionalCapabilities = "6000F0A001",
            supportedPaymentMethods = allMethods(), contactlessLimitMinor = 5000,
        ),
        POSDeviceProfile(
            id = "ncr-self-checkout", vendor = "NCR Voyix", model = "Self-checkout POS", firmware = "Retail POS",
            terminalType = "21", terminalCapabilities = "E0F8C8", additionalCapabilities = "6000F0A001",
            supportedPaymentMethods = setOf(PaymentMethod.CONTACT_EMV, PaymentMethod.CONTACTLESS_EMV, PaymentMethod.MAGNETIC_STRIPE, PaymentMethod.QR_CODE, PaymentMethod.NFC), contactlessLimitMinor = 5000,
        ),
    )

    fun all(): List<POSDeviceProfile> = allProfiles
    fun byId(id: String): POSDeviceProfile = allProfiles.firstOrNull { it.id == id } ?: allProfiles.first()

    private fun allMethods(): Set<PaymentMethod> = PaymentMethod.values().toSet()
}

internal fun responseDescription(code: String): String = when (code) {
    "00" -> "Approved"
    "08" -> "Approved with identification"
    "10" -> "Partial approval"
    "51" -> "Insufficient funds"
    "54" -> "Expired card"
    "55" -> "Incorrect PIN"
    "57" -> "Transaction not permitted"
    "58" -> "Terminal not permitted"
    "91" -> "Issuer unavailable"
    "96" -> "System malfunction"
    else -> "Declined (response $code)"
}
