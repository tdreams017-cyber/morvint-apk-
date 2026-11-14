package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.example.MainActivity
import com.example.nlp.AgentExecutionState
import com.example.nlp.MorventAgentHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Morvent Floating Agent Service runs persistently in the background as a Foreground Service,
 * displaying an interactive Floating HUD over all other applications and an inline-command
 * notification so users can command, pause, resume, or steer Morvent while it works.
 */
class MorventFloatingAgentService : Service() {

    private val tag = "MorventFloating"
    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var isExpanded = false

    private lateinit var agentHub: MorventAgentHub
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var statusPillView: TextView? = null
    private var liveTaskTextView: TextView? = null
    private var pauseResumeBtnView: Button? = null
    private var stopBtnView: Button? = null
    private var liveControlsRowView: LinearLayout? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        _runningFlow.value = true
        agentHub = MorventAgentHub.getInstance(applicationContext)

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("مورڤينت يعمل في الخلفية وجاهز للأوامر 🤖"))

        if (Settings.canDrawOverlays(this)) {
            setupFloatingView()
        }

        observeAgentStatus()
    }

    private fun observeAgentStatus() {
        serviceScope.launch {
            agentHub.artemisEngine.status.collect { status ->
                val stateLabel = when (status.state) {
                    AgentExecutionState.IDLE -> "تحكم حي"
                    AgentExecutionState.PERCEIVING -> "فحص الشاشة"
                    AgentExecutionState.PLANNING -> "تخطيط"
                    AgentExecutionState.ACTING -> "ينفذ ${status.currentStepIndex}/${status.totalSteps}"
                    AgentExecutionState.VERIFYING -> "يتحقق ✓"
                    AgentExecutionState.PAUSED -> "متوقف مؤقتاً"
                    AgentExecutionState.COMPLETED -> "اكتمل 🎯"
                    AgentExecutionState.ERROR -> "تنبيه"
                }
                statusPillView?.text = stateLabel

                if (status.isActive) {
                    liveControlsRowView?.visibility = View.VISIBLE
                    liveTaskTextView?.visibility = View.VISIBLE
                    liveTaskTextView?.text = status.statusMessage
                    pauseResumeBtnView?.text = if (status.state == AgentExecutionState.PAUSED) "استئناف ▶️" else "مؤقت ⏸️"
                } else {
                    liveControlsRowView?.visibility = View.GONE
                    liveTaskTextView?.visibility = if (status.state == AgentExecutionState.COMPLETED) View.VISIBLE else View.GONE
                    if (status.state == AgentExecutionState.COMPLETED) {
                        liveTaskTextView?.text = status.statusMessage
                    }
                }

                updateNotification(status.statusMessage)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                agentHub.artemisEngine.stop()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> {
                if (agentHub.artemisEngine.status.value.state == AgentExecutionState.PAUSED) {
                    agentHub.artemisEngine.resume()
                } else {
                    agentHub.artemisEngine.pause()
                }
            }
            ACTION_RESUME -> {
                agentHub.artemisEngine.resume()
            }
            ACTION_INSPECT -> {
                agentHub.submitCommand("افحص الشاشة", sourceLabel = "الإشعار")
            }
            ACTION_REMOTE_COMMAND -> {
                val remoteInput = RemoteInput.getResultsFromIntent(intent)
                val cmd = remoteInput?.getCharSequence(KEY_REMOTE_COMMAND)?.toString()?.trim()
                if (!cmd.isNullOrEmpty()) {
                    agentHub.submitCommand(cmd, sourceLabel = "شريط الإشعار") { _, msg ->
                        serviceScope.launch(Dispatchers.Main) {
                            updateNotification(msg)
                        }
                    }
                }
            }
        }
        return START_STICKY
    }

    private fun setupFloatingView() {
        try {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

            val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 40
                y = 200
            }

            val rootLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(18, 16, 18, 16)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#EB0B1220"))
                    cornerRadius = 42f
                    setStroke(3, Color.parseColor("#00E5FF"))
                }
            }

            // Header Row (Draggable)
            val headerRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val orbIcon = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(52, 52)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#00E5FF"))
                }
            }

            val titleText = TextView(this).apply {
                text = " مورڤينت "
                setTextColor(Color.WHITE)
                textSize = 13f
                setPadding(12, 0, 12, 0)
            }

            val statusPill = TextView(this).apply {
                text = "تحكم حي"
                setTextColor(Color.parseColor("#00E5FF"))
                textSize = 10f
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#1A00E5FF"))
                    cornerRadius = 16f
                }
                setPadding(12, 4, 12, 4)
            }
            statusPillView = statusPill

            headerRow.addView(orbIcon)
            headerRow.addView(titleText)
            headerRow.addView(statusPill)
            rootLayout.addView(headerRow)

            // Live Task Feedback Text (shown while Artemis is acting)
            val liveTaskText = TextView(this).apply {
                setTextColor(Color.parseColor("#A5F3FC"))
                textSize = 11f
                visibility = View.GONE
                setPadding(4, 10, 4, 4)
                maxWidth = 650
            }
            liveTaskTextView = liveTaskText
            rootLayout.addView(liveTaskText)

            // Live Pause / Stop controls (shown while Artemis is acting)
            val liveControlsRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                visibility = View.GONE
                setPadding(0, 6, 0, 4)
            }
            liveControlsRowView = liveControlsRow

            fun createActionBtn(label: String, colorHex: String, onClick: () -> Unit): Button {
                return Button(this).apply {
                    text = label
                    textSize = 10f
                    setTextColor(Color.BLACK)
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor(colorHex))
                        cornerRadius = 18f
                    }
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        setMargins(4, 0, 4, 0)
                    }
                    setOnClickListener { onClick() }
                }
            }

            val pauseBtn = createActionBtn("مؤقت ⏸️", "#F59E0B") {
                if (agentHub.artemisEngine.status.value.state == AgentExecutionState.PAUSED) {
                    agentHub.artemisEngine.resume()
                } else {
                    agentHub.artemisEngine.pause()
                }
            }
            pauseResumeBtnView = pauseBtn

            val skipBtn = createActionBtn("تخطي ⏭️", "#A855F7") {
                agentHub.artemisEngine.skipCurrentStep()
            }

            val stopBtn = createActionBtn("إيقاف ⏹️", "#F43F5E") {
                agentHub.artemisEngine.stop()
            }
            stopBtnView = stopBtn

            liveControlsRow.addView(pauseBtn)
            liveControlsRow.addView(skipBtn)
            liveControlsRow.addView(stopBtn)
            rootLayout.addView(liveControlsRow)

            // Expanded Command Input & Quick Controls
            val expandedContainer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
                setPadding(0, 12, 0, 0)
            }

            val cmdInput = EditText(this).apply {
                hint = "آمرني الآن (اضغط، افتح، تصفح، توقف...)"
                setHintTextColor(Color.GRAY)
                setTextColor(Color.WHITE)
                textSize = 12f
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#152033"))
                    cornerRadius = 20f
                    setStroke(1, Color.parseColor("#2A3E5C"))
                }
                setPadding(20, 14, 20, 14)
            }

            val actionsRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 10, 0, 0)
            }

            val sendBtn = createActionBtn("تنفيذ 🚀", "#00E5FF") {
                val text = cmdInput.text.toString().trim()
                if (text.isNotEmpty()) {
                    cmdInput.setText("")
                    statusPill.text = "جارِ التنفيذ..."
                    agentHub.submitCommand(text, sourceLabel = "النافذة العائمة") { ok, _ ->
                        serviceScope.launch(Dispatchers.Main) {
                            if (!agentHub.artemisEngine.isBusy()) {
                                statusPill.text = if (ok) "نجح ✓" else "تنبيه"
                            }
                        }
                    }
                }
            }

            val inspectBtn = createActionBtn("فحص 🔍", "#A855F7") {
                agentHub.submitCommand("افحص الشاشة", sourceLabel = "النافذة العائمة")
            }

            val scrollBtn = createActionBtn("تصفح ⬇️", "#10B981") {
                agentHub.submitCommand("انزل لأسفل", sourceLabel = "النافذة العائمة")
            }

            val voiceBtn = createActionBtn("صوت 🎤", "#38BDF8") {
                val voiceIntent = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(MainActivity.EXTRA_START_VOICE, true)
                }
                startActivity(voiceIntent)
            }

            actionsRow.addView(sendBtn)
            actionsRow.addView(inspectBtn)
            actionsRow.addView(scrollBtn)
            actionsRow.addView(voiceBtn)

            val osRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 8, 0, 0)
            }

            val homeBtn = createActionBtn("🏠", "#1E293B") {
                agentHub.submitCommand("الشاشة الرئيسية", sourceLabel = "النافذة العائمة")
            }.apply { setTextColor(Color.WHITE) }

            val backBtn = createActionBtn("◀️", "#1E293B") {
                agentHub.submitCommand("الرجوع للخلف", sourceLabel = "النافذة العائمة")
            }.apply { setTextColor(Color.WHITE) }

            val splitBtn = createActionBtn("🪟", "#1E293B") {
                agentHub.submitCommand("تقسيم الشاشة", sourceLabel = "النافذة العائمة")
            }.apply { setTextColor(Color.WHITE) }

            val shotBtn = createActionBtn("📸", "#1E293B") {
                agentHub.submitCommand("لقطة شاشة", sourceLabel = "النافذة العائمة")
            }.apply { setTextColor(Color.WHITE) }

            val notifBtn = createActionBtn("🔔", "#1E293B") {
                agentHub.submitCommand("اقرأ الإشعارات", sourceLabel = "النافذة العائمة")
            }.apply { setTextColor(Color.WHITE) }

            osRow.addView(homeBtn)
            osRow.addView(backBtn)
            osRow.addView(splitBtn)
            osRow.addView(shotBtn)
            osRow.addView(notifBtn)

            expandedContainer.addView(cmdInput)
            expandedContainer.addView(actionsRow)
            expandedContainer.addView(osRow)
            rootLayout.addView(expandedContainer)

            // Touch Listener for dragging and expanding
            var initialX = 0
            var initialY = 0
            var initialTouchX = 0f
            var initialTouchY = 0f
            var isClick = false

            headerRow.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isClick = true
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                            isClick = false
                        }
                        params.x = initialX + dx
                        params.y = initialY + dy
                        windowManager?.updateViewLayout(rootLayout, params)
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (isClick) {
                            isExpanded = !isExpanded
                            expandedContainer.visibility = if (isExpanded) View.VISIBLE else View.GONE
                            params.flags = if (isExpanded) {
                                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            } else {
                                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            }
                            windowManager?.updateViewLayout(rootLayout, params)
                        }
                        true
                    }
                    else -> false
                }
            }

            windowManager?.addView(rootLayout, params)
            floatingView = rootLayout
        } catch (e: Exception) {
            Log.e(tag, "Failed to create floating view", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "مساعد مورڤينت للتحكم بالهاتف في الخلفية",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "إشعار تحكم مورڤينت أثناء العمل في الخلفية"
                enableLights(true)
                lightColor = Color.CYAN
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val remoteInput = RemoteInput.Builder(KEY_REMOTE_COMMAND)
            .setLabel("أرسل أمراً لمورڤينت...")
            .build()

        val replyIntent = Intent(this, MorventFloatingAgentService::class.java).apply {
            action = ACTION_REMOTE_COMMAND
        }
        val replyPending = PendingIntent.getService(
            this, 3, replyIntent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val replyAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send,
            "أرسل أمراً",
            replyPending
        ).addRemoteInput(remoteInput).build()

        val inspectIntent = Intent(this, MorventFloatingAgentService::class.java).apply {
            action = ACTION_INSPECT
        }
        val inspectPending = PendingIntent.getService(
            this, 1, inspectIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val pauseIntent = Intent(this, MorventFloatingAgentService::class.java).apply {
            action = ACTION_PAUSE
        }
        val pausePending = PendingIntent.getService(
            this, 2, pauseIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, MorventFloatingAgentService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 4, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("مورڤينت (Artemis Background Agent)")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(openPending)
            .addAction(replyAction)
            .addAction(android.R.drawable.ic_menu_search, "فحص", inspectPending)
            .addAction(android.R.drawable.ic_media_pause, "مؤقت/استئناف", pausePending)
            .addAction(android.R.drawable.ic_delete, "إغلاق", stopPending)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        _runningFlow.value = false
        serviceScope.cancel()
        if (floatingView != null && windowManager != null) {
            try {
                windowManager?.removeView(floatingView)
            } catch (_: Exception) {
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_ID = "morvent_floating_agent_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_STOP = "com.example.morvent.ACTION_STOP"
        const val ACTION_PAUSE = "com.example.morvent.ACTION_PAUSE"
        const val ACTION_RESUME = "com.example.morvent.ACTION_RESUME"
        const val ACTION_INSPECT = "com.example.morvent.ACTION_INSPECT"
        const val ACTION_REMOTE_COMMAND = "com.example.morvent.ACTION_REMOTE_COMMAND"
        const val KEY_REMOTE_COMMAND = "key_morvent_remote_command"

        private val _runningFlow = MutableStateFlow(false)
        val runningFlow: StateFlow<Boolean> = _runningFlow.asStateFlow()

        var instance: MorventFloatingAgentService? = null
            private set

        val isRunning: Boolean
            get() = instance != null

        fun start(context: Context) {
            val intent = Intent(context, MorventFloatingAgentService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, MorventFloatingAgentService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
