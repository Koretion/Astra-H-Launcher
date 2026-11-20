package com.ts10.inspector.phone

import com.ts10.inspector.ui.UiText
import com.ts10.inspector.R
import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.ts10.inspector.settings.DriverProfilePreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray

internal enum class BluetoothCallState {
    IDLE,
    DIALING,
    CONNECTED,
    RINGING,
    FAILED,
}

internal data class BluetoothPhoneState(
    val serviceAvailable: Boolean = false,
    val connected: Boolean = false,
    val deviceName: String? = null,
    val deviceAddress: String? = null,
    val deviceId: String? = null,
    val callState: BluetoothCallState = BluetoothCallState.IDLE,
    val remoteNumber: String = "",
    val remoteName: String = "",
    val contacts: List<BluetoothContact> = emptyList(),
    val status: UiText = UiText.of(R.string.bt_connecting),
)

internal data class BluetoothContact(
    val id: Int,
    val name: String,
    val number: String,
    val fullSpell: String,
    val type: Int,
    val sortLetters: String,
    val favorite: Boolean = false,
)

/**
 * Standard Android HFP/HEADSET profile observer. It intentionally has no dependency on
 * com.qf.bluetooth, hidden APIs or root. Public Android APIs expose connection state and voice
 * recognition, but not connect/disconnect, PBAP contacts or direct HFP call control.
 */
internal object AndroidBluetoothPhoneClient : BluetoothProfile.ServiceListener {
    private val mutableState = MutableStateFlow(BluetoothPhoneState())
    val state: StateFlow<BluetoothPhoneState> = mutableState.asStateFlow()

    private var applicationContext: Context? = null
    private var adapter: BluetoothAdapter? = null
    private var headset: BluetoothHeadset? = null
    private var a2dp: BluetoothA2dp? = null
    private var receiverRegistered = false

