package net.odiak.medaka

import android.content.Context
import androidx.datastore.core.Serializer
import androidx.datastore.dataStore
import net.odiak.medaka.proto.Settings
import java.io.InputStream
import java.io.OutputStream

class SettingsSerializer : Serializer<Settings> {
    override val defaultValue: Settings = Settings.getDefaultInstance()

    override suspend fun readFrom(input: InputStream): Settings {
        return Settings.parseFrom(input)
    }

    override suspend fun writeTo(t: Settings, output: OutputStream) {
        t.writeTo(output)
    }
}

val Context.settingsDataStore by dataStore(
    fileName = "settings.pb",
    serializer = SettingsSerializer()
)

fun Settings.validate(): String? {
    if (username.isEmpty()) {
        return "Username must not be empty"
    }
    if (language.isEmpty()) {
        return "Language must not be empty"
    }
    if (country.isEmpty()) {
        return "Country must not be empty"
    }
    return null
}

fun Settings.isValid(): Boolean {
    return validate() == null
}