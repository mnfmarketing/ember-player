package app.ember.playback

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.ember.R
import app.ember.container
import app.ember.ui.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Car mode: a big-button screen for driving. It opens by itself when your car's Bluetooth
 * connects (devices that say they're car audio, plus any you mark in Settings). Android
 * doesn't let apps jump to the front on their own, so if Ember isn't open you get a
 * one-tap notification instead.
 */
object CarMode {

    private const val CHANNEL = "car_mode"
    private const val NOTIFICATION_ID = 4311

    /** Ask the app to show car mode (the value changes each time). */
    val open = MutableStateFlow<Long?>(null)

    /** The car disconnected: leave car mode if it's showing. */
    val close = MutableStateFlow<Long?>(null)

    /** Whether Ember's screen is showing right now (set by MainActivity). */
    @Volatile var appVisible = false

    suspend fun isCar(context: Context, device: BluetoothDevice?): Boolean {
        if (device == null) return false
        val chosen = try {
            device.address in context.container.settings.current().carDevices.split(',').filter { it.isNotBlank() }
        } catch (e: Exception) {
            false
        }
        if (chosen) return true
        return try {
            device.bluetoothClass?.deviceClass == BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO
        } catch (e: SecurityException) {
            false
        }
    }

    suspend fun onCarConnected(context: Context) {
        if (!context.container.settings.current().carModeAuto) return
        if (appVisible) {
            open.value = System.nanoTime()
        } else {
            notify(context)
        }
    }

    fun onCarDisconnected(context: Context) {
        close.value = System.nanoTime()
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }

    private fun notify(context: Context) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Car mode", NotificationManager.IMPORTANCE_HIGH))
        val tap = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            Intent(context, MainActivity::class.java)
                .putExtra("ember_route", "car")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Your car is connected")
            .setContentText("Tap for car mode: big buttons, no fiddling.")
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .build()
        nm.notify(NOTIFICATION_ID, n)
    }
}
