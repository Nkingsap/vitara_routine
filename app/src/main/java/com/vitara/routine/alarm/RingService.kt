package com.vitara.routine.alarm

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.vitara.routine.data.BlockEvent
import com.vitara.routine.data.RoutineBlock
import java.time.LocalTime
import java.util.concurrent.CopyOnWriteArrayList

/** Which routine, which moment, and whether it is the manual preview. */
data class RingRequest(
    val block: RoutineBlock,
    val event: BlockEvent,
    val test: Boolean,
    val snooze: Boolean = false
) {
    companion object {
        fun put(
            intent: Intent,
            block: RoutineBlock,
            event: BlockEvent,
            test: Boolean,
            snooze: Boolean = false
        ): Intent =
            AlarmScheduler.putBlock(intent, block)
                .putExtra(AlarmScheduler.EXTRA_EVENT, event.name)
                .putExtra(AlarmScheduler.EXTRA_TEST, test)
                .putExtra(AlarmScheduler.EXTRA_SNOOZE, snooze)

        fun from(intent: Intent, context: Context): RingRequest? {
            val block = AlarmScheduler.blockFromIntent(intent, context) ?: return null
            return RingRequest(
                block = block,
                event = AlarmScheduler.eventFrom(intent),
                test = intent.getBooleanExtra(AlarmScheduler.EXTRA_TEST, false),
                snooze = intent.getBooleanExtra(AlarmScheduler.EXTRA_SNOOZE, false)
            )
        }
    }
}

/** Lets the ringing screen follow the service state. */
interface RingingListener {
    fun onRingingChanged(request: RingRequest?)
}

/**
 * Plays the alarm sound on a loop and vibrates until the user presses Dismiss.
 *
 * It runs as a `mediaPlayback` foreground service with a partial wake lock, so
 * the sound + vibration continue with the screen off and the phone idle.
 */
class RingService : Service() {

