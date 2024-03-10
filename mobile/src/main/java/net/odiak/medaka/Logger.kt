package net.odiak.medaka

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class Logger(private val context: Context) {
    companion object {
        const val LOG_FILE = "log.txt"
    }

    fun log(message: String) {
        Log.i("Logger", message)

        CoroutineScope(Dispatchers.IO).launch {
            val datetimeStr =
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
            val line = "[$datetimeStr] $message\n"

            val file = File(context.filesDir, LOG_FILE)
            if (!file.exists()) {
                file.createNewFile()
            }
            val lines = file.readLines()
            val newLines = lines.takeLast(500) + line
            file.writeText(newLines.joinToString("\n"))
        }
    }
}

val Context.logger: Logger
    get() = Logger(this)