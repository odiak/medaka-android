package net.odiak.medaka

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineBreak
import net.odiak.medaka.theme.MedakaTheme
import java.io.File

class LogActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MedakaTheme {
                Scaffold { p ->
                    val textState = remember { mutableStateOf(getLogText()) }
                    val scrollState = rememberScrollState()

                    Column(modifier = Modifier.padding(p)) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .verticalScroll(scrollState)
                                .fillMaxWidth()
                        ) {
                            Text(
                                modifier = Modifier.fillMaxWidth(),
                                style = TextStyle(lineBreak = LineBreak.Paragraph),
                                text = textState.value
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                        ) {
                            Button(onClick = { textState.value = getLogText() }) {
                                Text(text = "Reload Logs")
                            }
                            Button(onClick = { clear(); textState.value = "" }) {
                                Text(text = "Clear Logs")
                            }
                            Button(onClick = { reset() }) {
                                Text(text = "Reset Data")
                            }
                            Button(onClick = { reauth() }) {
                                Text(text = "Reauth")
                            }
                        }
                    }
                }
            }
        }
    }

    private fun getLogText(): String {
        val file = File(filesDir, Logger.LOG_FILE)
        if (!file.exists()) return ""
        return file.readText()
    }

    private fun clear() {
        val file = File(filesDir, Logger.LOG_FILE)
        if (file.exists()) file.delete()
    }

    private fun reset() {
        DataFetcher.resetAllData(this)
    }

    private fun reauth() {
        DataFetcher.forceReauth(this)
    }
}