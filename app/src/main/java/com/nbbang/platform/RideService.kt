package com.nbbang.platform

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.nbbang.MainActivity
import com.nbbang.R
import com.nbbang.RideApplication
import com.nbbang.ui.driving.DrivingOverlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 내비가 앞에 있는 동안에도 차 신호를 받기 위한 포그라운드 서비스.
 * Pleos 에뮬레이터 2.1.0에서 내비가 앞으로 나오면 약 10초 뒤 백그라운드 앱이 동결됐다(First100 실측). 이 서비스가 있으면 동결되지 않는다.
 */
class RideService : Service() {
    private var overlay: DrivingOverlay? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val vm = (application as RideApplication).viewModel
        scope.launch {
            vm.ui.map { it.live?.id to it.nextTollPopup }.distinctUntilChanged().collect { (_, toll) ->
                if (toll != null && overlay?.isShowing != true) {
                    val (x, y) = DrivingOverlay.position(this@RideService)
                    overlay = DrivingOverlay(this@RideService, vm, showStatusInitially = false).also { it.show(x, y) }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = notification(this, intent?.getStringExtra(EXTRA_TEXT) ?: DEFAULT_TEXT)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }.onFailure { Log.w(TAG, "기록 서비스를 시작하지 못했어요", it) }
        if (intent?.action == DrivingOverlay.ACTION_SHOW) {
            val current = overlay
            if (current?.isShowing == true) {
                current.requestStatus()
            } else {
                overlay = DrivingOverlay(this, (application as RideApplication).viewModel).also {
                    it.show(intent.getIntExtra("x", 0), intent.getIntExtra("y", 0))
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        overlay?.close()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "RideService"
        private const val CHANNEL_ID = "ride_recording"
        private const val NOTIFICATION_ID = 200
        private const val EXTRA_TEXT = "text"
        private const val DEFAULT_TEXT = "함께 타고 출발하면 탄 구간을 기록해요"

        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, RideService::class.java)) }
                .onFailure { Log.w(TAG, "기록 서비스를 시작하지 못했어요", it) }
        }

        fun update(context: Context, text: String) {
            runCatching {
                context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(context, text))
            }.onFailure { Log.w(TAG, "알림을 바꾸지 못했어요", it) }
        }

        private fun notification(context: Context, text: String): Notification {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "함께 탄 길 기록", NotificationManager.IMPORTANCE_LOW))
            }
            val open = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            return Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("N빵~")
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .build()
        }
    }
}
