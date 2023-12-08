package net.odiak.medaka

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.map
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.map
import net.odiak.medaka.common.Basal
import net.odiak.medaka.common.MinimedData
import net.odiak.medaka.common.PumpBannerState
import net.odiak.medaka.common.SensorGlucose
import net.odiak.medaka.common.parseISODateTime
import net.odiak.medaka.common.periodicFlow
import net.odiak.medaka.common.relativeTextTo
import net.odiak.medaka.common.signed
import net.odiak.medaka.theme.MedakaTheme
import java.time.LocalDateTime
import kotlin.math.sin
import kotlin.time.Duration.Companion.minutes

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        askNotificationPermission()
        Worker.checkCache(this)

        val workManager = WorkManager.getInstance(this)
        val workInfoLiveData =
            workManager.getWorkInfosForUniqueWorkLiveData(Worker.workerName).map {
                it.firstOrNull()
            }

        val observer = object : Observer<WorkInfo?> {
            override fun onChanged(value: WorkInfo?) {
                if (value == null) {
                    Worker.enqueue(workManager)
                }
                workInfoLiveData.removeObserver(this)
            }
        }
        workInfoLiveData.observe(this, observer)

        Worker.enqueuePeriodically(workManager)

        setContent {
            val settings = remember { settingsDataStore.data }.collectAsState(initial = null)

            LaunchedEffect(settings) {
                val s = settings.value ?: return@LaunchedEffect
                if (!s.isValid()) {
                    openSettings()
                }
            }

            MedakaTheme {
                Scaffold(topBar = {
                    TopAppBar(title = { Text("Medaka") }, modifier = Modifier, actions = {
                        IconButton(onClick = { Worker.enqueue(workManager) }) {
                            // reload button
                            Icon(
                                Icons.Filled.Refresh, contentDescription = "Reload data"
                            )
                        }
                        IconButton(onClick = ::openSettings) {
                            Icon(
                                Icons.Filled.Settings,
                                contentDescription = "Button to open settings"
                            )
                        }
                    })
                }) { padding ->
                    val state = rememberScrollState()

                    Surface(Modifier.padding(padding)) {
                        Column(
                            modifier = Modifier
                                .scrollable(state, Orientation.Vertical)
                                .padding(16.dp)
                        ) {
                            Button(onClick = {
                                startActivity(Intent(this@MainActivity, LoginActivity::class.java))
                            }) {
                                Text("Login")
                            }

                            val data = Worker.lastData.collectAsState(null).value
                            val workInfoState = workInfoLiveData.observeAsState().value?.state
                            if (data == null) {
                                Text("No data")
                                return@Column
                            } else {
                                val now = remember {
                                    periodicFlow(1.minutes).map { LocalDateTime.now() }
                                }.collectAsStateWithLifecycle(
                                    initialValue = LocalDateTime.now(), lifecycle = lifecycle
                                ).value
                                Main(data, workInfoState, now)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun openSettings() {
        val intent = Intent(this, SettingsActivity::class.java)
        startActivity(intent)
    }

    private fun askNotificationPermission() {
        if (ActivityCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0
            )
        }
    }

}

@Composable
fun Main(data: MinimedData, workState: WorkInfo.State?, now: LocalDateTime) {
    Column {
        val last = data.lastSG
        val datetime = last?.datetime?.parseISODateTime()
        if (last == null || datetime == null) {
            Text("Empty")
        } else {
            val relativeTime = datetime.relativeTextTo(now)

            Text(buildAnnotatedString {
                withStyle(style = SpanStyle(fontSize = 24.sp)) {
                    append(data.lastSGString)
                }
                append(" -- $relativeTime")
            })
        }

        Text(buildAnnotatedString {
            append("basal: ")
            val text = "${data.basal.activeBasalPattern} ${data.basal.basalRate}U/h"
            if (data.basal.tempBasalRate != null) {
                withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                    append(text)
                }
            } else {
                append(text)
            }

            val tempRate = data.basal.tempBasalRate
            if (tempRate != null) {
                append(" (temp ${tempRate}U/h)")
            }
        })

        data.activeInsulin?.let {
            Text("active insulin: ${it.amount}U")
        }

        for (bannerState in data.pumpBannerStates) {
            val remainingText = bannerState.timeRemaining?.let {
                val h = (it / 60).toString()
                val m = (it % 60).toString().padStart(2, '0')
                "(remaining $h:$m)"
            } ?: ""
            Text("in progress: ${bannerState.type} $remainingText")
        }

        data.timeToNextCalibrationMinutes?.let {
            val h = (it / 60).toString()
            val m = (it % 60).toString().padStart(2, '0')
            Text("next calibration: after $h:$m")
        }

        Spacer(modifier = Modifier.padding(8.dp))

        Plot(data.sgs)

        Spacer(modifier = Modifier.padding(8.dp))

        Text("fetching status: ${workState ?: "NONE"}")

        val sgItems = data.sgs.withIndex().toList().reversed()

        LazyColumn(Modifier.fillMaxWidth()) {
            items(sgItems.size) { i ->
                val (index, item) = sgItems[i]
                val sgDiff = if (index > 0) {
                    val sg = item.sgValue ?: 0
                    val sg1 = data.sgs[index - 1].sgValue ?: 0
                    (sg - sg1).signed()
                } else {
                    ""
                }
                val sgDiffDiff = if (index > 1) {
                    val sg = item.sgValue ?: 0
                    val sg1 = data.sgs[index - 1].sgValue ?: 0
                    val sg2 = data.sgs[index - 2].sgValue ?: 0
                    val diff1 = sg - sg1
                    val diff2 = sg1 - sg2
                    val v = (diff1 - diff2).signed()
                    "($v)"
                } else {
                    ""
                }
                Text("${item.timeText} -- ${item.sgText} $sgDiff $sgDiffDiff")
            }
        }
    }
}

@Preview
@Composable
fun MainPreview() {
    val sgs = (0..20).map { i ->
        val h = (i / 60).toString().padStart(2, '0')
        val m = (i % 60).toString().padStart(2, '0')
        SensorGlucose(
            datetime = "2023-08-01T$h:$m:00Z",
            sensorState = SensorGlucose.SensorStates.NO_ERROR_MESSAGE,
            sg = (sin(i.toDouble() * 0.6) * 40 + 100).toInt(),
        )
    }

    Main(
        MinimedData(
            sgs = sgs,
            lastSG = sgs.last(),
            basal = Basal("WORKDAY", 0.025, tempBasalRate = 0.05),
            pumpBannerStates = listOf(
                PumpBannerState(type = "FOO", timeRemaining = 100),
                PumpBannerState(type = "BAR")
            )
        ),
        null,
        LocalDateTime.of(2023, 8, 1, 0, 12)
    )
}


