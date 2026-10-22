package com.hamdi.tiktokcleaner

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * v1.7: bottom-navigation UI + bilingual (AR default / EN via Settings).
 * All v1.6 behaviour preserved. Every user-visible string now comes from
 * resources. NOT BUILT, NOT TESTED.
 */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var files: AppFiles
    private val handler = Handler(Looper.getMainLooper())

    // -------- views (same IDs as v1.6) --------
    private val txtServiceStatus by lazy { findViewById<TextView>(R.id.txtServiceStatus) }
    private val txtAuditStatus by lazy { findViewById<TextView>(R.id.txtAuditStatus) }
    private val txtUnfollowStatus by lazy { findViewById<TextView>(R.id.txtUnfollowStatus) }
    private val txtCandidatesInfo by lazy { findViewById<TextView>(R.id.txtCandidatesInfo) }
    private val txtLog by lazy { findViewById<TextView>(R.id.txtLog) }
    private val chkConfirm by lazy { findViewById<CheckBox>(R.id.chkConfirm) }
    private val txtServicePill by lazy { findViewById<TextView>(R.id.txtServicePill) }
    private val progressAudit by lazy { findViewById<ProgressBar>(R.id.progressAudit) }
    private val chipAudited by lazy { findViewById<TextView>(R.id.chipAudited) }
    private val chipCandidates by lazy { findViewById<TextView>(R.id.chipCandidates) }
    private val chipActive by lazy { findViewById<TextView>(R.id.chipActive) }
    private val chipUnknown by lazy { findViewById<TextView>(R.id.chipUnknown) }
    private val txtOverlayStatus by lazy { findViewById<TextView>(R.id.txtOverlayStatus) }
    private val switchBubble by lazy { findViewById<Switch>(R.id.switchBubble) }
    private val txtReadyService by lazy { findViewById<TextView>(R.id.txtReadyService) }
    private val txtReadyOverlay by lazy { findViewById<TextView>(R.id.txtReadyOverlay) }
    private val txtReadyTikTok by lazy { findViewById<TextView>(R.id.txtReadyTikTok) }
    private val txtReadyVersion by lazy { findViewById<TextView>(R.id.txtReadyVersion) }
    private val txtHelpBody by lazy { findViewById<TextView>(R.id.txtHelpBody) }
    private val btnHelpToggle by lazy { findViewById<Button>(R.id.btnHelpToggle) }

    // -------- tab containers + buttons --------
    private val tabHome by lazy { findViewById<View>(R.id.tabHome) }
    private val tabAudit by lazy { findViewById<View>(R.id.tabAudit) }
    private val tabUnfollow by lazy { findViewById<View>(R.id.tabUnfollow) }
    private val tabLog by lazy { findViewById<View>(R.id.tabLog) }
    private val tabSettings by lazy { findViewById<View>(R.id.tabSettings) }

    private val tabBtnHome by lazy { findViewById<View>(R.id.tabBtnHome) }
    private val tabBtnAudit by lazy { findViewById<View>(R.id.tabBtnAudit) }
    private val tabBtnUnfollow by lazy { findViewById<View>(R.id.tabBtnUnfollow) }
    private val tabBtnLog by lazy { findViewById<View>(R.id.tabBtnLog) }
    private val tabBtnSettings by lazy { findViewById<View>(R.id.tabBtnSettings) }

    /** Locale snapshot at onCreate — used by onResume to detect a language change. */
    private var currentLang: String = LocaleHelper.DEFAULT_LANG

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        currentLang = LocaleHelper.readLang(this)

        prefs = Prefs(this)
        files = AppFiles(this)
        LogStore.init(files.logFile)

        // -------- buttons (unchanged wiring) --------
        findViewById<Button>(R.id.btnA11y).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, R.string.toast_enable_service_hint, Toast.LENGTH_LONG).show()
        }
        findViewById<Button>(R.id.btnOpenTikTok).setOnClickListener { openTikTok() }
        findViewById<Button>(R.id.btnReadyService).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btnReadyOverlay).setOnClickListener {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }
        findViewById<Button>(R.id.btnReadyTikTok).setOnClickListener { openTikTok() }

        // version row
        txtReadyVersion.text = try {
            @Suppress("DEPRECATION")
            val pi = packageManager.getPackageInfo(packageName, 0)
            getString(R.string.ready_version_format, pi.versionName ?: "?", pi.versionCode)
        } catch (_: Exception) {
            getString(R.string.ready_version_unknown)
        }

        // help toggle
        btnHelpToggle.setOnClickListener {
            val show = txtHelpBody.visibility != View.VISIBLE
            txtHelpBody.visibility = if (show) View.VISIBLE else View.GONE
            btnHelpToggle.setText(if (show) R.string.help_hide else R.string.help_show)
        }

        // bubble permission + switch
        findViewById<Button>(R.id.btnOverlayPerm).setOnClickListener {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
            Toast.makeText(this, R.string.toast_bubble_grant_hint, Toast.LENGTH_LONG).show()
        }
        switchBubble.isChecked = prefs.bubbleEnabled
        switchBubble.setOnCheckedChangeListener { _, checked ->
            prefs.bubbleEnabled = checked
            if (checked && !Settings.canDrawOverlays(this)) {
                Toast.makeText(this, R.string.toast_bubble_need_permission, Toast.LENGTH_LONG).show()
            }
            refresh()
        }

        // audit
        findViewById<Button>(R.id.btnAuditStart).setOnClickListener {
            if (!ensureService()) return@setOnClickListener
            CleanerEngine.startAudit(resume = false)
            refresh()
        }
        findViewById<Button>(R.id.btnAuditResume).setOnClickListener {
            if (!ensureService()) return@setOnClickListener
            CleanerEngine.startAudit(resume = true)
            refresh()
        }

        // candidates
        findViewById<Button>(R.id.btnPick).setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_PICK_CANDIDATES)
        }
        findViewById<Button>(R.id.btnUseLast).setOnClickListener {
            if (!files.candidatesCsv.exists()) {
                Toast.makeText(this, R.string.toast_no_previous_audit, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val list = files.readCandidatesFrom(files.candidatesCsv)
            setCandidates(list, "مرشحو آخر فحص محفوظ")
        }
        chkConfirm.setOnCheckedChangeListener { _, checked ->
            CleanerEngine.candidatesConfirmed = checked
        }
        findViewById<Button>(R.id.btnUnfollowStart).setOnClickListener {
            if (!ensureService()) return@setOnClickListener
            if (CleanerEngine.candidates.isEmpty()) {
                Toast.makeText(this, R.string.toast_pick_first, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!chkConfirm.isChecked) {
                Toast.makeText(this, R.string.toast_confirm_first, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            CleanerEngine.candidatesConfirmed = true
            CleanerEngine.startUnfollow()
            refresh()
        }
        findViewById<Button>(R.id.btnStop).setOnClickListener {
            CleanerEngine.requestStop()
            refresh()
        }

        // settings shortcuts (Home card + Settings tab)
        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<Button>(R.id.btnSettingsTabOpen).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // log
        findViewById<Button>(R.id.btnClearLog).setOnClickListener {
            LogStore.clearScreen()
            refresh()
        }
        findViewById<Button>(R.id.btnCopyLog).setOnClickListener {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(
                ClipData.newPlainText(getString(R.string.log_clip_label), LogStore.tail())
            )
            Toast.makeText(this, R.string.log_copied_toast, Toast.LENGTH_SHORT).show()
        }

        // -------- bottom navigation --------
        tabBtnHome.setOnClickListener { selectTab(Tab.HOME) }
        tabBtnAudit.setOnClickListener { selectTab(Tab.AUDIT) }
        tabBtnUnfollow.setOnClickListener { selectTab(Tab.UNFOLLOW) }
        tabBtnLog.setOnClickListener { selectTab(Tab.LOG) }
        tabBtnSettings.setOnClickListener { selectTab(Tab.SETTINGS) }
        selectTab(Tab.HOME)

        LogStore.log("تم فتح التطبيق")
        handler.post(refreshLoop)
    }

    override fun onResume() {
        super.onResume()
        // v1.7 language-change detection (option ج you chose).
        val langNow = LocaleHelper.readLang(this)
        if (langNow != currentLang) {
            currentLang = langNow
            recreate()
            return
        }
        refresh()
    }

    override fun onDestroy() {
        handler.removeCallbacks(refreshLoop)
        super.onDestroy()
    }

    // ---------------- tab switching ----------------

    private enum class Tab { HOME, AUDIT, UNFOLLOW, LOG, SETTINGS }

    private fun selectTab(tab: Tab) {
        tabHome.visibility = if (tab == Tab.HOME) View.VISIBLE else View.GONE
        tabAudit.visibility = if (tab == Tab.AUDIT) View.VISIBLE else View.GONE
        tabUnfollow.visibility = if (tab == Tab.UNFOLLOW) View.VISIBLE else View.GONE
        tabLog.visibility = if (tab == Tab.LOG) View.VISIBLE else View.GONE
        tabSettings.visibility = if (tab == Tab.SETTINGS) View.VISIBLE else View.GONE

        tabBtnHome.isSelected = tab == Tab.HOME
        tabBtnAudit.isSelected = tab == Tab.AUDIT
        tabBtnUnfollow.isSelected = tab == Tab.UNFOLLOW
        tabBtnLog.isSelected = tab == Tab.LOG
        tabBtnSettings.isSelected = tab == Tab.SETTINGS

        val active = Color.parseColor("#FE2C55")
        val muted = Color.parseColor("#8A8A99")
        val bold = android.graphics.Typeface.DEFAULT_BOLD
        val normal = android.graphics.Typeface.DEFAULT

        fun styleLabel(id: Int, activeTab: Boolean) {
            val tv = findViewById<TextView>(id)
            tv.setTextColor(if (activeTab) active else muted)
            tv.typeface = if (activeTab) bold else normal
        }
        styleLabel(R.id.tabLabelHome, tab == Tab.HOME)
        styleLabel(R.id.tabLabelAudit, tab == Tab.AUDIT)
        styleLabel(R.id.tabLabelUnfollow, tab == Tab.UNFOLLOW)
        styleLabel(R.id.tabLabelLog, tab == Tab.LOG)
        styleLabel(R.id.tabLabelSettings, tab == Tab.SETTINGS)
    }

    // ---------------- legacy result API (no AndroidX) ----------------

    @Deprecated("legacy result API used on purpose (no AndroidX)")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK_CANDIDATES && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            loadCandidatesFromUri(uri)
        }
    }

    private fun loadCandidatesFromUri(uri: Uri) {
        try {
            val text = contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: ""
            val list = AppFiles.parseCandidateUsernames(text)
            File(files.candidatesCsv.parentFile, "candidates_imported.txt")
                .writeText(list.joinToString("\n"))
            setCandidates(list, "ملف مختار: ${uri.lastPathSegment ?: "candidates"}")
        } catch (e: Exception) {
            Toast.makeText(
                this,
                getString(R.string.toast_read_file_error, e.message ?: ""),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun setCandidates(list: List<String>, source: String) {
        CleanerEngine.candidates = list
        CleanerEngine.candidatesSource = source
        CleanerEngine.candidatesConfirmed = false
        chkConfirm.isChecked = false
        LogStore.log("تم تحميل قائمة مرشحين: ${list.size} حساب من «$source»")
        refresh()
    }

    private fun ensureService(): Boolean {
        if (!isServiceEnabled() || CleanerEngine.service == null) {
            Toast.makeText(this, R.string.toast_service_not_enabled, Toast.LENGTH_LONG).show()
            return false
        }
        return true
    }

    private fun isServiceEnabled(): Boolean {
        val mgr = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        return mgr.getEnabledAccessibilityServiceList(
            android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        ).any { it.resolveInfo.serviceInfo.packageName == packageName }
    }

    private fun openTikTok() {
        val s = CleanerEngine.service
        if (s != null && s.openTikTokApp()) {
            // opened
        } else {
            val intent = packageManager.getLaunchIntentForPackage("com.zhiliaoapp.musically")
                ?: packageManager.getLaunchIntentForPackage("com.ss.android.ugc.trill")
            if (intent != null) startActivity(intent)
            else Toast.makeText(this, R.string.toast_tiktok_missing, Toast.LENGTH_SHORT).show()
        }
    }

    private fun isTikTokInstalled(): Boolean =
        listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill").any { pkg ->
            try {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(pkg, 0)
                true
            } catch (_: Exception) {
                false
            }
        }

    // v1.9: saved-progress line cache, keyed by the two audit files'
    // lengths so the 900 ms refresh loop re-reads them only on change.
    private var savedProgressKey: String = ""
    private var cachedSavedText: String? = null

    /**
     * v1.9: when no run is active in this process (e.g. the app was
     * closed and reopened), the engine's in-memory counters read zero
     * even though a previous audit classified thousands of profiles.
     * The saved truth lives in the app files: the collected list and
     * the appended results. Show it, so «استئناف الفحص» has a visible
     * starting point: "classified X of Y".
     */
    private fun savedProgressText(): String? {
        val key = "${files.followingList.length()}:${files.auditJsonl.length()}"
        if (key == savedProgressKey) return cachedSavedText
        savedProgressKey = key
        cachedSavedText = try {
            val total = files.readFollowing().size
            val done = if (files.auditJsonl.exists())
                files.auditJsonl.readLines().count { it.isNotBlank() } else 0
            if (total > 0 && done > 0)
                getString(R.string.audit_saved_progress_format, minOf(done, total), total)
            else null
        } catch (_: Exception) {
            null
        }
        return cachedSavedText
    }

    private val refreshLoop = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 900)
        }
    }

    private fun refresh() {
        val engine = CleanerEngine
        val svcEnabled = isServiceEnabled()

        txtServiceStatus.text = getString(
            R.string.service_status_format,
            getString(if (svcEnabled) R.string.service_status_on else R.string.service_status_off)
        )

        txtServicePill.text =
            getString(if (svcEnabled) R.string.service_pill_on else R.string.service_pill_off)
        txtServicePill.setBackgroundResource(
            if (svcEnabled) R.drawable.bg_pill_on else R.drawable.bg_pill_off
        )
        txtServicePill.setTextColor(Color.parseColor(if (svcEnabled) "#4ADE80" else "#FBBF24"))

        val canOverlay = Settings.canDrawOverlays(this)
        txtOverlayStatus.text = getString(
            R.string.bubble_status_format,
            getString(
                if (canOverlay) R.string.bubble_status_granted else R.string.bubble_status_off
            )
        )
        if (switchBubble.isChecked != prefs.bubbleEnabled) {
            switchBubble.isChecked = prefs.bubbleEnabled
        }

        fun ready(view: TextView, ok: Boolean, okRes: Int, badRes: Int) {
            view.text = getString(if (ok) okRes else badRes)
            view.setTextColor(Color.parseColor(if (ok) "#4ADE80" else "#FBBF24"))
        }
        ready(txtReadyService, svcEnabled, R.string.ready_ok, R.string.ready_off)
        ready(txtReadyOverlay, canOverlay, R.string.ready_granted, R.string.ready_not_granted)
        ready(
            txtReadyTikTok, isTikTokInstalled(),
            R.string.ready_installed, R.string.ready_not_installed
        )

        val c = engine.categoryCounts
        // v1.9: with no run active in this process, prefer the saved
        // progress line (classified X of Y from the audit files).
        val savedText = if (engine.state == CleanerEngine.RunState.IDLE &&
            engine.auditTotal == 0
        ) savedProgressText() else null
        if (savedText != null) {
            txtAuditStatus.text = savedText
        } else {
            txtAuditStatus.text = getString(
                R.string.audit_status_detail_format,
                engine.state.name,
                engine.statusDetail,
                engine.collectedCount,
                engine.auditedCount,
                engine.auditTotal,
                c[Category.GONE] ?: 0,
                c[Category.FAKE] ?: 0,
                c[Category.DEAD] ?: 0,
                c[Category.UNKNOWN] ?: 0,
                c[Category.ACTIVE] ?: 0
            )
        }

        progressAudit.max = if (engine.auditTotal > 0) engine.auditTotal else 100
        progressAudit.progress = engine.auditedCount
        chipAudited.text = engine.auditedCount.toString()
        chipCandidates.text =
            ((c[Category.GONE] ?: 0) + (c[Category.FAKE] ?: 0) + (c[Category.DEAD] ?: 0)).toString()
        chipActive.text = (c[Category.ACTIVE] ?: 0).toString()
        chipUnknown.text = (c[Category.UNKNOWN] ?: 0).toString()

        val now = System.currentTimeMillis()
        txtUnfollowStatus.text = getString(
            R.string.unfollow_status_format,
            engine.state.name,
            engine.unfollowProcessed,
            engine.unfollowTotal,
            engine.unfollowSuccess,
            prefs.countInLast(24, now),
            prefs.dayCap,
            prefs.countInLast(1, now),
            prefs.hourCap
        )

        txtCandidatesInfo.text = if (engine.candidates.isEmpty()) {
            getString(R.string.unfollow_candidates_none)
        } else {
            val preview = engine.candidates.take(5).joinToString("، ") { "@$it" }
            getString(
                R.string.unfollow_candidates_format,
                engine.candidates.size,
                engine.candidatesSource,
                preview
            )
        }

        // log with colour spans (unchanged semantics)
        val logEntries = LogStore.entries()
        val logPlain = logEntries.joinToString("\n") { it.first }
        if (txtLog.text.toString() != logPlain) {
            val sb = SpannableStringBuilder()
            logEntries.forEachIndexed { i, (line, level) ->
                if (i > 0) sb.append("\n")
                val start = sb.length
                sb.append(line)
                val color = when (level) {
                    LogStore.Level.ERROR -> Color.parseColor("#FF5C7A")
                    LogStore.Level.WARN -> Color.parseColor("#FBBF24")
                    LogStore.Level.INFO -> Color.parseColor("#C9D1D9")
                }
                sb.setSpan(
                    ForegroundColorSpan(color), start, sb.length,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            txtLog.text = sb
            // v1.7: scroll detection fixed to match the new nesting
            // (txtLog.parent is now the ScrollView directly).
            (txtLog.parent as? ScrollView)?.post {
                (txtLog.parent as? ScrollView)?.fullScroll(View.FOCUS_DOWN)
            }
        }
    }

    companion object {
        private const val REQ_PICK_CANDIDATES = 42
    }
}
