package com.ts10.inspector.panel

import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Rect
import com.ts10.inspector.settings.DashboardPreferences

internal data class PanelTaskSnapshot(
    val taskId: Int,
    val baseActivity: String?,
    val topActivity: String?,
    val bounds: Rect?,
    val appBounds: Rect?,
    val windowingMode: Int?,
    val configurationSignature: String?,
    val rank: Int,
) {
    val identity: String = "$taskId:${topActivity ?: baseActivity.orEmpty()}"

    fun hasWrongGeometry(expectedBounds: Rect, packageName: String): Boolean =
        (windowingMode != null && windowingMode != WINDOWING_MODE_FREEFORM) ||
            (bounds != null && !panelTaskBoundsMatch(expectedBounds, bounds, packageName))
}

private data class PanelTaskWindowGeometry(
    val bounds: Rect?,
    val appBounds: Rect?,
    val windowingMode: Int?,
    val configurationSignature: String?,
)

/**
 * Reads the same running-task list used by QFApi.getTopMostTaskId, retaining the activity and
 * WindowConfiguration details that method discards. All reflection remains optional so the
 * normal ActivityOptions fallback still works away from QF firmware.
 */
internal fun qfTopTaskSnapshot(context: Context, packageName: String): PanelTaskSnapshot? =
    runCatching {
        val qfApi = Class.forName("android.qf.os.QFApi")
        val getRunningTasks = qfApi.getMethod(
            "getRunningTasks",
            Context::class.java,
            Int::class.javaPrimitiveType,
        )
        val tasks = (getRunningTasks.invoke(null, context, QF_TASK_QUERY_LIMIT) as? List<*>)
            .orEmpty()
        tasks.withIndex()
            .asSequence()
            .mapNotNull { indexed ->
                val task = indexed.value as? ActivityManager.RunningTaskInfo
                    ?: return@mapNotNull null
                val basePackage = task.baseActivity?.packageName
                val topPackage = task.topActivity?.packageName
                if (
                    basePackage?.startsWith(packageName) != true &&
                    topPackage?.startsWith(packageName) != true
                ) {
                    return@mapNotNull null
                }
                val geometry = readTaskWindowGeometry(task)
                PanelTaskSnapshot(
                    taskId = task.taskId,
                    baseActivity = task.baseActivity?.className,
                    topActivity = task.topActivity?.className,
                    bounds = geometry.bounds,
                    appBounds = geometry.appBounds,
                    windowingMode = geometry.windowingMode,
                    configurationSignature = geometry.configurationSignature,
                    rank = indexed.index,
                )
            }
            .firstOrNull()
    }.getOrNull()

internal fun qfRunningTaskPackages(context: Context): Set<String>? = runCatching {
    val qfApi = Class.forName("android.qf.os.QFApi")
    val getRunningTasks = qfApi.getMethod(
        "getRunningTasks",
        Context::class.java,
        Int::class.javaPrimitiveType,
    )
    (getRunningTasks.invoke(null, context, QF_TASK_QUERY_LIMIT) as? List<*>)
        .orEmpty()
        .mapNotNull { task ->
            (task as? ActivityManager.RunningTaskInfo)?.let { info ->
                info.topActivity?.packageName ?: info.baseActivity?.packageName
            }
        }
        .toSet()
}.getOrNull()

internal data class RunningTaskSnapshot(
    val taskId: Int,
    val packageName: String?,
    val activityName: String?,
    val windowingMode: Int?,
    val bounds: Rect?,
)

