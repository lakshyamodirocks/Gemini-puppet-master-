package `in`.aasmaan.puppetmaster

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Foreground Service that keeps the local AI engine alive while the app is in use.
 * 1. Persistent low-importance notification: "Aasmaan" / "engine running · tap to open"
 * 2. Every 30s polls http://127.0.0.1:<port>/api/status with Authorization: Bearer <token>.
 *    2xx = up, anything else = down.
 * 3. On the first down result only, sends Termux RUN_COMMAND intent running `ai wake`.
 *    Until a poll succeeds, notification text becomes "engine asleep · tap to open".
 * 4. Stop action in notification stops the service.
 * 5. While engine is UP, also polls http://127.0.0.1:<port>/api/jobs to notify on job completion.
 */
class EngineService : Service() {

    private lateinit var tokenStore: TokenStore
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    private var hasSentWakeOnDown = false
    private var isPolling = false

    private val knownJobStates = mutableMapOf<Int, String>()
    private var isFirstJobsPoll = true

    private val pollRunnable = object : Runnable {
        override fun run() {
            pollStatus()
            if (isPolling) {
                mainHandler.postDelayed(this, POLL_INTERVAL_MS)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        tokenStore = TokenStore(this)
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopEngineService()
            return START_NOT_STICKY
        }

        startForegroundWithNotification("engine running · tap to open")

        if (!isPolling) {
            isPolling = true
            mainHandler.removeCallbacks(pollRunnable)
            mainHandler.post(pollRunnable)
        }

        return START_STICKY
    }

    private fun startForegroundWithNotification(contentText: String) {
        val notification = buildNotification(contentText)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(contentText: String) {
        val notification = buildNotification(contentText)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(contentText: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, EngineService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopAction = NotificationCompat.Action.Builder(
            0,
            "Stop",
            stopPendingIntent
        ).build()

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_terminal)
            .setContentTitle("Aasmaan")
            .setContentText(contentText)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(contentPendingIntent)
            .addAction(stopAction)
            .build()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Aasmaan Engine",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps local Aasmaan engine reachable on 127.0.0.1"
                setShowBadge(false)
            }
            manager.createNotificationChannel(serviceChannel)

            val jobsChannel = NotificationChannel(
                JOBS_CHANNEL_ID,
                "Aasmaan jobs",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications for completed Aasmaan background jobs"
            }
            manager.createNotificationChannel(jobsChannel)
        }
    }

    private fun pollStatus() {
        executor.execute {
            val port = tokenStore.port
            val token = tokenStore.token

            var isUp = false
            var conn: HttpURLConnection? = null
            try {
                val url = URL("http://127.0.0.1:$port/api/status")
                conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 5000
                    readTimeout = 5000
                    if (token.isNotBlank()) {
                        setRequestProperty("Authorization", "Bearer $token")
                    }
                }
                val code = conn.responseCode
                isUp = code in 200..299
            } catch (_: Exception) {
                isUp = false
            } finally {
                conn?.disconnect()
            }

            mainHandler.post {
                if (isUp) {
                    hasSentWakeOnDown = false
                    updateNotification("engine running · tap to open")
                } else {
                    updateNotification("engine asleep · tap to open")
                    if (!hasSentWakeOnDown) {
                        hasSentWakeOnDown = true
                        TermuxBridge.wakeAiInTermux(this@EngineService)
                    }
                }
            }

            // If the engine is UP, check /api/jobs within the same background execution
            if (isUp) {
                pollJobs(port, token)
            }
        }
    }

    private fun pollJobs(port: Int, token: String) {
        var jobsConn: HttpURLConnection? = null
        try {
            val jobsUrl = URL("http://127.0.0.1:$port/api/jobs")
            jobsConn = (jobsUrl.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 5000
                if (token.isNotBlank()) {
                    setRequestProperty("Authorization", "Bearer $token")
                }
            }

            if (jobsConn.responseCode in 200..299) {
                val body = jobsConn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                val jobsArray = json.optJSONArray("jobs")
                if (jobsArray != null) {
                    val currentIds = mutableSetOf<Int>()
                    val notificationsToSend = mutableListOf<Triple<Int, String, String>>()

                    for (i in 0 until jobsArray.length()) {
                        val jobObj = jobsArray.optJSONObject(i) ?: continue
                        val id = jobObj.optInt("id", -1)
                        if (id < 0) continue
                        currentIds.add(id)

                        val label = jobObj.optString("label", "")
                        val state = jobObj.optString("state", "")

                        if (!isFirstJobsPoll) {
                            val oldState = knownJobStates[id]
                            if (oldState != "done" && state == "done") {
                                notificationsToSend.add(Triple(id, "Job #$id done", label))
                            } else if (oldState != "failed" && state == "failed") {
                                notificationsToSend.add(Triple(id, "Job #$id failed", label))
                            }
                        }

                        knownJobStates[id] = state
                    }

                    if (isFirstJobsPoll) {
                        isFirstJobsPoll = false
                    }

                    if (knownJobStates.size > 50) {
                        knownJobStates.keys.retainAll(currentIds)
                    }

                    if (notificationsToSend.isNotEmpty()) {
                        mainHandler.post {
                            for ((id, title, label) in notificationsToSend) {
                                showJobNotification(id, title, label)
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Silently skip if request fails or returns non-2xx; does not affect status
        } finally {
            jobsConn?.disconnect()
        }
    }

    private fun showJobNotification(jobId: Int, title: String, label: String) {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            2000 + jobId,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, JOBS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_terminal)
            .setContentTitle(title)
            .setContentText(label)
            .setAutoCancel(true)
            .setContentIntent(contentPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(2000 + jobId, notification)
    }

    private fun stopEngineService() {
        isPolling = false
        mainHandler.removeCallbacks(pollRunnable)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    override fun onDestroy() {
        isPolling = false
        mainHandler.removeCallbacks(pollRunnable)
        executor.shutdown()
        knownJobStates.clear()
        isFirstJobsPoll = true
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_ID = "engine_service_channel"
        const val JOBS_CHANNEL_ID = "jobs"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "in.aasmaan.puppetmaster.ACTION_STOP_ENGINE"
        const val POLL_INTERVAL_MS = 30_000L
    }
}
