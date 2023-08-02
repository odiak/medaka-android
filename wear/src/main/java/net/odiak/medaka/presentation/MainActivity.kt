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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ListeningService.checkData(this)
        val dataFlow = ListeningService.lastData

        setContent {
            val data = dataFlow.collectAsState(null)

            MedakaTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp),
                    verticalArrangement = Arrangement.Center
                ) {
                    val d = data.value
                    if (d == null) {
                        Text("No data")
                    } else {
                        Text("${d.lastSG} ${d.lastSGDiff}")
                        Text("at ${d.lastSGTime}")
                    }
                }
            }
        }
    }
}
