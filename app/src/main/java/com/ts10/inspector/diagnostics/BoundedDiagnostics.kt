package com.ts10.inspector.diagnostics

/** Small immutable helpers used from Binder threads without retaining unbounded trip data. */
internal fun incrementDiagnosticCount(
    counts: Map<String, Long>,
    key: String,
): Map<String, Long> = counts + (key to (counts[key] ?: 0L) + 1L)

internal fun appendDiagnosticEvent(
    events: List<String>,
    event: String,
    limit: Int,
): List<String> = (events + event).takeLast(limit.coerceAtLeast(1))

internal fun diagnosticEventTime(): String = System.currentTimeMillis().toString()
