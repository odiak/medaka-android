package net.odiak.medaka.presentation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Text
import net.odiak.medaka.ListeningService
import net.odiak.medaka.presentation.theme.MedakaTheme
import net.odiak.medaka.utils.signed
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val dataFlow = ListeningService.lastData

        setContent {
            val data = dataFlow.collectAsState(null)

            val sgs = data.value?.sgs ?: emptyList()
            val diff = if (sgs.isNotEmpty()) {
                (sgs[sgs.size - 1].sg - sgs[sgs.size - 2].sg).signed()
            } else {
                ""
            }
            val time =
                data.value?.lastSGDateTime?.format(DateTimeFormatter.ofPattern("HH:mm")) ?: ""

            MedakaTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("${data.value?.lastSG?.sg}mg/dL $diff")
                    Text("at $time")
                }
            }
        }
    }
}
