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
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import androidx.work.await
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.odiak.medaka.theme.MedakaTheme
import net.odiak.medaka.utils.parseISODateTime
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        askNotificationPermission()

        setContent {
            val settings =
                settingsDataStore.data.collectAsState(initial = null)

            LaunchedEffect(settings) {
                if (settings.value?.password?.isEmpty() == true) {
                    openSettings()
                }
            }

            MedakaTheme {
                Scaffold(topBar = {
                    TopAppBar(title = { Text("Medaka") }, modifier = Modifier, actions = {
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
                            Main()
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()

        CoroutineScope(Dispatchers.IO).launch {
            val workManager = WorkManager.getInstance(this@MainActivity)
            val works = workManager.getWorkInfos(
                WorkQuery.Builder.fromTags(listOf(Worker.tag))
                    .addStates(listOf(WorkInfo.State.RUNNING)).build()
            )
                .await()
            if (works.isNotEmpty()) return@launch

            workManager.cancelAllWorkByTag(Worker.tag)

            workManager.enqueueUniqueWork(
                Worker.workerName,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<Worker>().build()
            )

            workManager.enqueueUniquePeriodicWork(
                PeriodicWorker.workName,
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<PeriodicWorker>(Duration.ofMinutes(15)).build()
            )
        }
    }

    private fun openSettings() {
        val intent = Intent(this, SettingsActivity::class.java)
        startActivity(intent)
    }

    private fun askNotificationPermission() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                0
            )
        }
    }

}

@Composable
fun Main() {
    val data = Worker.lastData.collectAsState(null).value

    if (data == null) {
        Text("No data")
    } else {
        val last = data.lastSG
        if (last == null) {
            Text("Empty")
        } else {
            val diff = Duration.between(last.datetime.parseISODateTime(), LocalDateTime.now())
                .coerceAtLeast(Duration.ZERO)
            val diffSec = diff.seconds
            val diffMin = diffSec / 60
            val diffHour = diffMin / 60

            val diffText = if (diffHour > 0) {
                "${diffHour}h ago"
            } else if (diffMin > 0) {
                "${diffMin}m ago"
            } else {
                "${diffSec}s ago"
            }

            Column {
                Text("${last.sg}mg/dL")
                Text("last sensor time: $diffText")

                Spacer(modifier = Modifier.padding(16.dp))

                val sgItems = data.sgs.withIndex().toList().takeLast(100).reversed()

                LazyColumn(Modifier.fillMaxWidth()) {
                    items(sgItems.size) { i ->
                        val (index, item) = sgItems[i]
                        val sgDiff = if (index > 0) {
                            (item.sg - data.sgs[index - 1].sg).signed()
                        } else {
                            ""
                        }
                        val sgDiffDiff = if (index > 1) {
                            val diff1 = item.sg - data.sgs[index - 1].sg
                            val diff2 = data.sgs[index - 1].sg - data.sgs[index - 2].sg
                            (diff1 - diff2).signed()
                        } else {
                            ""
                        }
                        val time = item.datetime.parseISODateTime()
                            .format(DateTimeFormatter.ofPattern("HH:mm"))
                        Text("$time -- ${item.sg}mg/dL $sgDiff ($sgDiffDiff)")
                    }
                }
            }
        }
    }
}

fun Int.signed() = if (this > 0) "+$this" else "$this"