    companion object {
        private const val TAG = "RingService"

        const val ACTION_RING = "com.vitara.routine.action.RING"
        const val ACTION_DISMISS = "com.vitara.routine.action.DISMISS"
        const val ACTION_SNOOZE = "com.vitara.routine.action.SNOOZE"

        /** How long one ring lasts before it goes quiet waiting for a reaction. */
        private const val RING_WINDOW_MS = 60_000L

        /** Gap before the alarm rings again when nothing happened. */
        private const val RETRY_DELAY_MS = 5 * 60_000L

        /** Up to four rings per event, then the app moves on. */
        private const val MAX_ATTEMPTS = 4

        /** Re-issue the vibration regularly: some devices drop long repeating ones. */
        private const val VIBRATION_KEEP_ALIVE_MS = 8_000L

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var current: RingRequest? = null
            private set

        private val listeners = CopyOnWriteArrayList<RingingListener>()

        fun addListener(listener: RingingListener) {
            listeners.addIfAbsent(listener)
            listener.onRingingChanged(current)
        }

        fun removeListener(listener: RingingListener) {
            listeners.remove(listener)
        }

        /** Starts (or replaces) the ring. False when Android refused a background start. */
        fun start(context: Context, request: RingRequest): Boolean {
            val intent = RingRequest.put(
                Intent(context, RingService::class.java).setAction(ACTION_RING),
                request.block,
                request.event,
                request.test
            )
            return try {
                ContextCompat.startForegroundService(context, intent)
                true
            } catch (t: Throwable) {
                // Android 12+ may refuse a foreground service start from the background.
                // The notification is already ringing/vibrating and its full screen intent
                // brings the user to the ringing screen, which starts the service again.
                Log.w(TAG, "Foreground service start refused, notification carries the alarm", t)
                false
            }
        }

        /** Stops the ring for [blockId], or the current one when null. */
        fun requestDismiss(context: Context, blockId: Int? = null) {
            if (!isRunning) return
            try {
                context.startService(
                    Intent(context, RingService::class.java)
                        .setAction(ACTION_DISMISS)
                        .putExtra(AlarmScheduler.EXTRA_BLOCK_ID, blockId ?: -1)
                )
            } catch (t: Throwable) {
                Log.w(TAG, "Dismiss refused, stopping the service", t)
                context.stopService(Intent(context, RingService::class.java))
            }
        }

        /** Snoozes the current ring and shuts the alarm down until the snooze fires. */
        fun requestSnooze(context: Context, request: RingRequest) {
            if (!isRunning) return
            try {
                context.startService(
                    RingRequest.put(
                        Intent(context, RingService::class.java).setAction(ACTION_SNOOZE),
                        request.block,
                        request.event,
                        request.test,
                        request.snooze
                    )
                )
            } catch (t: Throwable) {
                Log.w(TAG, "Snooze refused, stopping the service", t)
                context.stopService(Intent(context, RingService::class.java))
            }
        }

        private fun publish(request: RingRequest?) {
            current = request
            listeners.forEach { it.onRingingChanged(request) }
        }
    }

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val handler = Handler(Looper.getMainLooper())
    private var attempt = 0
    private var pending: RingRequest? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        AlarmNotifications.ensureChannels(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISMISS -> {
                val requested = intent.getIntExtra(AlarmScheduler.EXTRA_BLOCK_ID, -1)
                val active = current
                if (active == null || requested == -1 || active.block.id == requested) {
                    dismissCurrent()
                }
            }

            ACTION_SNOOZE -> {
                val request = RingRequest.from(intent, this) ?: current
                if (request != null) snoozeCurrent(request)
            }

            ACTION_RING -> {
                val request = RingRequest.from(intent, this)
                val active = current
                when {
                    request == null -> Unit
                    active != null && active.block.id == request.block.id && active.event == request.event -> {
                        // Already ringing this moment: just keep the foreground notification fresh.
                        ServiceCompat.startForeground(
                            this,
                            AlarmNotifications.notificationId(request.block.id, request.event),
                            AlarmNotifications.build(this, request.block, request.event, request.test, alert = false),
                            foregroundServiceType()
                        )
                    }

                    else -> ring(request)
                }
            }

            else -> if (current == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        cancelCycle()
        releaseEverything(removeNotification = true)
        isRunning = false
        publish(null)
        super.onDestroy()
    }

    /**
     * Takes over the ring: cancels whatever was ringing before and starts the new
     * request, so two routines landing on the same minute (Study ends 05:00, Rest
     * starts 05:00) never fight over the speaker.
     */
    private fun ring(request: RingRequest) {
        cancelCycle()
        if (current != null) releaseEverything(removeNotification = true)

        publish(request)
        attempt = 1
        startAttempt(request)
    }

    /**
     * One ring: sound and continuous vibration for [RING_WINDOW_MS]. When that passes
     * without a reaction the alarm goes quiet and, while the routine is still
     * running, rings again after five minutes - up to [MAX_ATTEMPTS] times.
     */
    private fun startAttempt(request: RingRequest) {
        val notification = AlarmNotifications.build(
            this,
            request.block,
            request.event,
            request.test,
            alert = attempt == 1
        )
        ServiceCompat.startForeground(
            this,
            AlarmNotifications.notificationId(request.block.id, request.event),
            notification,
            foregroundServiceType()
        )

        publish(request)
        acquireWakeLock()
        startAudio(request.block.soundUri)
        if (request.block.vibrate) {
            startVibration()
            handler.postDelayed(vibrateKeeper, VIBRATION_KEEP_ALIVE_MS)
        }
        handler.postDelayed(silenceRunnable, RING_WINDOW_MS)

        // Every attempt takes the screen back, also the ones after a five minute gap.
        if (request.block.fullScreen) {
            RingController.openRingScreen(this, request)
        }
    }

    private val silenceRunnable = Runnable { onRingWindowElapsed() }

    private val vibrateKeeper = object : Runnable {
        override fun run() {
            val request = current ?: return
            if (!request.block.vibrate || player == null) return
            startVibration()
            handler.postDelayed(this, VIBRATION_KEEP_ALIVE_MS)
        }
    }

    private fun onRingWindowElapsed() {
        val request = current ?: pending ?: return
        stopSoundAndVibration()

        if (request.event.isStart && attempt < MAX_ATTEMPTS && withinRoutineWindow(request)) {
            attempt++
            pending = request
            // Release the screen (and the ring) until the next attempt, keep the notification.
            publish(null)
            handler.postDelayed({
                if (pending === request) {
                    pending = null
                    startAttempt(request)
                }
            }, RETRY_DELAY_MS)
        } else {
            pending = null
            finishCycle()
        }
    }

    /** True while the routine's own time window has not passed yet. */
    private fun withinRoutineWindow(request: RingRequest): Boolean {
        if (request.test) return false
        val block = request.block
        val now = LocalTime.now().let { it.hour * 60 + it.minute }
        return if (block.crossesMidnight) {
            now >= block.startMinute || now < block.endMinute
        } else {
            now >= block.startMinute && now < block.endMinute
        }
    }

    /** Android 10+ expects the declared foreground service type when starting it. */
    private fun foregroundServiceType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else {
            0
        }

    /** Nothing was pressed: the alarm stays quiet and the cycle is over. */
    private fun finishCycle() {
        cancelCycle()
        releaseEverything(removeNotification = true)
        publish(null)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Stops sound + vibration. Then either rings the next request or shuts down. */
    private fun dismissCurrent() {
        finishCycle()
    }

    private fun snoozeCurrent(request: RingRequest) {
        val minutes = AlarmNotifications.SNOOZE_MINUTES
        AlarmScheduler.scheduleSnooze(this, request.block, request.event, minutes)
        cancelCycle()
        releaseEverything(removeNotification = true)
        publish(null)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun cancelCycle() {
        handler.removeCallbacks(silenceRunnable)
        handler.removeCallbacks(vibrateKeeper)
    }

    private fun releaseEverything(removeNotification: Boolean) {
        stopSoundAndVibration()
        val active = current
        if (removeNotification && active != null) {
            AlarmNotifications.cancel(this, active.block.id, active.event)
        }
    }

    private fun stopSoundAndVibration() {
        player?.let {
            try {
                if (it.isPlaying) it.stop()
            } catch (_: Throwable) {
                // already stopped
            }
            it.release()
        }
        player = null
        vibrator?.cancel()
        vibrator = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    /** Loops the routine's sound (or the system alarm sound) on the alarm stream. */
    private fun startAudio(soundUri: String?) {
        player?.release()
        player = null

        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val candidates = ArrayList<Uri>(3)
        if (!soundUri.isNullOrBlank()) {
            try {
                candidates.add(Uri.parse(soundUri))
            } catch (_: Throwable) {
                // ignore a malformed persisted uri
            }
        }
        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)?.let { candidates.add(it) }
        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)?.let { candidates.add(it) }

        for (uri in candidates) {
            try {
                val mp = MediaPlayer()
                mp.setAudioAttributes(attributes)
                mp.setDataSource(this, uri)
                mp.isLooping = true
                mp.prepare()
                mp.start()
                player = mp
                return
            } catch (t: Throwable) {
                Log.w(TAG, "Cannot play alarm sound $uri", t)
            }
        }
        Log.w(TAG, "No playable alarm sound, ringing with vibration only")
    }

    /**
     * Continuous vibration: a repeating waveform at full amplitude, re-issued every
     * few seconds so it can never stop early - it only ends on Snooze or Dismiss.
     */
    private fun startVibration() {
        val device = vibrator() ?: return
        vibrator = device
        try {
            val timings = AlarmNotifications.VIBRATION_PATTERN
            val amplitudes = timings.mapIndexed { index, _ ->
                if (index % 2 == 0) 0 else 255
            }.toIntArray()
            val effect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                VibrationEffect.createWaveform(timings, amplitudes, 0)
            } else {
                @Suppress("DEPRECATION")
                VibrationEffect.createWaveform(timings, 0)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                device.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
            } else {
                @Suppress("DEPRECATION")
                device.vibrate(
                    effect,
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Vibration failed", t)
        }
    }

    private fun vibrator(): Vibrator? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = getSystemService(VibratorManager::class.java)
            if (manager != null) return manager.defaultVibrator
        }
        @Suppress("DEPRECATION")
        return getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    /** Keeps the CPU awake so the ringtone does not stop while the screen is off. */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java) ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VitaraRoutine:alarm").apply {
            setReferenceCounted(false)
            acquire(2 * 60 * 60 * 1000L)
        }
    }

    /**
     * The alarm always plays on the alarm audio stream, so the phone's volume
     * buttons control how loud it is - the app never overrides the user's volume.
     */
}
