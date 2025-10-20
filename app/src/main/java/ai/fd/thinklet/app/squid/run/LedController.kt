package ai.fd.thinklet.app.squid.run

import android.content.Context
import android.os.Handler
import android.os.Looper
import ai.fd.thinklet.sdk.led.LedClient

class LedController(context: Context) {
    private val ledClient = LedClient(context)
    private var isLedOn = false
    private var isBlinking = false
    private val handler = Handler(Looper.getMainLooper())
    private val blinkRunnable: Runnable = object : Runnable {
        override fun run() {
            isLedOn = !isLedOn
            ledClient.updateCameraLed(isLedOn)
            handler.postDelayed(this, 500)
        }
    }

    fun startLedBlinking() {
        if (!isBlinking) {
            isBlinking = true
            handler.post(blinkRunnable)
        }
    }

    fun stopLedBlinking() {
        if (isBlinking) {
            isBlinking = false
            handler.removeCallbacks(blinkRunnable)
            isLedOn = false
            ledClient.updateCameraLed(false)
            
            // 延迟再次确保LED已关闭（防止硬件驱动延迟导致的问题）
            handler.postDelayed({
                if (!isBlinking) {  // 确认没有被重新启动
                    ledClient.updateCameraLed(false)
                }
            }, 100)
        }
    }
}

