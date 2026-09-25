package com.vitara.routine.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.vitara.routine.R
import com.vitara.routine.alarm.RingController
import com.vitara.routine.alarm.RingRequest
import com.vitara.routine.alarm.RingService
import com.vitara.routine.alarm.RingingListener
import com.vitara.routine.data.BlockEvent
import com.vitara.routine.data.RoutineBlock
import com.vitara.routine.util.TimeText

/**
 * The full screen alarm. It is shown over the lock screen and turns the screen
 * on; the sound and vibration only stop when the user presses DISMISS. The back
 * gesture is deliberately ignored.
 */
class RingActivity : AppCompatActivity(), RingingListener {

    companion object {
        @Volatile
        var isVisible: Boolean = false
            private set

        fun intent(context: Context, block: RoutineBlock, event: BlockEvent, test: Boolean): Intent =
            RingRequest.put(
                Intent(context, RingActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_NO_USER_ACTION)
                },
                block,
                event,
                test
            )
    }

    private val handler = Handler(Looper.getMainLooper())
    private var bound = false
    private var boundBlockId: Int = Int.MIN_VALUE

    private val clockTick = object : Runnable {
        override fun run() {
            findViewById<TextView>(R.id.ringClock).text = TimeText.nowClock()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyLockScreenFlags()
        setContentView(R.layout.activity_ring)

        findViewById<View>(R.id.dismissButton).setOnClickListener { dismiss() }
        findViewById<View>(R.id.snoozeButton).setOnClickListener { snooze() }

        // The alarm can only be stopped with a deliberate Snooze or Dismiss press.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = Unit
        })
    }

    override fun onStart() {
        super.onStart()
        isVisible = true
        RingService.addListener(this)

        if (RingService.current == null) {
            val fromIntent = RingRequest.from(intent, this)
            if (fromIntent != null) {
                // Arrived through the full screen intent: sound and vibration start now
                // that the app is allowed to run in the foreground.
                bind(fromIntent)
                RingService.start(this, fromIntent)
            } else {
                finish()
            }
        }
        handler.post(clockTick)
    }

    override fun onStop() {
        isVisible = false
        handler.removeCallbacks(clockTick)
        RingService.removeListener(this)
        super.onStop()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) setIntent(intent)
        RingService.current?.let { bind(it) }
    }

    /** Called by the service whenever the ring starts, changes or ends. */
    override fun onRingingChanged(request: RingRequest?) {
        if (request == null) {
            if (bound) finish()
            return
        }
        bind(request)
    }

    private fun bind(request: RingRequest) {
        bound = true
        boundBlockId = request.block.id
        val block = request.block

        val kicker = when {
            request.test -> getString(R.string.ring_preview)
            request.event.isStart -> getString(R.string.ring_started)
            else -> getString(R.string.ring_ended)
        }
        findViewById<TextView>(R.id.ringKicker).text = kicker
        findViewById<TextView>(R.id.ringTitle).text = block.title
        findViewById<TextView>(R.id.ringMessage).text = block.message.ifBlank { kicker }
        findViewById<TextView>(R.id.ringRange).text = TimeText.window(block)
    }

    private fun dismiss() {
        val blockId = if (boundBlockId != Int.MIN_VALUE) boundBlockId else RingService.current?.block?.id
        if (blockId != null) RingController.dismiss(this, blockId)
        finish()
    }

    private fun snooze() {
        val request = RingService.current
        if (request != null) {
            RingController.snooze(this, request)
        } else {
            val blockId = if (boundBlockId != Int.MIN_VALUE) boundBlockId else null
            if (blockId != null) RingController.dismiss(this, blockId)
        }
        finish()
    }

    private fun applyLockScreenFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}
