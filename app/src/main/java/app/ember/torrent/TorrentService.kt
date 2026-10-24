package app.ember.torrent

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.ember.R
import app.ember.container
import app.ember.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Keeps torrent downloads going in the background, with a progress notification. */
class TorrentService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PAUSE_ALL) {
            container.torrents.views.value.filter { it.job.state == TorrentJob.DOWNLOADING }.forEach { container.torrents.pause(it.job.hash) }
        }
        channel(this)
        val n = progressNotification("Starting downloads…", null)
        try {
            ServiceCompat.startForeground(this, PROGRESS_ID, n, if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (loop?.isActive != true) {
            loop = scope.launch {
                val engine = container.torrents
                while (true) {
                    val busy = try {
                        engine.tick()
                    } catch (e: Throwable) {
                        true
                    }
                    if (!busy) break
                    updateNotification()
                    delay(1_000)
                }
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun updateNotification() {
        val views = container.torrents.views.value.filter { it.job.active }
        if (views.isEmpty()) return
        val title = if (views.size == 1) views[0].job.name else "Downloading ${views.size} books"
        val text = when {
            container.torrents.waitingForWifi.value -> "Waiting for Wi-Fi"
            views.any { it.job.state == TorrentJob.MOVING } -> "Adding to your library…"
            else -> {
                val rate = views.sumOf { it.rateBps }
                val pct = (views.map { it.progress }.average() * 100).toInt()
                "$pct% · ${Formatter.formatShortFileSize(this, rate)}/s"
            }
        }
        val progress = (views.map { it.progress }.average() * 100).toInt()
        getSystemService(NotificationManager::class.java)?.notify(PROGRESS_ID, progressNotification(title, text, progress))
    }

    private fun progressNotification(title: String, text: String?, progress: Int = -1) =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setProgress(100, progress.coerceAtLeast(0), progress < 0)
            .setContentIntent(openDownloads(this))
            .addAction(
                0,
                "Pause all",
                PendingIntent.getService(
                    this,
                    3,
                    Intent(this, TorrentService::class.java).setAction(ACTION_PAUSE_ALL),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .build()

    /** Android 15 limits background data jobs to 6 hours a day; pause until Ember is opened again. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "torrent_downloads"
        private const val DONE_CHANNEL = "torrent_done"
        private const val PROGRESS_ID = 5120
        private const val ACTION_PAUSE_ALL = "app.ember.torrent.PAUSE_ALL"

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, TorrentService::class.java))
            } catch (e: Exception) {
                // Not allowed from the background right now; it starts next time Ember opens.
            }
        }

        private fun channel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Torrent downloads", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(DONE_CHANNEL, "Downloaded books", NotificationManager.IMPORTANCE_DEFAULT))
        }

        private fun openDownloads(context: Context): PendingIntent = PendingIntent.getActivity(
            context,
            4,
            Intent(context, MainActivity::class.java).putExtra("ember_route", "downloads").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        fun notifyDone(context: Context, name: String, folder: String) {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            channel(context)
            val open = PendingIntent.getActivity(
                context,
                5,
                Intent(context, MainActivity::class.java).putExtra("ember_route", "library").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = NotificationCompat.Builder(context, DONE_CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("Ready to listen")
                .setContentText("$name is in your library ($folder)")
                .setAutoCancel(true)
                .setContentIntent(open)
                .build()
            context.getSystemService(NotificationManager::class.java)?.notify(name.hashCode(), n)
        }
    }
}
