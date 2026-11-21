package com.ts10.inspector.vehicle.can.profile

import android.content.Context
import android.net.Uri
import com.ts10.inspector.R
import com.ts10.inspector.diagnostics.TelemetryLog
import com.ts10.inspector.ui.UiText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

internal data class VehicleProfileEntry(
    val id: String,
    val name: String,
    val builtIn: Boolean,
    val frameCount: Int,
    val signalCount: Int,
)

internal data class VehicleProfileState(
    val profiles: List<VehicleProfileEntry> = emptyList(),
    val activeId: String = "",
    val status: UiText? = null,
)

/**
 * Perfiles CAN disponibles: el incluido en el APK y los importados por el usuario, guardados en
 * `files/vehicle_profiles/<id>.json`. La selección se conserva entre arranques.
 */
internal object VehicleProfileManager {
    private const val PREFERENCES = "vehicle_can_profile"
    private const val KEY_ACTIVE = "active_id"
    private const val MAX_PROFILE_BYTES = 512 * 1024
    private val VALID_ID = Regex("[a-z0-9_.-]{1,64}")

    private class Stored(val profile: VehicleCanProfile, val text: String)

    private val mutableState = MutableStateFlow(VehicleProfileState())
    val state: StateFlow<VehicleProfileState> = mutableState.asStateFlow()

    private val imported = LinkedHashMap<String, Stored>()
    private var started = false

    @Synchronized
    fun start(context: Context) {
        if (started) return
        started = true
        directory(context).listFiles { file -> file.extension == "json" }?.sortedBy { it.name }?.forEach { file ->
            runCatching {
                val text = file.readText()
                val profile = VehicleCanProfiles.parse(text)
                if (profile.id != VehicleCanProfiles.builtIn.id) imported[profile.id] = Stored(profile, text)
            }.onFailure { TelemetryLog.event("CAN_PROFILE", "ignored ${file.name}: ${it.message}") }
        }
        val saved = preferences(context).getString(KEY_ACTIVE, null)
        apply(saved?.takeIf { it in imported } ?: VehicleCanProfiles.builtIn.id, status = null)
    }

    @Synchronized
    fun select(context: Context, id: String) {
        start(context)
        if (id != VehicleCanProfiles.builtIn.id && id !in imported) return
        preferences(context).edit().putString(KEY_ACTIVE, id).apply()
        apply(id, status = null)
    }

    /** Valida y guarda un perfil elegido por el usuario; si es válido pasa a ser el activo. */
    fun import(context: Context, uri: Uri): UiText {
        start(context)
        val result = runCatching {
            val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                input.readNBytesCompat(MAX_PROFILE_BYTES + 1)
            } ?: error("Android could not open the file")
            require(bytes.size <= MAX_PROFILE_BYTES) { "file larger than ${MAX_PROFILE_BYTES / 1024} KB" }
            val text = bytes.toString(Charsets.UTF_8)
            val profile = VehicleCanProfiles.parse(text)
            if (profile.id == VehicleCanProfiles.builtIn.id) {
                return@runCatching UiText.of(R.string.vprofile_reserved_id, profile.id)
            }
            require(VALID_ID.matches(profile.id)) { "id must use a-z, 0-9, '_', '.' or '-'" }
            File(directory(context), "${profile.id}.json").writeText(text)
            synchronized(this) {
                imported[profile.id] = Stored(profile, text)
                preferences(context).edit().putString(KEY_ACTIVE, profile.id).apply()
            }
            TelemetryLog.event("CAN_PROFILE", "imported ${profile.id} frames=${profile.frames.size}")
            val unknown = profile.signalNames - CanSignals.all
            if (unknown.isEmpty()) {
                UiText.of(R.string.vprofile_imported_ok, profile.name)
            } else {
                UiText.of(R.string.vprofile_unknown_signals, profile.name, unknown.joinToString())
            }
        }.getOrElse { error ->
            TelemetryLog.event("CAN_PROFILE", "import rejected: ${error.message}")
            UiText.of(R.string.vprofile_import_failed, error.message ?: error.javaClass.simpleName)
        }
        synchronized(this) { apply(preferences(context).getString(KEY_ACTIVE, null) ?: VehicleCanProfiles.builtIn.id, result) }
        return result
    }

    @Synchronized
    fun delete(context: Context, id: String) {
        start(context)
        if (imported.remove(id) == null) return
        File(directory(context), "$id.json").delete()
        if (mutableState.value.activeId == id) preferences(context).edit().remove(KEY_ACTIVE).apply()
        apply(if (mutableState.value.activeId == id) VehicleCanProfiles.builtIn.id else mutableState.value.activeId, status = null)
    }

    /** Texto original del perfil, con sus notas, para editarlo o compartirlo. */
    @Synchronized
    fun exportText(id: String): String =
        imported[id]?.text ?: VehicleCanProfiles.builtInText

    fun suggestedFileName(id: String): String = "$id.json"

    private fun apply(id: String, status: UiText?) {
        val stored = imported[id]
        VehicleCanProfiles.activate(stored?.profile)
        val builtIn = VehicleCanProfiles.builtIn
        mutableState.update {
            VehicleProfileState(
                profiles = listOf(entry(builtIn, builtIn = true)) + imported.values.map { entry(it.profile, builtIn = false) },
                activeId = stored?.profile?.id ?: builtIn.id,
                status = status,
            )
        }
    }

    private fun entry(profile: VehicleCanProfile, builtIn: Boolean) = VehicleProfileEntry(
        id = profile.id,
        name = profile.name,
        builtIn = builtIn,
        frameCount = profile.frames.size,
        signalCount = profile.frames.sumOf { it.signals.size },
    )

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(8 * 1024)
        while (buffer.size() < limit) {
            val read = read(chunk, 0, minOf(chunk.size, limit - buffer.size()))
            if (read < 0) break
            buffer.write(chunk, 0, read)
        }
        return buffer.toByteArray()
    }

    private fun directory(context: Context) = File(context.filesDir, "vehicle_profiles").apply { mkdirs() }

    private fun preferences(context: Context) = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
}
