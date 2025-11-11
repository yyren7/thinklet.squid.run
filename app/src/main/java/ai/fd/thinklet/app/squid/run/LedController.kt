package ai.fd.thinklet.app.squid.run

import android.content.Context
import android.os.Handler
import android.os.Looper
import ai.fd.thinklet.sdk.led.LedClient

class LedController(context: Context) {
    private val ledClient = LedClient(context)
    private var isLedOn = false
    private var isBlinking = false
    private var lastLedState: Boolean? = null  // Track last LED state to avoid redundant updates
    private val handler = Handler(Looper.getMainLooper())
    private val blinkRunnable: Runnable = object : Runnable {
        override fun run() {
            if (!isBlinking) {
                return  // Safety check: stop if blinking was stopped
            }
            isLedOn = !isLedOn
            updateLedState(isLedOn)
            handler.postDelayed(this, 500)
        }
    }

    /**
     * Update LED state only if it has changed to avoid redundant service connections
     */
    private fun updateLedState(newState: Boolean) {
        if (lastLedState != newState) {
            lastLedState = newState
            ledClient.updateCameraLed(newState)
        }
    }

    fun startLedBlinking() {
        if (!isBlinking) {
            isBlinking = true
            // Start blinking immediately (don't wait for first delay)
            handler.post(blinkRunnable)
        }
    }

    fun stopLedBlinking() {
        if (isBlinking) {
            isBlinking = false
            handler.removeCallbacks(blinkRunnable)
            // Only update LED state if it needs to be turned off
            // Avoid redundant calls to prevent multiple service connections
            isLedOn = false
            updateLedState(false)
        }
    }
}

