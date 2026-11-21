package com.ts10.inspector.diagnostics

import android.content.Context
import android.os.Build
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal enum class RootDetectionStatus { CHECKING, DETECTED, POSSIBLE, NOT_DETECTED }

internal data class AndroidRootState(
    val status: RootDetectionStatus = RootDetectionStatus.CHECKING,
    val summary: String = "Checking",
    val evidence: String = "--",
)

/** Passive root check: it never invokes su, requests elevation or changes the device. */
internal object AndroidRootChecker {
    private val mutableState = MutableStateFlow(AndroidRootState())
    val state: StateFlow<AndroidRootState> = mutableState.asStateFlow()
    private var started = false

    fun start(context: Context, scope: CoroutineScope) {
        if (started) return
        started = true
        val appContext = context.applicationContext
        scope.launch(Dispatchers.IO) {
            val candidates = buildSet {
                addAll(KNOWN_SU_PATHS)
                System.getenv("PATH")?.split(File.pathSeparatorChar)
                    ?.filter(String::isNotBlank)
                    ?.mapTo(this) { File(it, "su").absolutePath }
            }
            val managers = runCatching {
                appContext.packageManager.getInstalledPackages(0).map { it.packageName }
                    .filter { it in ROOT_MANAGER_PACKAGES }
            }.getOrDefault(emptyList())
            mutableState.value = evaluateRootEvidence(
                candidates.filter { File(it).isFile },
                managers,
                Build.TAGS.orEmpty(),
                Build.TYPE.orEmpty(),
            )
        }
    }

    private val KNOWN_SU_PATHS = setOf(
        "/system/bin/su", "/system/xbin/su", "/system/bin/.ext/.su", "/sbin/su",
        "/su/bin/su", "/debug_ramdisk/su", "/data/local/bin/su", "/data/local/xbin/su", "/data/local/su",
    )
    private val ROOT_MANAGER_PACKAGES = setOf(
        "com.topjohnwu.magisk", "eu.chainfire.supersu", "com.noshufou.android.su",
        "com.koushikdutta.superuser", "com.kingroot.kinguser", "com.kingo.root",
    )
}

internal fun evaluateRootEvidence(
    suPaths: List<String>,
    managerPackages: List<String>,
    buildTags: String,
    buildType: String,
): AndroidRootState {
    val managers = managerPackages.map { "manager: $it" }
    if (suPaths.isNotEmpty()) {
        return AndroidRootState(RootDetectionStatus.DETECTED, "Root detected", (suPaths.map { "su: $it" } + managers).joinToString())
    }
    // La app de Magisk puede estar instalada sin haber parcheado el arranque: sin su no hay root.
    val weak = buildList {
        if (managers.isNotEmpty()) add("${managers.joinToString()} without su binary")
        if ("test-keys" in buildTags) add("firmware test-keys")
        if (buildType == "eng" || buildType == "userdebug") add("build $buildType")
    }
    return if (weak.isNotEmpty()) {
        AndroidRootState(RootDetectionStatus.POSSIBLE, "Root possible", weak.joinToString())
    } else {
        AndroidRootState(
            RootDetectionStatus.NOT_DETECTED,
            "Root not detected",
            "No su binary or known manager; hidden root cannot be ruled out",
        )
    }
}
