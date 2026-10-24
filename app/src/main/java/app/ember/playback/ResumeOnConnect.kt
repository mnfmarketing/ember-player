package app.ember.playback

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.ember.R
import app.ember.container
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Picks up where you left off when headphones or a car connect.
 *
 * Android only lets an app start playing by itself from the background when its battery
 * setting is Unrestricted. Otherwise Ember shows a one-tap "Resume" notification instead.
 */
object ResumeOnConnect {

    private const val CHANNEL = "resume_on_connect"
    private const val NOTIFICATION_ID = 4310
    private const val RECENT_MS = 3L * 24 * 3_600_000L
    @Volatile private var lastAttempt = 0L

    fun canStartOnItsOwn(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

    /** Called a moment after an audio device connects. */
    suspend fun onAudioDeviceConnected(context: Context, deviceName: String?) {
        val c = context.container
        val s = c.settings.current()
        if (!s.resumeOnConnect) return
        if (PlaybackStatus.isPlaying.value) return
        val now = System.currentTimeMillis()
        if (now - lastAttempt < 20_000) return
        val id = s.lastBookId ?: return
        val (title, lastPlayed) = withContext(Dispatchers.IO) {
            if (id.startsWith(PlaybackService.EPISODE_PREFIX)) {
                val ep = c.media.dao().getEpisode(id.removePrefix(PlaybackService.EPISODE_PREFIX))
                (ep?.title to (ep?.lastPlayedAt ?: 0L))
            } else {
                val b = c.db.dao().getBook(id)
                if (b == null || b.isExternal || b.status == app.ember.data.BookEntity.STATUS_FINISHED) null to 0L else b.title to b.lastPlayedAt
            }
        }
        if (title == null || now - lastPlayed > RECENT_MS) return
        lastAttempt = now
        if (canStartOnItsOwn(context)) {
            withContext(Dispatchers.Main) { c.player.playBook(id) }
        } else {
            offer(context, id, title, deviceName)
        }
    }

    /** The fallback: a notification you tap once to keep listening. */
    private fun offer(context: Context, id: String, title: String, deviceName: String?) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Resume when connected", NotificationManager.IMPORTANCE_HIGH))
        val play = PendingIntent.getBroadcast(
            context,
            7,
            Intent(context, ResumeActionReceiver::class.java).putExtra("id", id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(if (deviceName != null) "$deviceName connected" else "Headphones connected")
            .setContentText("Tap to keep listening to $title")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setTimeoutAfter(5 * 60_000L)
            .setAutoCancel(true)
            .setContentIntent(play)
            .addAction(0, "Resume", play)
            .build()
        nm.notify(NOTIFICATION_ID, n)
    }

    fun dismissOffer(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }

    /** Watches for wired and Bluetooth audio while Ember is running (no permission needed). */
    fun watch(context: Context) {
        val am = context.getSystemService(AudioManager::class.java) ?: return
        val app = context.applicationContext
        var initial = true
        am.registerAudioDeviceCallback(
            object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                    // The first call lists devices that were already connected; ignore it.
                    if (initial) {
                        initial = false
                        return
                    }
                    val headset = addedDevices.firstOrNull { it.isSink && it.type in HEADSET_TYPES } ?: return
                    app.container.appScope.launch {
                        delay(1_500)
                        onAudioDeviceConnected(app, headset.productName?.toString()?.takeIf { it.isNotBlank() })
                    }
                }
            },
            Handler(Looper.getMainLooper()),
        )
    }

    private val HEADSET_TYPES: Set<Int> = buildSet {
        add(AudioDeviceInfo.TYPE_WIRED_HEADPHONES)
        add(AudioDeviceInfo.TYPE_WIRED_HEADSET)
        add(AudioDeviceInfo.TYPE_USB_HEADSET)
        add(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
        if (Build.VERSION.SDK_INT >= 31) {
            add(AudioDeviceInfo.TYPE_BLE_HEADSET)
            add(AudioDeviceInfo.TYPE_BLE_SPEAKER)
        }
    }
}

/** Bluetooth headphones or a car connected (works even when Ember isn't running). */
class BluetoothConnectReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
        if (intent.action == BluetoothDevice.ACTION_ACL_DISCONNECTED) {
            val app = context.applicationContext
            val pending = goAsync()
            app.container.appScope.launch {
                try {
                    if (CarMode.isCar(app, device)) CarMode.onCarDisconnected(app)
                } finally {
                    pending.finish()
                }
            }
            return
        }
        if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED) return
        // Your car: offer car mode (whether or not it also resumes).
        run {
            val app = context.applicationContext
            app.container.appScope.launch { if (CarMode.isCar(app, device)) CarMode.onCarConnected(app) }
        }
        val (isAudio, name) = try {
            val major = device?.bluetoothClass?.majorDeviceClass
            (major == null || major == BluetoothClass.Device.Major.AUDIO_VIDEO) to device?.name
        } catch (e: SecurityException) {
            true to null
        }
        if (!isAudio) return
        val pending = goAsync()
        context.container.appScope.launch {
            try {
                // Give the phone a moment to route audio to the new device.
                delay(3_000)
                ResumeOnConnect.onAudioDeviceConnected(context.applicationContext, name)
            } finally {
                pending.finish()
            }
        }
    }
}

/** The "Resume" button on the notification. */
class ResumeActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("id") ?: return
        ResumeOnConnect.dismissOffer(context)
        context.container.player.playBook(id)
    }
}
