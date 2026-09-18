package `in`.aicortex.iso8583studio.posSimulator

import `in`.aicortex.iso8583studio.domain.service.posSimulatorService.Iso8583Codec
import `in`.aicortex.iso8583studio.domain.service.posSimulatorService.Iso8583Message
import `in`.aicortex.iso8583studio.domain.service.posSimulatorService.POSCardInput
import `in`.aicortex.iso8583studio.domain.service.posSimulatorService.POSDeviceProfiles
import `in`.aicortex.iso8583studio.domain.service.posSimulatorService.POSPaymentRequest
import `in`.aicortex.iso8583studio.domain.service.posSimulatorService.POSSimulatorService
import `in`.aicortex.iso8583studio.domain.service.posSimulatorService.POSTestRunner
import `in`.aicortex.iso8583studio.domain.service.posSimulatorService.POSHostTransportMode
import `in`.aicortex.iso8583studio.domain.service.posSimulatorService.POSTransactionKind
import `in`.aicortex.iso8583studio.ui.navigation.stateConfigs.pos.POSSimulatorConfig
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class POSSimulatorIntegrationTest {
    private fun config(
        responseCode: String = "00",
        deviceProfileId: String = POSDeviceProfiles.DEFAULT_ID,
    ) = POSSimulatorConfig(
        id = "test-pos",
        name = "test",
        description = "test",
        createdDate = 0,
        modifiedDate = 0,
        terminalid = 1,
        merchantid = 1,
        acquirerid = 1,
        deviceProfileId = deviceProfileId,
        hostTransportMode = POSHostTransportMode.EMBEDDED,
        embeddedResponseCode = responseCode,
        embeddedLatencyMs = 0,
    )

    @Test
    fun `codec round trip preserves primary and secondary fields`() {
        val source = Iso8583Message(
            "0200",
            mapOf(
                2 to "4761739001010010",
                3 to "000000",
                4 to "000000001250",
                11 to "100001",
                22 to "071",
                41 to "00000001",
                49 to "356",
                55 to "9F0206000000001250",
                70 to "001",
            ),
        )
        val decoded = Iso8583Codec.decode(Iso8583Codec.encode(source))
        assertEquals(source, decoded)
    }

    @Test
    fun `all built in company profiles can complete an embedded purchase`() = runBlocking {
        POSDeviceProfiles.all().forEach { profile ->
            val service = POSSimulatorService(config(deviceProfileId = profile.id))
            service.connect()
            val result = service.authorize(
                POSPaymentRequest(
                    amountMinor = 1250,
                    input = if (profile.supports(POSCardInput.CONTACTLESS_EMV)) POSCardInput.CONTACTLESS_EMV else POSCardInput.CONTACT_EMV,
                ),
            )
            assertTrue(result.approved, "${profile.vendor} ${profile.model} did not approve: ${result.error}")
            assertEquals("0210", result.response?.mti)
            assertEquals("00", result.responseCode)
            service.disconnect()
        }
    }

    @Test
    fun `forced decline is observable and updates metrics`() = runBlocking {
        val service = POSSimulatorService(config())
        service.connect()
        val result = service.sendScenario(service.scenarios.first { it.id == "decline" })
        assertTrue(!result.approved)
        assertEquals("51", result.responseCode)
        assertEquals(1, service.metrics.declined)
        service.disconnect()
    }

    @Test
    fun `scenario suite runs without hardware and produces junit`() = runBlocking {
        val service = POSSimulatorService(config())
        val suite = POSTestRunner(service).run()
        assertTrue(suite.passedAll, "scenario suite failed: ${suite.runs}")
        assertTrue(suite.toJUnitXml().contains("<testsuite"))
        assertTrue(suite.toJUnitXml().contains("purchase-contactless"))
    }

    @Test
    fun `contactless above device limit falls back to chip`() = runBlocking {
        val service = POSSimulatorService(config(deviceProfileId = "square-terminal"))
        service.connect()
        val result = service.authorize(
            POSPaymentRequest(
                amountMinor = 50_000,
                kind = POSTransactionKind.PURCHASE,
                input = POSCardInput.CONTACTLESS_EMV,
            ),
        )
        assertTrue(result.approved)
        assertEquals("051", result.request.fields[22])
        service.disconnect()
    }
}
