package nullvp.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import nullvp.MainActivity
import java.util.concurrent.atomic.AtomicBoolean

class NullVpnService : VpnService() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var vpnInterface: ParcelFileDescriptor? = null
    private val isStopping = AtomicBoolean(false)
    private var sleptMillis = 0L

    // Screen-on broadcast receiver to detect sleep gaps > 60s and wake the core
    private val screenOnReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val slept = SystemClock.elapsedRealtime() - SystemClock.uptimeMillis()
            val gap = slept - sleptMillis
            sleptMillis = slept
            if (gap >= DEEP_SLEEP_KICK_MS) {
                // Wake kick
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        registerReceiver(screenOnReceiver, IntentFilter(Intent.ACTION_SCREEN_ON))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY

        when (action) {
            ACTION_START -> {
                val profileName = intent.getStringExtra(EXTRA_PROFILE_NAME) ?: "VPN"
                startVpn(profileName)
            }
            ACTION_STOP -> {
                stopVpn()
            }
        }
        return START_NOT_STICKY
    }

    private fun startVpn(profileName: String) {
        if (vpnInterface != null) return
        isStopping.set(false)

        val notification = createNotification("Connected to $profileName")
        startForeground(NOTIFICATION_ID, notification)

        runCatching {
            val builder = Builder()
                .setSession("NullVP")
                .setMtu(1500)
                .addAddress("10.10.10.2", 24)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")

            val pfd = builder.establish()
            vpnInterface = pfd

            // Core starts on the tun fd
        }.onFailure {
            stopVpn()
        }
    }

    private fun stopVpn() {
        if (isStopping.compareAndSet(false, true)) {
            vpnInterface?.close()
            vpnInterface = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onRevoke() {
        super.onRevoke()
        stopVpn()
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(screenOnReceiver) }
        scope.cancel()
        vpnInterface?.close()
        vpnInterface = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "NullVP Active Tunnel",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows notification when VPN is active"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("NullVP")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_START = "nullvp.service.START"
        const val ACTION_STOP = "nullvp.service.STOP"
        const val EXTRA_PROFILE_NAME = "extra_profile_name"
        private const val CHANNEL_ID = "nullvp_channel"
        private const val NOTIFICATION_ID = 1001
        private const val DEEP_SLEEP_KICK_MS = 60_000L
    }
}

