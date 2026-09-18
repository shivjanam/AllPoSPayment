package `in`.aicortex.iso8583studio.domain.service.posSimulatorService

/** Result of a repeatable POS scenario suite. Suitable for CI adapters or a future report tab. */
data class POSScenarioRun(
    val scenario: POSScenario,
    val result: POSAuthorizationResult,
)

data class POSScenarioSuiteResult(
    val runs: List<POSScenarioRun>,
) {
    val passed: Int get() = runs.count { isPass(it) }
    val declined: Int get() = runs.count { it.result.error == null && !it.result.approved && !it.scenario.forceDecline }
    val failed: Int get() = runs.count { it.result.error != null }
    val passedAll: Boolean get() = runs.size == passed

    private fun isPass(run: POSScenarioRun): Boolean =
        run.result.error == null && (run.result.approved != run.scenario.forceDecline)

    /** Minimal JUnit XML makes a hardware-free suite consumable by CI without another dependency. */
    fun toJUnitXml(suiteName: String = "POS device simulator"): String = buildString {
        append("<testsuite name=\"").append(xml(suiteName)).append("\" tests=\"")
            .append(runs.size).append("\" failures=\"").append(runs.count { !isPass(it) }).append("\">\n")
        runs.forEach { run ->
            append("  <testcase name=\"").append(xml("${run.scenario.id}: ${run.scenario.name}")).append("\" time=\"")
                .append(run.result.latencyMs / 1000.0).append("\">")
            when {
                run.result.error != null -> append("<failure message=\"").append(xml(run.result.error)).append("/>")
                !isPass(run) -> append("<failure message=\"").append(xml(run.result.responseMessage)).append("/>")
            }
            append("</testcase>\n")
        }
        append("</testsuite>")
    }

    private fun xml(value: String?): String = value.orEmpty()
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}

/** Runs the same scenarios the UI exposes, serially, against the selected virtual device. */
class POSTestRunner(private val service: POSSimulatorService) {
    suspend fun run(
        scenarios: List<POSScenario> = service.scenarios,
        stopOnFailure: Boolean = false,
    ): POSScenarioSuiteResult {
        val connectedHere = !service.isConnected
        if (connectedHere) service.connect()
        val runs = ArrayList<POSScenarioRun>(scenarios.size)
        try {
            for (scenario in scenarios) {
                val result = service.sendScenario(scenario)
                runs += POSScenarioRun(scenario, result)
                if (stopOnFailure && result.error != null) break
            }
        } finally {
            if (connectedHere) service.disconnect()
        }
        return POSScenarioSuiteResult(runs)
    }
}

/** Runs the same deterministic cases against every bundled device profile with no network. */
data class POSDeviceMatrixResult(
    val profile: POSDeviceProfile,
    val suite: POSScenarioSuiteResult,
)

class POSDeviceMatrixRunner(private val baseConfig: `in`.aicortex.iso8583studio.ui.navigation.stateConfigs.pos.POSSimulatorConfig) {
    suspend fun run(scenarios: List<POSScenario> = POSScenario.builtIns()): List<POSDeviceMatrixResult> {
        val results = ArrayList<POSDeviceMatrixResult>(POSDeviceProfiles.all().size)
        for (profile in POSDeviceProfiles.all()) {
            val config = baseConfig.copy(
                deviceProfileId = profile.id,
                hostTransportMode = POSHostTransportMode.EMBEDDED,
            )
            val suite = POSTestRunner(POSSimulatorService(config)).run(scenarios)
            results += POSDeviceMatrixResult(profile, suite)
        }
        return results
    }
}
