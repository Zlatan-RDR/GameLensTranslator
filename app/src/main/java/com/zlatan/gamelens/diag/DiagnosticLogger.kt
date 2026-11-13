package com.zlatan.gamelens.diag

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque

object DiagnosticLogger {
    private const val MAX_LOGS = 60
    private val logs = ConcurrentLinkedDeque<String>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun log(message: String) {
        val time = timeFormat.format(Date())
        val entry = "[$time] $message"
        logs.add(entry)
        while (logs.size > MAX_LOGS) {
            logs.pollFirst()
        }
    }

    fun getLogs(): List<String> = logs.toList()

    fun getFormattedLogs(): String {
        val list = getLogs()
        if (list.isEmpty()) return "لا توجد سجلات بعد (No logs recorded yet)"
        return list.joinToString("\n")
    }

    @Synchronized
    fun clear() {
        logs.clear()
    }
}
