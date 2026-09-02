package com.example.myapp

import android.app.*
import android.content.Intent
import android.os.IBinder
import android.provider.Settings
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

class CommandService : Service() {

    /** 자식 코루틴 예외가 상위로 전파되지 않도록 SupervisorJob 사용 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var deviceId: String
    private val NOTIF_ID = 1
    private val CHANNEL_ID = "agent"

    /* ────────────── Service lifecycle ────────────── */

    override fun onCreate() {
        super.onCreate()
        deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)

        if (android.os.Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "Agent",
                NotificationManager.IMPORTANCE_MIN
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(ch)
        }
    }

    /** Android 14(API 34)+ :  startForeground() 는 onStartCommand 내에서 호출 */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotif())

        if (startId == 1) {
            scope.launch { AgentClient.uploadAppList(this@CommandService, deviceId) }

            scope.launch {
                while (isActive) {
                    AgentClient.pollAndExecute(this@CommandService, deviceId)
                    delay(5_000)
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null


    private fun buildNotif(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Agent 동작 중")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setPriority(NotificationCompat.PRIORITY_MIN)  // API 24 이하 대응
            .build()
}