    private val connectionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED,
                BluetoothAdapter.ACTION_STATE_CHANGED,
                BluetoothDevice.ACTION_BOND_STATE_CHANGED,
                -> refresh()
            }
        }
    }

    @Synchronized
    fun connect(context: Context) {
        applicationContext = context.applicationContext
        if (!hasConnectPermission(context)) {
            mutableState.value = BluetoothPhoneState(status = UiText.of(R.string.bt_permission_needed))
            return
        }
        adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        val activeAdapter = adapter
        if (activeAdapter == null) {
            mutableState.value = BluetoothPhoneState(status = UiText.of(R.string.bt_no_adapter))
            return
        }
        registerReceiver(context.applicationContext)
        if (!activeAdapter.isEnabled) {
            mutableState.value = BluetoothPhoneState(status = UiText.of(R.string.bt_disabled))
            return
        }
        if (headset != null) {
            refresh()
            return
        }
        val requested = runCatching {
            activeAdapter.getProfileProxy(context.applicationContext, this, BluetoothProfile.HEADSET)
        }.onFailure { error -> Log.w(TAG, "Unable to request Android HEADSET profile", error) }
            .getOrDefault(false)
        runCatching {
            activeAdapter.getProfileProxy(context.applicationContext, this, BluetoothProfile.A2DP)
        }.onFailure { error -> Log.w(TAG, "Unable to request Android A2DP profile", error) }
        mutableState.update {
            it.copy(
                serviceAvailable = false,
                status = if (requested) UiText.of(R.string.bt_opening_headset) else UiText.of(R.string.bt_headset_unavailable),
            )
        }
    }

    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
        when (profile) {
            BluetoothProfile.HEADSET -> headset = proxy as? BluetoothHeadset
            BluetoothProfile.A2DP -> a2dp = proxy as? BluetoothA2dp
            else -> return
        }
        refresh()
    }

    override fun onServiceDisconnected(profile: Int) {
        when (profile) {
            BluetoothProfile.HEADSET -> {
                headset = null
                mutableState.value = BluetoothPhoneState(status = UiText.of(R.string.bt_headset_disconnected))
            }
            BluetoothProfile.A2DP -> a2dp = null
        }
    }

    @Suppress("MissingPermission")
    fun refresh() {
        val context = applicationContext ?: return
        if (!hasConnectPermission(context)) {
            mutableState.value = BluetoothPhoneState(status = UiText.of(R.string.bt_permission_needed))
            return
        }
        val proxy = headset
        if (proxy == null) {
            connect(context)
            return
        }
        val connectedDevices = runCatching { proxy.connectedDevices }
            .onFailure { error -> Log.w(TAG, "Unable to read HEADSET devices", error) }
            .getOrDefault(emptyList())
        val preferredAddress = DriverProfilePreferences.get(context).state.value.activeProfile.preferredPhoneAddress
        val device = connectedDevices.firstOrNull { normalizeBluetoothAddress(it.address) == normalizeBluetoothAddress(preferredAddress) }
            ?: connectedDevices.firstOrNull()
        if (device == null) {
            mutableState.value = BluetoothPhoneState(
                serviceAvailable = true,
                status = if (preferredAddress == null) UiText.of(R.string.bt_no_phone) else UiText.of(R.string.bt_preferred_not_connected),
            )
            return
        }
        val address = normalizeBluetoothAddress(runCatching { device.address }.getOrNull())
        val name = runCatching { device.name }.getOrNull()?.takeIf(String::isNotBlank)
        val deviceId = address?.let { "mac:$it" } ?: name?.let { "name:${it.lowercase()}" }
        val contacts = deviceId?.let(::readCachedContacts).orEmpty()
        mutableState.value = BluetoothPhoneState(
            serviceAvailable = true,
            connected = true,
            deviceName = name,
            deviceAddress = address,
            deviceId = deviceId,
            contacts = deviceId?.let { applyFavorites(contacts, it) }.orEmpty(),
            status = if (address == normalizeBluetoothAddress(preferredAddress)) {
                UiText.of(R.string.bt_preferred_connected)
            } else {
                UiText.of(R.string.bt_phone_connected)
            },
        )
    }

    fun refreshContacts() {
        refresh()
        mutableState.update {
            it.copy(status = if (it.contacts.isEmpty()) UiText.of(R.string.bt_no_pbap) else UiText.of(R.string.bt_contacts_restored))
        }
    }

    @Suppress("MissingPermission")
    fun diagnostics(): List<String> {
        val context = applicationContext
        val activeAdapter = adapter
        val permission = context != null && hasConnectPermission(context)
        if (!permission) return listOf("Permission: missing")
        val bonded = runCatching { activeAdapter?.bondedDevices.orEmpty() }.getOrDefault(emptySet())
        val headsetDevices = runCatching { headset?.connectedDevices.orEmpty() }.getOrDefault(emptyList())
        val a2dpDevices = runCatching { a2dp?.connectedDevices.orEmpty() }.getOrDefault(emptyList())
        fun BluetoothDevice.label(): String =
            "${runCatching { name }.getOrNull() ?: "--"}@${runCatching { address }.getOrNull() ?: "--"}"
        return listOf(
            "Adapter: ${if (activeAdapter == null) "unavailable" else if (activeAdapter.isEnabled) "enabled" else "disabled"}",
            "HEADSET proxy: ${headset != null}",
            "HEADSET connected: ${headsetDevices.joinToString { it.label() }.ifBlank { "--" }}",
            "A2DP proxy: ${a2dp != null}",
            "A2DP connected: ${a2dpDevices.joinToString { it.label() }.ifBlank { "--" }}",
            "Bonded: ${bonded.joinToString { it.label() }.ifBlank { "--" }}",
            "Preferred: ${DriverProfilePreferences.get(context!!).state.value.activeProfile.preferredPhoneAddress ?: "--"}",
            "Client status: ${state.value.status.english(context!!)}",
        )
    }

    fun toggleFavorite(number: String) {
        if (number.isBlank()) return
        val context = applicationContext ?: return
        val deviceId = state.value.deviceId ?: return
        val preferences = context.getSharedPreferences(FAVORITES_FILE, Context.MODE_PRIVATE)
        val key = favoritesKey(deviceId)
        val favorites = preferences.getStringSet(key, emptySet()).orEmpty().toMutableSet()
        if (!favorites.remove(number)) favorites += number
        preferences.edit().putStringSet(key, favorites).apply()
        mutableState.update { it.copy(contacts = applyFavorites(it.contacts, deviceId)) }
    }

    /** Starts the only public call-adjacent HEADSET operation: remote voice recognition. */
    @Suppress("MissingPermission")
    fun dial(number: String): Boolean {
        val device = connectedDevice() ?: return false
        val proxy = headset ?: return false
        val started = runCatching { proxy.startVoiceRecognition(device) }
            .onFailure { error -> Log.w(TAG, "Unable to start HEADSET voice recognition", error) }
            .getOrDefault(false)
        mutableState.update {
            it.copy(
                callState = if (started) BluetoothCallState.DIALING else BluetoothCallState.FAILED,
                remoteNumber = number,
                status = if (started) UiText.of(R.string.bt_voice_dial_active) else UiText.of(R.string.bt_no_direct_dial),
            )
        }
        return started
    }

    fun answer(): Boolean = unsupportedCallControl(UiText.of(R.string.bt_no_answer))

    fun reject(): Boolean = unsupportedCallControl(UiText.of(R.string.bt_no_reject))

    @Suppress("MissingPermission")
    fun hangUp(): Boolean {
        val device = connectedDevice() ?: return false
        val stopped = runCatching { headset?.stopVoiceRecognition(device) == true }.getOrDefault(false)
        mutableState.update {
            it.copy(
                callState = BluetoothCallState.IDLE,
                remoteNumber = "",
                status = if (stopped) UiText.of(R.string.bt_voice_dial_closed) else UiText.of(R.string.bt_no_hang_up),
            )
        }
        return stopped
    }

    fun openBluetoothSettings(): Boolean {
        val context = applicationContext ?: return false
        return runCatching {
            context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.getOrDefault(false)
    }

    private fun unsupportedCallControl(message: UiText): Boolean {
        mutableState.update { it.copy(callState = BluetoothCallState.FAILED, status = message) }
        return false
    }

    @Suppress("MissingPermission")
    private fun connectedDevice(): BluetoothDevice? {
        val context = applicationContext ?: return null
        if (!hasConnectPermission(context)) return null
        val address = state.value.deviceAddress
        return headset?.connectedDevices?.firstOrNull {
            normalizeBluetoothAddress(it.address) == normalizeBluetoothAddress(address)
        }
    }

    private fun applyFavorites(contacts: List<BluetoothContact>, deviceId: String): List<BluetoothContact> {
        val favorites = applicationContext
            ?.getSharedPreferences(FAVORITES_FILE, Context.MODE_PRIVATE)
            ?.getStringSet(favoritesKey(deviceId), emptySet())
            .orEmpty()
        return contacts.map { it.copy(favorite = it.number in favorites) }
            .sortedWith(compareByDescending<BluetoothContact> { it.favorite }.thenBy { it.name.lowercase() })
    }

    private fun readCachedContacts(deviceId: String): List<BluetoothContact> {
        if (!deviceId.startsWith("mac:")) return emptyList()
        val context = applicationContext ?: return emptyList()
        val raw = context.getSharedPreferences(CONTACTS_FILE, Context.MODE_PRIVATE)
            .getString("$CONTACTS_KEY_PREFIX$deviceId", null) ?: return emptyList()
        return runCatching {
            val json = JSONArray(raw)
            buildList(json.length()) {
                repeat(json.length()) { index ->
                    val item = json.getJSONObject(index)
                    val number = item.optString("number")
                    if (number.isNotBlank()) {
                        add(
                            BluetoothContact(
                                id = item.optInt("id"),
                                name = item.optString("name"),
                                number = number,
                                fullSpell = item.optString("fullSpell"),
                                type = item.optInt("type"),
                                sortLetters = item.optString("sortLetters"),
                            ),
                        )
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun registerReceiver(context: Context) {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(connectionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(connectionReceiver, filter)
        }
        receiverRegistered = true
    }

    private fun hasConnectPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun favoritesKey(deviceId: String): String = "$FAVORITES_KEY_PREFIX$deviceId"

    private const val TAG = "AndroidBluetoothPhone"
    private const val CONTACTS_FILE = "bluetooth_contacts_by_device"
    private const val CONTACTS_KEY_PREFIX = "contacts."
    private const val FAVORITES_FILE = "bluetooth_phone_favorites"
    private const val FAVORITES_KEY_PREFIX = "numbers."
}

internal fun normalizeBluetoothAddress(rawAddress: String?): String? {
    val compact = rawAddress
        ?.trim()
        ?.replace(Regex("[:\\-\\s]"), "")
        ?.takeIf { it.length == 12 && it.all { character -> character.isDigit() || character.lowercaseChar() in 'a'..'f' } }
        ?.uppercase()
        ?: return null
    return compact.chunked(2).joinToString(":")
}
