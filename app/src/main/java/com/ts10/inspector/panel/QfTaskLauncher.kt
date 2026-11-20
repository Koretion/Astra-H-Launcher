package com.ts10.inspector.panel

import android.app.ActivityOptions
import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.graphics.Rect
import android.util.Log
import com.ts10.inspector.launcher.LauncherRuntime

internal fun clearQfLaunchParams(packageName: String) {
    runCatching {
        Class.forName("android.qf.os.QFApi")
            .getMethod("clearLaunchParamsForPackages", String::class.java)
            .invoke(null, packageName)
        LauncherRuntime.recordPanelWindowEvent("$packageName parametrosQF=limpiados")
    }.onFailure { error ->
        Log.i(TAG_EXTERNAL_APP, "QF launch-parameter reset unavailable for $packageName", error)
    }
}

internal fun startExistingTaskFullScreen(
    context: Context,
    packageName: String,
): Boolean = runCatching {
    val taskId = qfTaskId(context, packageName) ?: return false
    val options = ActivityOptions.makeBasic().apply {
        requestFullscreenWindow()
    }.toBundle().apply {
        putInt(KEY_LAUNCH_WINDOWING_MODE, WINDOWING_MODE_FULLSCREEN)
    }
    startTaskFromRecents(taskId, options)
    Log.i(TAG_EXTERNAL_APP, "Moved $packageName task $taskId to fullscreen")
    true
}.getOrElse { error ->
    Log.w(TAG_EXTERNAL_APP, "Unable to move $packageName existing task to fullscreen", error)
    false
}

internal fun startExistingTaskFromRecents(
    context: Context,
    packageName: String,
    bounds: Rect,
): Boolean {
    val taskId = qfTaskId(context, packageName) ?: return false
    return startExistingTaskFromRecents(taskId, packageName, bounds)
}

internal fun startExistingTaskFromRecents(
    taskId: Int,
    packageName: String,
    bounds: Rect,
): Boolean = runCatching {
    val options = boundedFreeformOptions(bounds)
    startTaskFromRecents(taskId, options)
    Log.i(TAG_EXTERNAL_APP, "Reapplied $packageName bounds through task $taskId: $bounds")
    true
}.getOrElse { error ->
    Log.w(TAG_EXTERNAL_APP, "QF task reactivation unavailable; using activity fallback", error)
    false
}

private fun startTaskFromRecents(taskId: Int, options: android.os.Bundle) {
    val activityTaskManager = Class.forName("android.app.ActivityTaskManager")
    val service = activityTaskManager.getDeclaredMethod("getService").apply {
        isAccessible = true
    }.invoke(null)
    val startFromRecents = service.javaClass.methods.firstOrNull {
        it.name == "startActivityFromRecents" && it.parameterTypes.size == 2
    } ?: error("startActivityFromRecents is unavailable")
    startFromRecents.invoke(service, taskId, options)
}

internal fun relaunchExistingTaskWithBounds(
    context: Context,
    packageName: String,
    activityClassName: String?,
    bounds: Rect,
): Boolean = runCatching {
    val intent = activityClassName?.let { className ->
        Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(ComponentName(packageName, className))
    } ?: context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
    intent.addFlags(
        Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
    )
    context.startActivity(intent, boundedFreeformOptions(bounds))
    Log.i(TAG_EXTERNAL_APP, "Reapplied $packageName bounds through its launch task: $bounds")
    true
}.getOrElse { error ->
    Log.w(TAG_EXTERNAL_APP, "Unable to stabilize Waze bounds", error)
    false
}

private fun boundedFreeformOptions(bounds: Rect) =
    ActivityOptions.makeBasic().apply {
        requestFreeformWindow()
        setLaunchBounds(bounds)
    }.toBundle().apply {
        putInt(KEY_LAUNCH_WINDOWING_MODE, WINDOWING_MODE_FREEFORM)
    }

/** Uses the same hidden Android 10 call as the QF launcher when this ROM permits it. */
internal fun ActivityOptions.requestFreeformWindow() {
    requestWindowingMode(WINDOWING_MODE_FREEFORM)
}

internal fun ActivityOptions.requestFullscreenWindow() {
    requestWindowingMode(WINDOWING_MODE_FULLSCREEN)
}

private fun ActivityOptions.requestWindowingMode(windowingMode: Int) {
    runCatching {
        javaClass.getMethod(
            "setLaunchWindowingMode",
            Int::class.javaPrimitiveType,
        ).invoke(this, windowingMode)
    }
}

internal const val KEY_LAUNCH_WINDOWING_MODE = "android.activity.windowingMode"
internal const val WINDOWING_MODE_FULLSCREEN = 1
internal const val WINDOWING_MODE_FREEFORM = 5
