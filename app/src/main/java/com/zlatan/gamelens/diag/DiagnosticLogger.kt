package com.zlatan.gamelens.diag

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque

object DiagnosticLogger {
    private const val MAX_LOGS = 250
    private val logs = ConcurrentLinkedDeque<String>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Volatile
    var isDevModeActive: Boolean = false

    private val BEARER_REGEX = Regex("Bearer\\s+[A-Za-z0-9_\\-\\.]+", RegexOption.IGNORE_CASE)
    private val SK_KEY_REGEX = Regex("sk-[A-Za-z0-9_\\-]{16,}", RegexOption.IGNORE_CASE)
    private val AUTH_HEADER_REGEX = Regex("Authorization:[^\\n,]+", RegexOption.IGNORE_CASE)

    private val _logsStateFlow = MutableStateFlow<List<String>>(emptyList())
    val logsStateFlow: StateFlow<List<String>> = _logsStateFlow.asStateFlow()

    fun sanitize(message: String): String {
        return message
            .replace(BEARER_REGEX, "Bearer [REDACTED]")
            .replace(SK_KEY_REGEX, "sk-[REDACTED]")
            .replace(AUTH_HEADER_REGEX, "Authorization: [REDACTED]")
    }

    @Synchronized
    fun log(message: String) {
        val sanitized = sanitize(message)
        val time = timeFormat.format(Date())
        val entry = "[$time] $sanitized"
        logs.add(entry)
        while (logs.size > MAX_LOGS) {
            logs.pollFirst()
        }
        // Only trigger state updates if developer UI is observing
        if (isDevModeActive || _logsStateFlow.subscriptionCount.value > 0) {
            _logsStateFlow.value = logs.toList()
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
        _logsStateFlow.value = emptyList()
    }
}
