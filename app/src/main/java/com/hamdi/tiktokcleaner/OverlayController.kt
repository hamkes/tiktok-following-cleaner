package com.hamdi.tiktokcleaner

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * Floating Cyber Pill Overlay v2.0:
 * - Safe against WindowManager.BadTokenException (checks Settings.canDrawOverlays before adding/updating)
 * - Fully localized (Arabic & English via string resources)
 * - Shows live progress, speed (acc/min), current phase, and Pause/Resume/Stop controls
 */
class OverlayController(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var rootView: LinearLayout? = null
    private var txtHeader: TextView? = null
    private var txtSub: TextView? = null
    private var controlsRow: LinearLayout? = null
    private var btnPauseResume: Button? = null
    private var btnStop: Button? = null
    private var isExpanded = false

    fun canShowOverlay(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }

    fun showOrUpdate(stats: EngineStats) {
        mainHandler.post {
            if (!canShowOverlay() || stats.phase == RunPhase.IDLE) {
                hideInternal()
                return@post
            }
            try {
                if (rootView == null) {
                    createOverlayView()
                }
                bindStats(stats)
            } catch (e: Exception) {
                LogStore.log("⚠️ تعذر إظهار الكبسولة العائمة: ${e.javaClass.simpleName}")
                hideInternal()
            }
        }
    }

    fun hide() {
        mainHandler.post { hideInternal() }
    }

    private fun hideInternal() {
        val view = rootView ?: return
        try {
            windowManager.removeViewImmediate(view)
        } catch (_: Exception) {
        }
        rootView = null
    }

    private fun createOverlayView() {
        val localizedCtx = LocaleHelper.wrap(context)
        val container = LinearLayout(localizedCtx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(22).toFloat()
                setColor(Color.parseColor("#E6111622"))
                setStroke(dp(1), Color.parseColor("#00F2FE"))
            }
        }

        val header = TextView(localizedCtx).apply {
            setTextColor(Color.parseColor("#00F2FE"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            text = localizedCtx.getString(R.string.overlay_running)
        }

        val sub = TextView(localizedCtx).apply {
            setTextColor(Color.parseColor("#E2E8F0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(0, dp(2), 0, 0)
        }

        val actions = LinearLayout(localizedCtx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
            visibility = if (isExpanded) View.VISIBLE else View.GONE
        }

        val pauseBtn = Button(localizedCtx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(Color.parseColor("#0B0E14"))
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#00F2FE"))
            }
            setOnClickListener {
                val p = Engine.stats.phase
                if (p == RunPhase.PAUSED || p == RunPhase.CAPTCHA_PAUSED) {
                    Engine.resume()
                } else {
                    Engine.pause()
                }
            }
        }

        val stopBtn = Button(localizedCtx).apply {
            text = localizedCtx.getString(R.string.overlay_stop)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(Color.WHITE)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#FE2C55"))
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(8) }
            layoutParams = lp
            setOnClickListener {
                Engine.stop()
            }
        }

        actions.addView(pauseBtn)
        actions.addView(stopBtn)

        container.addView(header)
        container.addView(sub)
        container.addView(actions)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = dp(140)
        }

        attachDragAndToggle(container, params, actions)
        windowManager.addView(container, params)

        rootView = container
        txtHeader = header
        txtSub = sub
        controlsRow = actions
        btnPauseResume = pauseBtn
        btnStop = stopBtn
    }

    private fun bindStats(stats: EngineStats) {
        val localizedCtx = LocaleHelper.wrap(context)
        val bg = rootView?.background as? GradientDrawable
        when (stats.phase) {
            RunPhase.CAPTCHA_PAUSED -> {
                bg?.setStroke(dp(2), Color.parseColor("#F59E0B"))
                txtHeader?.setTextColor(Color.parseColor("#F59E0B"))
                txtHeader?.text = localizedCtx.getString(R.string.overlay_captcha_alert)
                btnPauseResume?.text = localizedCtx.getString(R.string.overlay_resume)
            }
            RunPhase.PAUSED -> {
                bg?.setStroke(dp(1), Color.parseColor("#F59E0B"))
                txtHeader?.setTextColor(Color.parseColor("#F59E0B"))
                txtHeader?.text = localizedCtx.getString(R.string.overlay_paused)
                btnPauseResume?.text = localizedCtx.getString(R.string.overlay_resume)
            }
            RunPhase.COLLECTING -> {
                bg?.setStroke(dp(1), Color.parseColor("#00F2FE"))
                txtHeader?.setTextColor(Color.parseColor("#00F2FE"))
                txtHeader?.text = "${localizedCtx.getString(R.string.overlay_collecting)} (${stats.collectedCount})"
                btnPauseResume?.text = localizedCtx.getString(R.string.overlay_pause)
            }
            RunPhase.CLASSIFYING -> {
                bg?.setStroke(dp(1), Color.parseColor("#00F2FE"))
                txtHeader?.setTextColor(Color.parseColor("#00F2FE"))
                txtHeader?.text = "${localizedCtx.getString(R.string.overlay_auditing)} ${stats.auditedCount}/${stats.totalToAudit}"
                btnPauseResume?.text = localizedCtx.getString(R.string.overlay_pause)
            }
            RunPhase.UNFOLLOWING -> {
                bg?.setStroke(dp(1), Color.parseColor("#FE2C55"))
                txtHeader?.setTextColor(Color.parseColor("#FE2C55"))
                txtHeader?.text = "${localizedCtx.getString(R.string.overlay_unfollowing)} ${stats.unfollowedCount}/${stats.totalToUnfollow}"
                btnPauseResume?.text = localizedCtx.getString(R.string.overlay_pause)
            }
            RunPhase.IDLE -> {}
        }

        val speedInfo = if (stats.accountsPerMinute > 0) " • ${stats.accountsPerMinute}/د" else ""
        val userTag = if (stats.currentUsername.isNotEmpty()) "@${stats.currentUsername}" else stats.statusMessage
        txtSub?.text = "$userTag$speedInfo"
    }

    private fun attachDragAndToggle(
        view: View,
        params: WindowManager.LayoutParams,
        actionsRow: View
    ) {
        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false

        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) {
                        moved = true
                    }
                    params.x = initialX + dx
                    params.y = initialY + dy
                    try {
                        windowManager.updateViewLayout(view, params)
                    } catch (_: Exception) {
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        isExpanded = !isExpanded
                        actionsRow.visibility = if (isExpanded) View.VISIBLE else View.GONE
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics
        ).toInt()
}
