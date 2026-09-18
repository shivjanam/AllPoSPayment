package `in`.aicortex.iso8583studio.ui.screens.posTerminal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Card
import androidx.compose.material.Divider
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun POSIso8583TemplateTab(request: String, response: String) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("ISO 8583 exchange", style = MaterialTheme.typography.h6)
        Text("The simulator emits the same terminal request and host response for every virtual device. Raw hex is included for wire-level assertions.")
        ExchangePanel("Last request", request)
        ExchangePanel("Last response", response)
    }
}

@Composable
private fun ExchangePanel(title: String, content: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.subtitle1)
            Divider(modifier = Modifier.padding(vertical = 6.dp))
            SelectionContainer {
                Text(
                    content.ifBlank { "No exchange yet. Connect the virtual terminal and run a scenario." },
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                )
            }
        }
    }
}
