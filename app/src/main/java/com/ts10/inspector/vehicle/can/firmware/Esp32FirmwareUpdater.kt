package com.ts10.inspector.vehicle.can.firmware

import com.ts10.inspector.ui.LocalizedException
import com.ts10.inspector.ui.uiText
import com.ts10.inspector.ui.fail
import com.ts10.inspector.ui.UiText
import com.ts10.inspector.R
import android.content.Context
import android.net.Uri
import com.ts10.inspector.vehicle.can.Esp32CanUsbTransport
import com.ts10.inspector.vehicle.can.Esp32UsbRecord
import com.ts10.inspector.vehicle.can.extractGmlanFirmwareVersion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

internal data class Esp32FirmwareUpdateState(
    val usbConnected: Boolean = false,
    val otaSupported: Boolean? = null,
    val installedVersion: String? = null,
    val candidateVersion: String? = null,
    val candidateName: String? = null,
    val confirmationPending: Boolean = false,
    val busy: Boolean = false,
    val progressPercent: Int = 0,
    val status: UiText = UiText.of(R.string.efw_connect),
)

/** Firmware installer: OTA when available, otherwise a one-time ESP32-C3 ROM application flash. */
internal object Esp32FirmwareUpdater {
    private val mutableState = MutableStateFlow(Esp32FirmwareUpdateState())
    val state = mutableState.asStateFlow()
    private val worker = Executors.newSingleThreadExecutor()
    private val replies = LinkedBlockingQueue<Reply>()
    @Volatile private var selectedUri: Uri? = null
    // Version to confirm with OP_INFO once the board has restarted with the new image.
    @Volatile private var pendingVersionCheck: String? = null

    fun onUsbConnected() {
        mutableState.value = mutableState.value.copy(usbConnected = true, status = UiText.of(R.string.efw_checking_firmware))
        worker.execute(::probe)
    }

    fun onUsbDisconnected() {
        val current = mutableState.value
        mutableState.value = current.copy(
            usbConnected = false,
            otaSupported = null,
            confirmationPending = false,
            status = if (current.busy) UiText.of(R.string.efw_usb_lost) else UiText.of(R.string.efw_connect),
        )
    }

    fun selectFirmware(context: Context, uri: Uri) {
        if (mutableState.value.busy) return
        selectedUri = uri
        mutableState.value = mutableState.value.copy(
            busy = true,
            candidateName = uri.lastPathSegment ?: "firmware.bin",
            candidateVersion = null,
            confirmationPending = false,
            status = UiText.of(R.string.efw_checking_image),
        )
        worker.execute {
            runCatching { readAndValidateImage(context, uri) }
                .onSuccess { validated ->
                    if (selectedUri == uri) mutableState.value = mutableState.value.copy(
                        busy = false,
                        candidateVersion = validated.version,
                        status = UiText.of(R.string.efw_image_ready),
                    )
                }
                .onFailure { error ->
                    if (selectedUri == uri) mutableState.value = mutableState.value.copy(
                        busy = false,
                        candidateVersion = null,
                        status = UiText.of(R.string.efw_error, error.uiText()),
                    )
                }
        }
    }

    fun requestInstallationConfirmation() {
        val current = mutableState.value
        if (current.busy || !current.usbConnected || current.otaSupported == null || current.candidateVersion == null || selectedUri == null) return
        mutableState.value = current.copy(
            confirmationPending = true,
            status = if (current.otaSupported == true) UiText.of(R.string.efw_confirm_ota) else UiText.of(R.string.efw_confirm_first),
        )
    }

    fun cancelInstallation() {
        mutableState.value = mutableState.value.copy(
            confirmationPending = false,
            status = UiText.of(R.string.efw_cancelled),
        )
    }

    fun confirmInstallation(context: Context) {
        val uri = selectedUri ?: return
        val expectedVersion = mutableState.value.candidateVersion ?: return
        if (!mutableState.value.confirmationPending || mutableState.value.busy) return
        mutableState.value = mutableState.value.copy(
            confirmationPending = false,
            busy = true,
            progressPercent = 0,
            status = UiText.of(R.string.efw_reading_image),
        )
        worker.execute {
            runCatching {
                val validated = readAndValidateImage(context, uri)
                if (validated.version != expectedVersion) fail(R.string.efw_file_changed)
                when (mutableState.value.otaSupported) {
                    true -> installOta(validated.bytes)
                    false -> Esp32CanUsbTransport.installApplicationWithRom(validated.bytes) { percent, message ->
                        mutableState.value = mutableState.value.copy(progressPercent = percent, status = message)
                    }
                    null -> fail(R.string.efw_not_identified)
                }
                pendingVersionCheck = expectedVersion
                mutableState.value = mutableState.value.copy(
                    busy = false,
                    progressPercent = 100,
                    status = UiText.of(R.string.efw_verified),
                )
                // The ESP32-C3 native USB stays enumerated across a software restart, so Android
                // sees no detach: reopen it to read the version that is actually running.
                Esp32CanUsbTransport.reconnectAfterFirmwareRestart()
            }.onFailure { error ->
                mutableState.value = mutableState.value.copy(
                    busy = false,
                    status = UiText.of(R.string.efw_error, error.uiText()),
                )
            }
        }
    }

