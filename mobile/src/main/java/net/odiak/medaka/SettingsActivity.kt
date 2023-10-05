package net.odiak.medaka

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.odiak.medaka.proto.Settings
import net.odiak.medaka.theme.MedakaTheme

class SettingsActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val settings = settingsDataStore.data.collectAsState(initial = null)

            MedakaTheme {
                Scaffold(topBar = {
                    TopAppBar(
                        title = { Text("Settings") },
                        modifier = Modifier
                    )
                }) { padding ->
                    Box(Modifier.padding(padding)) {
                        settings.value?.let {
                            Settings(it, onSave = { newSettings ->
                                val error = newSettings.validate()
                                if (error != null) {
                                    Toast.makeText(this@SettingsActivity, error, Toast.LENGTH_SHORT)
                                        .show()
                                    return@Settings
                                }

                                CoroutineScope(Dispatchers.IO).launch {
                                    settingsDataStore.updateData { newSettings }
                                    val intent =
                                        Intent(this@SettingsActivity, MainActivity::class.java)
                                    startActivity(intent)
                                    finish()
                                }
                            })
                        } ?: CircularProgressIndicator()
                    }
                }
            }
        }
    }
}

@Composable
fun Settings(settings: Settings, onSave: (Settings) -> Unit) {
    val username = remember { mutableStateOf(settings.username) }
    val country = remember { mutableStateOf(settings.country) }
    val language = remember { mutableStateOf(settings.language) }

    Column {
        TextField(
            modifier = Modifier.fillMaxWidth(),
            value = username.value,
            onValueChange = { username.value = it },
            label = { Text("Username") },
            singleLine = true
        )

        Spacer(modifier = Modifier.padding(8.dp))

        TextField(
            modifier = Modifier.fillMaxWidth(),
            value = country.value,
            onValueChange = { country.value = it },
            label = { Text("Country code") },
            singleLine = true,
            placeholder = { Text("us, jp, etc.") }
        )

        Spacer(modifier = Modifier.padding(8.dp))

        TextField(
            modifier = Modifier.fillMaxWidth(),
            value = language.value,
            onValueChange = { language.value = it },
            label = { Text("Language code") },
            singleLine = true,
            placeholder = { Text("en, ja, etc.") }
        )

        Spacer(modifier = Modifier.weight(1f))

        Spacer(modifier = Modifier.padding(12.dp))

        Button(
            modifier = Modifier.align(Alignment.CenterHorizontally),
            onClick = {
                onSave(
                    settings.toBuilder().also {
                        it.username = username.value
                        it.country = country.value
                        it.language = language.value
                    }.build()
                )
            }
        ) {
            Text("Save")
        }

        Spacer(modifier = Modifier.padding(12.dp))
    }
}

@Preview
@Composable
fun SettingsPreview() {
    Settings(
        Settings.newBuilder()
            .setUsername("username")
            .setCountry("us")
            .setLanguage("en")
            .build()
    ) {}
}
