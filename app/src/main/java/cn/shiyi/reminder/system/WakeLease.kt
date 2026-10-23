package cn.shiyi.reminder.system

import android.annotation.SuppressLint
import android.content.Context
import android.os.PowerManager
import java.io.Closeable

/** Holds the CPU only while a goAsync broadcast is finishing its bounded local work. */
class WakeLease private constructor(private val wakeLock: PowerManager.WakeLock) : Closeable {
    override fun close() { if (wakeLock.isHeld) wakeLock.release() }

    companion object {
        @SuppressLint("WakelockTimeout") // acquire always receives a timeout; finally also releases it.
        fun acquire(context: Context): WakeLease {
            val manager = context.getSystemService(PowerManager::class.java)
            val wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "shiyi:reminder-dispatch")
            wakeLock.setReferenceCounted(false)
            wakeLock.acquire(12_000L)
            return WakeLease(wakeLock)
        }
    }
}