    private fun installOta(image: ByteArray) {
        val digest = MessageDigest.getInstance("SHA-256").digest(image)
        replies.clear()
        val begin = leInt(image.size) + digest
        if (!sendAndAwait(OP_BEGIN, begin).ok) fail(R.string.efw_begin_rejected)
        var offset = 0
        while (offset < image.size) {
            val chunk = image.copyOfRange(offset, minOf(offset + DATA_CHUNK_SIZE, image.size))
            val reply = sendAndAwait(OP_DATA, leInt(offset) + chunk)
            if (!(reply.ok && reply.received == offset + chunk.size)) {
                throw LocalizedException(if (reply.message.isBlank()) UiText.of(R.string.efw_block_rejected) else UiText.Raw(reply.message))
            }
            mutableState.value = mutableState.value.copy(
                progressPercent = ((offset + chunk.size) * 100 / image.size).coerceIn(0, 100),
                status = UiText.of(R.string.efw_updating_ota),
            )
            offset += chunk.size
        }
        val end = sendAndAwait(OP_END, byteArrayOf(PROTOCOL_VERSION.toByte()), timeoutSeconds = 20)
        if (!end.ok) throw LocalizedException(if (end.message.isBlank()) UiText.of(R.string.efw_validate_failed) else UiText.Raw(end.message))
    }

    fun acceptUsbRecord(record: Esp32UsbRecord): Boolean {
        if (record.type != RESPONSE_RECORD || record.payload.size < 11) return false
        val bytes = ByteBuffer.wrap(record.payload).order(ByteOrder.LITTLE_ENDIAN)
        val protocol = bytes.get().toInt() and 0xff
        val opcode = bytes.get().toInt() and 0xff
        val status = bytes.get().toInt() and 0xff
        val received = bytes.int
        val total = bytes.int
        val messageBytes = ByteArray(bytes.remaining()).also(bytes::get)
        replies.offer(Reply(protocol, opcode, status == 0, received, total, messageBytes.toString(Charsets.UTF_8).trimEnd('\u0000')))
        return true
    }

    private fun probe() {
        if (mutableState.value.busy) return
        replies.clear()
        val available = runCatching { sendAndAwait(OP_INFO, byteArrayOf(PROTOCOL_VERSION.toByte()), 2) }.getOrNull()
        val installed = available?.message?.toByteArray(Charsets.US_ASCII)?.let(::extractGmlanFirmwareVersion)
        val expected = pendingVersionCheck
        pendingVersionCheck = null
        mutableState.value = mutableState.value.copy(
            otaSupported = available?.ok == true && available.protocol >= PROTOCOL_VERSION,
            installedVersion = installed,
            status = when {
                expected != null && installed == expected -> UiText.of(R.string.efw_running_version, expected)
                expected != null -> UiText.of(R.string.efw_restart_needed, expected)
                available?.ok == true -> UiText.of(R.string.efw_ota_available)
                else -> UiText.of(R.string.efw_needs_usb_activation)
            },
        )
    }

    private fun readAndValidateImage(context: Context, uri: Uri): ValidatedImage {
        val image = context.contentResolver.openInputStream(uri)?.use { stream ->
            stream.readFirmwareImage()
        } ?: fail(R.string.efw_open_failed)
        if (image.size < ESP_IMAGE_HEADER_SIZE || image[0] != ESP_IMAGE_MAGIC) fail(R.string.efw_not_app_image)
        if (image.size > MAX_APP_IMAGE_SIZE) fail(R.string.efw_too_big, image.size)
        val chipId = (image[12].toInt() and 0xff) or ((image[13].toInt() and 0xff) shl 8)
        if (chipId != ESP32_C3_CHIP_ID) fail(R.string.efw_wrong_chip, chipId)
        val version = extractGmlanFirmwareVersion(image)
            ?: fail(R.string.efw_no_version)
        return ValidatedImage(image, version)
    }

    private fun sendAndAwait(opcode: Int, payload: ByteArray, timeoutSeconds: Long = 5): Reply {
        if (!Esp32CanUsbTransport.sendUpdaterRecord(opcode, payload)) fail(R.string.efw_usb_write_failed)
        val reply = replies.poll(timeoutSeconds, TimeUnit.SECONDS) ?: fail(R.string.efw_no_reply)
        if (reply.opcode != opcode) fail(R.string.efw_unexpected_reply)
        return reply
    }

    private fun leInt(value: Int): ByteArray = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

    private fun InputStream.readFirmwareImage(): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (output.size() <= MAX_APP_IMAGE_SIZE) {
            val count = read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private data class Reply(
        val protocol: Int,
        val opcode: Int,
        val ok: Boolean,
        val received: Int,
        val total: Int,
        val message: String,
    )

    private data class ValidatedImage(val bytes: ByteArray, val version: String)

    private const val PROTOCOL_VERSION = 1
    private const val OP_INFO = 0x20
    private const val OP_BEGIN = 0x21
    private const val OP_DATA = 0x22
    private const val OP_END = 0x23
    private const val RESPONSE_RECORD = 0x25
    private const val DATA_CHUNK_SIZE = 192
    private const val MAX_APP_IMAGE_SIZE = 0x140000
    private const val ESP_IMAGE_HEADER_SIZE = 24
    private const val ESP32_C3_CHIP_ID = 5
    private val ESP_IMAGE_MAGIC = 0xE9.toByte()
}
