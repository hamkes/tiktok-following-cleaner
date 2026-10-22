package com.hamdi.tiktokcleaner

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * v1.2 — Messenger-style floating control bubble.
 *
 * Shown (via WindowManager, TYPE_APPLICATION_OVERLAY) ONLY while an audit or
 * unfollow run is active. Collapsed it is a small draggable circle docked at
 * a screen edge: semi-transparent TikTok-red "▶" while the run proceeds,
 * opaque amber "⏸" while paused, amber "!" while waiting on a CAPTCHA.
 * A tap expands a small control panel («إيقاف مؤقت / استئناف», «إيقاف»,
 * «فتح التطبيق»); while the panel is open the engine auto-pauses between
 * steps (CleanerEngine.panelOpen), so the panel can never race the
 * automation. Tapping outside the panel (a transparent full-screen catcher
 * that exists only while the panel is open) or the bubble again collapses it.
 *
 * Requires android.permission.SYSTEM_ALERT_WINDOW. If the permission is
 * missing or the user disabled the bubble in the app, show() is a no-op and
 * runs behave exactly as in v1.1.
 *
 * All views are built in code with the v1.1 palette (dark card #1F1F28,
 * accent red #FE2C55, amber #FBBF24). Everything runs on the main thread;
 * callers may be on the worker thread.
 */
object OverlayController {

    private val handler = Handler(Looper.getMainLooper())

    private var windowManager: WindowManager? = null
    private var appContext: Context? = null

    private var bubble: TextView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null

    private var catcher: View? = null
    private var panel: LinearLayout? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var panelStatus: TextView? = null
    private var panelPauseBtn: TextView? = null
    private var panelVisible = false

    private var shownAtMs = 0L
    private var inactiveTicks = 0

    private const val BUBBLE_DP = 54

    // ---------------- public API ----------------

    /** Show the bubble for the current run. No-op without permission/toggle. */
    fun show(context: Context) {
        val ctx = context.applicationContext
        handler.post {
            if (!Prefs(ctx).bubbleEnabled || !Settings.canDrawOverlays(ctx)) {
                hideInternal()
                return@post
            }
            if (bubble != null) return@post
            appContext = ctx
            windowManager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            shownAtMs = System.currentTimeMillis()
            inactiveTicks = 0
            buildAndAttachBubble(ctx)
            syncUi()
            handler.removeCallbacks(syncLoop)
            handler.postDelayed(syncLoop, 700)
        }
    }

    /** Remove bubble + panel (run finished, service destroyed, …). */
    fun hide() {
        handler.post { hideInternal() }
    }

    // ---------------- construction ----------------

    private fun buildAndAttachBubble(ctx: Context) {
        val mgr = windowManager ?: return
        val size = dp(ctx, BUBBLE_DP)
        val tv = TextView(ctx).apply {
            text = GLYPH_RUN
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = circleDrawable(Color.parseColor("#FE2C55"))
            alpha = 0.78f
            elevation = dp(ctx, 6).toFloat()
            isClickable = true
        }
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.LEFT
        val metrics = ctx.resources.displayMetrics
        lp.x = metrics.widthPixels - size - dp(ctx, 8)
        lp.y = (metrics.heightPixels * 0.32f).toInt()

        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var dragged = false
        tv.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = e.rawX; downRawY = e.rawY
                    startX = lp.x; startY = lp.y
                    dragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downRawX).toInt()
                    val dy = (e.rawY - downRawY).toInt()
                    if (kotlin.math.abs(dx) + kotlin.math.abs(dy) > dp(ctx, 8)) dragged = true
                    if (dragged) {
                        lp.x = startX + dx
                        lp.y = startY + dy
                        runCatching { mgr.updateViewLayout(v, lp) }
                        if (panelVisible) positionPanel()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragged) snapToEdge(v, lp) else togglePanel()
                    true
                }
                else -> false
            }
        }

        bubble = tv
        bubbleParams = lp
        runCatching { mgr.addView(tv, lp) }
    }

    private fun snapToEdge(v: View, lp: WindowManager.LayoutParams) {
        val ctx = appContext ?: return
        val mgr = windowManager ?: return
        val w = ctx.resources.displayMetrics.widthPixels
        val size = lp.width
        lp.x = if (lp.x + size / 2 < w / 2) dp(ctx, 6) else w - size - dp(ctx, 6)
        runCatching { mgr.updateViewLayout(v, lp) }
        if (panelVisible) positionPanel()
    }

    // ---------------- panel ----------------

    private fun togglePanel() {
        if (panelVisible) collapsePanel() else expandPanel()
    }

    private fun expandPanel() {
        val ctx = appContext ?: return
        val mgr = windowManager ?: return
        if (panel == null) buildPanel(ctx)
        val p = panel ?: return
        // Engine auto-pauses between steps while the panel is open.
        CleanerEngine.panelOpen = true

        if (catcher == null) {
            val c = View(ctx)
            val clp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            )
            c.setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_DOWN) collapsePanel()
                true
            }
            catcher = c
            runCatching { mgr.addView(c, clp) }
        }
        val pp = panelParams ?: return
        runCatching { mgr.addView(p, pp) }
        panelVisible = true
        positionPanel()
        syncUi()
    }

    private fun collapsePanel() {
        CleanerEngine.panelOpen = false
        panelVisible = false
        val mgr = windowManager ?: return
        panel?.let { runCatching { mgr.removeView(it) } }
        catcher?.let { runCatching { mgr.removeView(it) } }
        catcher = null
    }

    private fun buildPanel(ctx: Context) {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedDrawable(
                Color.parseColor("#1F1F28"), 16f,
                Color.parseColor("#3D3D4C")
            )
            setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
            elevation = dp(ctx, 8).toFloat()
        }
        val title = TextView(ctx).apply {
            text = "التحكم بالتشغيل"
            textSize = 15f
            setTextColor(Color.parseColor("#F5F5F7"))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        card.addView(title)
        panelStatus = TextView(ctx).apply {
            textSize = 12.5f
            setTextColor(Color.parseColor("#B9B9C6"))
            setPadding(0, dp(ctx, 4), 0, dp(ctx, 6))
        }
        card.addView(panelStatus)

        panelPauseBtn = actionButton(ctx, "إيقاف مؤقت", "#26262F", "#F5F5F7") {
            CleanerEngine.togglePause()
            syncUi()
        }
        card.addView(panelPauseBtn)
        card.addView(
            actionButton(ctx, "إيقاف", "#C8102E", "#FFFFFF") {
                CleanerEngine.requestStop() // same stop path as the in-app STOP
                collapsePanel()
                hideInternal()
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(ctx, 8) }
        )
        card.addView(
            actionButton(ctx, "فتح التطبيق", "#26262F", "#25F4EE") {
                openMainApp(ctx)
                collapsePanel()
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(ctx, 8) }
        )

        panel = card
        panelParams = WindowManager.LayoutParams(
            dp(ctx, 232),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.LEFT }
    }

    private fun positionPanel() {
        val ctx = appContext ?: return
        val mgr = windowManager ?: return
        val bp = bubbleParams ?: return
        val pp = panelParams ?: return
        val p = panel ?: return
        val metrics = ctx.resources.displayMetrics
        val bubbleSize = bp.width
        val panelW = pp.width
        // Keep the panel on screen: align its end edge with the bubble's dock edge.
        pp.x = if (bp.x + bubbleSize / 2 < metrics.widthPixels / 2) {
            bp.x
        } else {
            (bp.x + bubbleSize - panelW).coerceAtLeast(0)
        }
        pp.y = (bp.y + bubbleSize + dp(ctx, 8))
            .coerceAtMost((metrics.heightPixels - dp(ctx, 240)).coerceAtLeast(0))
        runCatching { mgr.updateViewLayout(p, pp) }
    }

    private fun openMainApp(ctx: Context) {
        val intent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
            ?: Intent(ctx, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        runCatching { ctx.startActivity(intent) }
    }

    // ---------------- state sync ----------------

    private val syncLoop = object : Runnable {
        override fun run() {
            if (bubble == null) return
            syncUi()
            if (bubble != null) handler.postDelayed(this, 700)
        }
    }

    private fun runActive(): Boolean =
        CleanerEngine.isRunning() ||
            CleanerEngine.state == CleanerEngine.RunState.PAUSED_CAPTCHA

    private fun syncUi() {
        val b = bubble ?: return
        // Safety net: if the run ended without the service hiding us
        // (its finally-block is the primary path), remove the bubble.
        if (!runActive()) {
            if (System.currentTimeMillis() - shownAtMs > 4000) {
                inactiveTicks++
                if (inactiveTicks >= 3) {
                    hideInternal()
                    return
                }
            }
        } else {
            inactiveTicks = 0
        }

        when {
            CleanerEngine.state == CleanerEngine.RunState.PAUSED_CAPTCHA -> {
                b.text = "!"
                b.setTextColor(Color.parseColor("#1F1F28"))
                b.background = circleDrawable(Color.parseColor("#FBBF24"))
                b.alpha = 1f
            }
            CleanerEngine.isPaused() -> {
                b.text = GLYPH_PAUSE
                b.setTextColor(Color.parseColor("#1F1F28"))
                b.background = circleDrawable(Color.parseColor("#FBBF24"))
                b.alpha = 1f
            }
            else -> {
                b.text = GLYPH_RUN
                b.setTextColor(Color.WHITE)
                b.background = circleDrawable(Color.parseColor("#FE2C55"))
                b.alpha = 0.78f
            }
        }

        if (panelVisible) {
            panelStatus?.text = statusLine()
            panelPauseBtn?.text =
                if (CleanerEngine.paused) "استئناف" else "إيقاف مؤقت"
        }
    }

    private fun statusLine(): String {
        val base = when (CleanerEngine.state) {
            CleanerEngine.RunState.AUDIT_LIST -> "الفحص — جمع قائمة المتابَعة"
            CleanerEngine.RunState.AUDIT_PROFILES ->
                "الفحص — ${CleanerEngine.auditedCount}/${CleanerEngine.auditTotal}"
            CleanerEngine.RunState.UNFOLLOW ->
                "إلغاء المتابعة — ${CleanerEngine.unfollowProcessed}/${CleanerEngine.unfollowTotal}"
            CleanerEngine.RunState.PAUSED_CAPTCHA -> "متوقف — بانتظار حلّ التحقق داخل TikTok"
            else -> CleanerEngine.state.name
        }
        return if (CleanerEngine.paused) "$base (موقوف مؤقتاً)" else base
    }

    // ---------------- teardown ----------------

    private fun hideInternal() {
        handler.removeCallbacks(syncLoop)
        if (panelVisible || panel != null) collapsePanel()
        val mgr = windowManager
        if (mgr != null) {
            bubble?.let { runCatching { mgr.removeView(it) } }
        }
        bubble = null
        bubbleParams = null
        panel = null
        panelParams = null
        panelStatus = null
        panelPauseBtn = null
        CleanerEngine.panelOpen = false
    }

    // ---------------- small view helpers ----------------

    private fun actionButton(
        ctx: Context,
        label: String,
        bg: String,
        fg: String,
        onClick: () -> Unit
    ): TextView {
        return TextView(ctx).apply {
            text = label
            textSize = 14.5f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor(fg))
            background = roundedDrawable(Color.parseColor(bg), 12f, null)
            setPadding(dp(ctx, 10), dp(ctx, 10), dp(ctx, 10), dp(ctx, 10))
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { onClick() }
        }
    }

    private fun circleDrawable(color: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(2, Color.parseColor("#3D3D4C"))
        }

    private fun roundedDrawable(color: Int, radiusDp: Float, stroke: Int?): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = radiusDp *
                (appContext?.resources?.displayMetrics?.density ?: 1f)
            if (stroke != null) setStroke(2, stroke)
        }

    private fun dp(ctx: Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()

    private const val GLYPH_RUN = "▶"
    private const val GLYPH_PAUSE = "⏸"
}