internal fun qfRunningTaskSnapshots(context: Context): List<RunningTaskSnapshot>? = runCatching {
    val qfApi = Class.forName("android.qf.os.QFApi")
    val method = qfApi.getMethod("getRunningTasks", Context::class.java, Int::class.javaPrimitiveType)
    (method.invoke(null, context, QF_TASK_QUERY_LIMIT) as? List<*>)
        .orEmpty()
        .mapNotNull { it as? ActivityManager.RunningTaskInfo }
        .map { task ->
            val geometry = readTaskWindowGeometry(task)
            RunningTaskSnapshot(
                taskId = task.taskId,
                packageName = task.topActivity?.packageName ?: task.baseActivity?.packageName,
                activityName = task.topActivity?.className ?: task.baseActivity?.className,
                windowingMode = geometry.windowingMode,
                bounds = geometry.bounds,
            )
        }
}.getOrNull()

internal fun panelTaskDiagnostics(context: Context): List<String> =
    qfRunningTaskSnapshots(context).orEmpty().take(12).mapIndexed { rank, task ->
        "rank=$rank task=${task.taskId} package=${task.packageName ?: "--"} " +
            "activity=${task.activityName ?: "--"} mode=${task.windowingMode ?: "--"} " +
            "bounds=${task.bounds ?: "--"}"
    }

internal fun panelProcessDiagnostics(context: Context): List<String> {
    val settings = DashboardPreferences.current(context)
    val configured = buildSet {
        add(settings.navigationPackage)
        addAll(settings.panelPackages)
        settings.mediaPackage?.let(::add)
        addAll(settings.mediaPackages)
    }
    val processes = context.getSystemService(ActivityManager::class.java)
        ?.runningAppProcesses.orEmpty()
        .filter { process -> process.pkgList?.any(configured::contains) == true }
    return processes.map { process ->
        "process=${process.processName} pid=${process.pid} importance=${process.importance} " +
            "packages=${process.pkgList?.joinToString().orEmpty()}"
    }
}

private fun readTaskWindowGeometry(
    task: ActivityManager.RunningTaskInfo,
): PanelTaskWindowGeometry {
    val configuration = runCatching {
        task.javaClass.getField("configuration").get(task) as? Configuration
    }.getOrNull() ?: return parseTaskWindowGeometry(task.toString()).let { parsed ->
        PanelTaskWindowGeometry(
            bounds = parsed.first,
            appBounds = parseTaskWindowAppBounds(task.toString())?.toRect(),
            windowingMode = parsed.second,
            configurationSignature = null,
        )
    }
    val configurationText = configuration.toString()
    val parsed = parseTaskWindowGeometry(configurationText)
    val windowConfiguration = runCatching {
        val windowConfigurationField = Configuration::class.java
            .getField("windowConfiguration")
        windowConfigurationField.get(configuration)
    }.getOrNull()
    val reflectedBounds = windowConfiguration?.let { value ->
        runCatching {
            value.javaClass.getMethod("getBounds").invoke(value) as? Rect
        }.getOrNull()
    }
    val reflectedAppBounds = windowConfiguration?.let { value ->
        runCatching {
            value.javaClass.getMethod("getAppBounds").invoke(value) as? Rect
        }.getOrNull()
    }
    val reflectedMode = windowConfiguration?.let { value ->
        runCatching {
            (value.javaClass.getMethod("getWindowingMode").invoke(value) as? Number)
                ?.toInt()
        }.getOrNull()
    }
    return PanelTaskWindowGeometry(
        bounds = reflectedBounds?.let(::Rect) ?: parsed.first,
        appBounds = reflectedAppBounds?.let(::Rect) ?:
            parseTaskWindowAppBounds(configurationText)?.toRect(),
        windowingMode = reflectedMode ?: parsed.second,
        configurationSignature = stableTaskConfigurationSignature(configurationText),
    )
}

/**
 * Hidden-API enforcement can block WindowConfiguration reflection for a target-34 application.
 * Configuration.toString() is produced inside the framework and retains the same bounds/mode,
 * so it is a safe read-only fallback on the Android 10 QF build.
 */
internal fun parseTaskWindowGeometry(configuration: String): Pair<Rect?, Int?> {
    val coordinates = parseTaskWindowBounds(configuration)
    val bounds = coordinates?.let {
        Rect(it[0], it[1], it[2], it[3])
    }
    return bounds to parseTaskWindowingMode(configuration)
}

