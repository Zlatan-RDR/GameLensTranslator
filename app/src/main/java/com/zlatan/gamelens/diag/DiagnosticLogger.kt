package com.zlatan.gamelens.diag

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque

object DiagnosticLogger {
    private const val MAX_LOGS = 60
    private val logs = ConcurrentLinkedDeque<String>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val _logsStateFlow = MutableStateFlow<List<String>>(emptyList())
    val logsStateFlow: StateFlow<List<String>> = _logsStateFlow.asStateFlow()

    @Synchronized
    fun log(message: String) {
        val time = timeFormat.format(Date())
        val entry = "[$time] $message"
        logs.add(entry)
        while (logs.size > MAX_LOGS) {
            logs.pollFirst()
        }
        _logsStateFlow.value = logs.toList()
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
        _logsStateFlow.value = emptyList()
    }
}
