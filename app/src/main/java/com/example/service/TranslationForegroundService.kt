package com.example.service

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
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.BSubApplication
import com.example.MainActivity
import com.example.R
import com.example.domain.model.TranslationSessionState
import com.example.domain.model.TranslationStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class TranslationForegroundService : Service() {

  private val serviceJob = SupervisorJob()
  private val serviceScope = CoroutineScope(serviceJob + Dispatchers.IO)
  private var observerJob: Job? = null
  private var wakeLock: PowerManager.WakeLock? = null

  private val coordinator: TranslationCoordinator
    get() = (application as BSubApplication).translationCoordinator

  override fun onCreate() {
    super.onCreate()
    createNotificationChannel()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_ABORT -> {
        coordinator.abortTranslation()
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        return START_NOT_STICKY
      }
      ACTION_START,
      null -> {
        acquireWakeLock()
        val initialNotification = buildProgressNotification(coordinator.sessionState.value)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            initialNotification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
          )
        } else {
          startForeground(NOTIFICATION_ID, initialNotification)
        }

        startObservingProgress()
        coordinator.startExecutionInScope(serviceScope) {
          onTranslationExecutionEnded()
        }
      }
    }
    return START_NOT_STICKY
  }

  private fun startObservingProgress() {
    observerJob?.cancel()
    observerJob =
      serviceScope.launch(Dispatchers.Main) {
        val notificationManager =
          getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        coordinator.sessionState.collectLatest { state ->
          if (state.isRunningOrCooldown) {
            notificationManager.notify(NOTIFICATION_ID, buildProgressNotification(state))
          }
        }
      }
  }

  private fun onTranslationExecutionEnded() {
    releaseWakeLock()
    observerJob?.cancel()
    val finalState = coordinator.sessionState.value
    val notificationManager =
      getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)

    if (finalState.status == TranslationStatus.COMPLETED ||
      finalState.status == TranslationStatus.FAILED ||
      finalState.status == TranslationStatus.ABORTED
    ) {
      notificationManager.notify(RESULT_NOTIFICATION_ID, buildResultNotification(finalState))
    }

    stopSelf()
  }

  private fun buildProgressNotification(state: TranslationSessionState): Notification {
    val openAppIntent =
      Intent(this, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
      }
    val openAppPendingIntent =
      PendingIntent.getActivity(
        this,
        101,
        openAppIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )

    val abortIntent =
      Intent(this, TranslationForegroundService::class.java).apply { action = ACTION_ABORT }
    val abortPendingIntent =
      PendingIntent.getService(
        this,
        102,
        abortIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )

    val fileInfo =
      if (state.totalFiles > 0) "File ${state.currentFileIndex}/${state.totalFiles}" else "Preparing"
    val batchInfo =
      if (state.totalBatchesInCurrentFile > 0) {
        "Batch ${state.currentBatchNumber}/${state.totalBatchesInCurrentFile}"
      } else {
        "Initializing"
      }
    val keyInfo = if (state.currentKeyNumber > 0) "Key #${state.currentKeyNumber}" else "Key #1"
    val attemptInfo = "Attempt ${state.currentAttempt.coerceAtLeast(1)}/${state.maxAttempts.coerceAtLeast(1)}"

    val titleText = "BSub Translator • $fileInfo"
    val bodyText = "$batchInfo • $keyInfo • $attemptInfo • ${state.activePhaseMessage}"

    val maxProgress = state.totalBlocksAllFiles.coerceAtLeast(1)
    val currentProgress = state.completedBlocksAllFiles.coerceIn(0, maxProgress)
    val indeterminate = state.totalBlocksAllFiles <= 0

    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_notification_app_icon)
      .setColor(0xFF006D5B.toInt())
      .setContentTitle(titleText)
      .setContentText(bodyText)
      .setStyle(NotificationCompat.BigTextStyle().bigText("$bodyText\n${state.currentFileName}"))
      .setContentIntent(openAppPendingIntent)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setSilent(true)
      .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
      .setProgress(maxProgress, currentProgress, indeterminate)
      .addAction(
        android.R.drawable.ic_menu_close_clear_cancel,
        getString(R.string.action_abort),
        abortPendingIntent,
      )
      .build()
  }

  private fun buildResultNotification(state: TranslationSessionState): Notification {
    val openAppIntent =
      Intent(this, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
      }
    val openAppPendingIntent =
      PendingIntent.getActivity(
        this,
        103,
        openAppIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )

    val (title, body) =
      when (state.status) {
        TranslationStatus.COMPLETED ->
          "Translation Complete" to
            "Translated ${state.completedFiles}/${state.totalFiles} file(s) to Downloads/BSub Translator/."
        TranslationStatus.ABORTED ->
          "Translation Aborted" to
            "Stopped gracefully. Any validated batches have been preserved."
        else ->
          "Translation Stopped" to state.activePhaseMessage
      }

    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_notification_app_icon)
      .setColor(0xFF006D5B.toInt())
      .setContentTitle(title)
      .setContentText(body)
      .setContentIntent(openAppPendingIntent)
      .setAutoCancel(true)
      .build()
  }

  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel =
        NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
          )
          .apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
          }
      val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
      manager.createNotificationChannel(channel)
    }
  }

  private fun acquireWakeLock() {
    if (wakeLock?.isHeld == true) return
    val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
    wakeLock =
      pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BSubTranslator::TranslationWakeLock").apply {
        setReferenceCounted(false)
        acquire(6 * 60 * 60 * 1000L) // Up to 6h safety ceiling, released immediately on completion/abort
      }
  }

  private fun releaseWakeLock() {
    runCatching {
      if (wakeLock?.isHeld == true) {
        wakeLock?.release()
      }
    }
    wakeLock = null
  }

  override fun onDestroy() {
    releaseWakeLock()
    observerJob?.cancel()
    serviceScope.cancel()
    super.onDestroy()
  }

  override fun onBind(intent: Intent?): IBinder? = null

  companion object {
    const val CHANNEL_ID = "bsub_translation_channel"
    const val NOTIFICATION_ID = 4101
    const val RESULT_NOTIFICATION_ID = 4102

    const val ACTION_START = "com.example.action.START_TRANSLATION"
    const val ACTION_ABORT = "com.example.action.ABORT_TRANSLATION"

    fun startTranslationService(context: Context) {
      val intent =
        Intent(context, TranslationForegroundService::class.java).apply {
          action = ACTION_START
        }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
      } else {
        context.startService(intent)
      }
    }

    fun abortTranslationService(context: Context) {
      val intent =
        Intent(context, TranslationForegroundService::class.java).apply {
          action = ACTION_ABORT
        }
      context.startService(intent)
    }
  }
}