internal fun parseTaskWindowBounds(configuration: String): List<Int>? =
    WINDOW_BOUNDS_PATTERN.find(configuration)?.destructured?.let {
            (left, top, right, bottom) ->
        listOf(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())
    }

internal fun parseTaskWindowAppBounds(configuration: String): List<Int>? =
    WINDOW_APP_BOUNDS_PATTERN.find(configuration)?.destructured?.let {
            (left, top, right, bottom) ->
        listOf(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())
    }

/**
 * Configuration's `s.N`/`as.N` counters can advance after an idempotent task command. They do not
 * describe a viewport change and must not turn the non-invasive heartbeat into a resize loop.
 */
internal fun stableTaskConfigurationSignature(configuration: String): String =
    configuration
        .replace(CONFIGURATION_SEQUENCE_PATTERN, "")
        .replace(CONFIGURATION_ASSET_SEQUENCE_PATTERN, "")
        .replace(CONFIGURATION_WHITESPACE_PATTERN, " ")
        .trim()

internal fun List<Int>.toRect(): Rect = Rect(this[0], this[1], this[2], this[3])

internal fun parseTaskWindowingMode(configuration: String): Int? {
    val rawMode = WINDOW_MODE_PATTERN.find(configuration)?.groupValues?.getOrNull(1)
    return rawMode?.toIntOrNull() ?: when (rawMode?.lowercase()) {
        "fullscreen" -> WINDOWING_MODE_FULLSCREEN
        "freeform" -> WINDOWING_MODE_FREEFORM
        else -> null
    }
}

internal fun latestForegroundActivity(
    context: Context,
    packageName: String,
    sinceWallClock: Long,
): String? = runCatching {
    val usageStats = context.getSystemService(UsageStatsManager::class.java) ?: return null
    val events = usageStats.queryEvents(sinceWallClock, System.currentTimeMillis())
    val event = UsageEvents.Event()
    var latestTimestamp = Long.MIN_VALUE
    var latestActivity: String? = null
    while (events.hasNextEvent()) {
        events.getNextEvent(event)
        if (
            event.packageName == packageName &&
            event.eventType == UsageEvents.Event.ACTIVITY_RESUMED &&
            event.timeStamp >= latestTimestamp
        ) {
            latestTimestamp = event.timeStamp
            latestActivity = event.className
        }
    }
    latestActivity
}.getOrNull()

internal fun qfTaskId(context: Context, packageName: String): Int? = runCatching {
    val qfApi = Class.forName("android.qf.os.QFApi")
    val getTaskId = qfApi.getMethod(
        "getTopMostTaskId",
        Context::class.java,
        String::class.java,
    )
    (getTaskId.invoke(null, context, packageName) as? Number)
        ?.toInt()
        ?.takeIf { it >= 0 }
}.getOrNull()

private const val QF_TASK_QUERY_LIMIT = 100
private val WINDOW_BOUNDS_PATTERN = Regex(
    """mBounds=Rect\(\s*(-?\d+)\s*,\s*(-?\d+)\s*-\s*(-?\d+)\s*,\s*(-?\d+)\s*\)""",
)
private val WINDOW_APP_BOUNDS_PATTERN = Regex(
    """mAppBounds=Rect\(\s*(-?\d+)\s*,\s*(-?\d+)\s*-\s*(-?\d+)\s*,\s*(-?\d+)\s*\)""",
)
private val WINDOW_MODE_PATTERN = Regex("""mWindowingMode=([A-Za-z_-]+|-?\d+)""")
private val CONFIGURATION_SEQUENCE_PATTERN = Regex("""(?<=\s)s\.\d+(?=[\s}])""")
private val CONFIGURATION_ASSET_SEQUENCE_PATTERN = Regex("""(?<=\s)as\.\d+(?=[\s}])""")
private val CONFIGURATION_WHITESPACE_PATTERN = Regex("""\s+""")
