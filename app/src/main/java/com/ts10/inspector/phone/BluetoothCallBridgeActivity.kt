package com.ts10.inspector.phone

import androidx.annotation.StringRes
import com.ts10.inspector.ui.AppLocale
import com.ts10.inspector.R
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.ts10.inspector.launcher.HybridOverlayService
import com.ts10.inspector.ui.AstraDashboardTheme
import com.ts10.inspector.ui.LocalDashboardColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Routes generic tel: intents to the standard Android HEADSET profile without SIM fallback. */
class BluetoothCallBridgeActivity : ComponentActivity() {
    private var status by mutableStateOf("")
    private var completed by mutableStateOf(false)
    private var routingJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = t(R.string.call_connecting)
        render()
        route(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        route(intent)
    }

    private fun route(intent: Intent) {
        routingJob?.cancel()
        val number = intent.data?.schemeSpecificPart
            ?.filter { it.isDigit() || it in "+*#,;" }
            ?.take(MAX_NUMBER_LENGTH)
            .orEmpty()
        if (number.isBlank()) {
            routingJob = lifecycleScope.launch {
                finishWith(t(R.string.call_invalid_number))
            }
            return
        }

        routingJob = lifecycleScope.launch {
            completed = false
            status = t(R.string.call_preparing, number)
            AndroidBluetoothPhoneClient.connect(this@BluetoothCallBridgeActivity)
            val phone = withTimeoutOrNull(CONNECTION_TIMEOUT_MS) {
                AndroidBluetoothPhoneClient.state.first { it.serviceAvailable && it.connected }
            }
            if (phone?.connected != true) {
                finishWith(t(R.string.call_no_phone))
                return@launch
            }

            if (AndroidBluetoothPhoneClient.dial(number)) {
                status = t(R.string.call_voice_dial_ready, number)
                completed = true
                HybridOverlayService.showPhone(this@BluetoothCallBridgeActivity)
                delay(SUCCESS_MESSAGE_MS)
                finish()
            } else {
                finishWith(t(R.string.call_voice_dial_failed))
            }
        }
    }

    private fun t(@StringRes id: Int, vararg args: Any): String = AppLocale.string(this, id, *args)

    private suspend fun finishWith(message: String) {
        status = message
        completed = true
        delay(ERROR_MESSAGE_MS)
        finish()
    }

    private fun render() {
        setContent {
            AstraDashboardTheme {
                val colors = LocalDashboardColors.current
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(colors.background.copy(alpha = 0.94f))
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    if (!completed) CircularProgressIndicator(color = Color(0xFF278844))
                    Text(
                        text = status,
                        color = colors.primary,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 20.dp),
                    )
                }
            }
        }
    }

    private companion object {
        const val CONNECTION_TIMEOUT_MS = 5_000L
        const val SUCCESS_MESSAGE_MS = 700L
        const val ERROR_MESSAGE_MS = 2_400L
        const val MAX_NUMBER_LENGTH = 64
    }
}
