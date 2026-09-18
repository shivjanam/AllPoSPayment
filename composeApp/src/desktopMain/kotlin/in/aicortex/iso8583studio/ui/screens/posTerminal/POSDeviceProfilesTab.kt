package `in`.aicortex.iso8583studio.ui.screens.posTerminal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.Card
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import `in`.aicortex.iso8583studio.domain.service.posSimulatorService.POSDeviceMatrixResult
import `in`.aicortex.iso8583studio.domain.service.posSimulatorService.POSDeviceMatrixRunner
import `in`.aicortex.iso8583studio.domain.service.posSimulatorService.POSDeviceProfiles
import `in`.aicortex.iso8583studio.domain.service.posSimulatorService.POSDeviceState
import `in`.aicortex.iso8583studio.domain.service.posSimulatorService.POSSimulatorService
import `in`.aicortex.iso8583studio.ui.navigation.stateConfigs.pos.POSSimulatorConfig

/** Device registry screen. Profiles describe terminal behaviour; no vendor hardware is accessed. */
@Composable
fun POSDeviceProfilesTab(
    posService: POSSimulatorService,
    baseConfig: POSSimulatorConfig,
    onDeviceSelected: (String) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var suiteRunning by remember { mutableStateOf(false) }
    var matrixResult by remember { mutableStateOf<List<POSDeviceMatrixResult>?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val selected = posService.selectedProfile
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Virtual POS device", style = MaterialTheme.typography.h6)
                Text("Choose a public device family. The simulator applies its terminal capabilities and entry-mode rules while keeping payment data synthetic.")
                Row {
                    Button(onClick = { menuOpen = true }) { Text("${selected.vendor} ${selected.model}") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        POSDeviceProfiles.all().forEach { profile ->
                            DropdownMenuItem(onClick = {
                                onDeviceSelected(profile.id)
                                menuOpen = false
                            }) {
                                Column {
                                    Text("${profile.vendor} — ${profile.model}")
                                    Text(profile.id, style = MaterialTheme.typography.caption)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Text("State: ${posService.state.name}", modifier = Modifier.padding(top = 10.dp))
                }
                Button(
                    onClick = {
                        coroutineScope.launch {
                            suiteRunning = true
                            matrixResult = try {
                                POSDeviceMatrixRunner(baseConfig).run()
                            } catch (_: Throwable) {
                                null
                            } finally {
                                suiteRunning = false
                            }
                        }
                    },
                    enabled = !suiteRunning,
                ) {
                    Text(if (suiteRunning) "Running device matrix…" else "Run all device E2E tests")
                }
                matrixResult?.let { results ->
                    val passedProfiles = results.count { it.suite.passedAll }
                    Text(
                        "$passedProfiles/${results.size} device profiles passed the E2E matrix",
                        color = if (passedProfiles == results.size) MaterialTheme.colors.primary else MaterialTheme.colors.error,
                    )
                }
            }
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Terminal capabilities", style = MaterialTheme.typography.h6)
                Detail("Firmware", selected.firmware)
                Detail("Terminal type (9F35)", selected.terminalType)
                Detail("Capabilities (9F33)", selected.terminalCapabilities)
                Detail("Contactless limit", "${selected.contactlessLimitMinor} minor units")
                Detail("Offline support", if (selected.supportsOffline) "Yes" else "No")
                Detail("Payment methods", selected.supportedPaymentMethods.joinToString { it.name })
            }
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Session metrics", style = MaterialTheme.typography.h6)
                Detail("Transactions", posService.metrics.total.toString())
                Detail("Approved", posService.metrics.approved.toString())
                Detail("Declined", posService.metrics.declined.toString())
                Detail("Failed", posService.metrics.failed.toString())
                Detail("Average latency", "${posService.metrics.averageLatencyMs} ms")
            }
        }
        if (posService.state == POSDeviceState.ERROR) {
            OutlinedButton(onClick = { /* connect/disconnect remains on Transactions tab */ }) {
                Text("Select Transactions tab to reconnect")
            }
        }
    }
}

@Composable
private fun Detail(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text("$label: ", style = MaterialTheme.typography.caption)
        Text(value, style = MaterialTheme.typography.caption)
    }
}
