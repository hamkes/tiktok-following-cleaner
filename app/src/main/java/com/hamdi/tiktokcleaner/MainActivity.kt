package com.hamdi.tiktokcleaner

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

/**
 * Redesigned MainActivity v2.0 (Dark Cyber Pro UI):
 * - Live Service & Overlay Permission status banner with 1-tap activation
 * - 3-Mode Speed Selector (Turbo In-List, Hybrid Smart, Safe Deep-Link)
 * - 4 Real-Time KPI Cards + Animated Progress Bar
 * - Category Filter Chips + Instant Search Input
 * - Interactive Candidate List with per-account selection, category badge, and Open-in-TikTok button
 * - Live Diagnostic Console with Copy & Export CSV
 */
class MainActivity : Activity() {

    private var activeFilter: Category? = null
    private var searchQuery: String = ""
    private val selectedHandles = LinkedHashSet<String>()
    private var allResults: List<AuditResult> = emptyList()
    // v2.0.3-gemini: usernames already merged into the selection once.
    // A refresh must never re-seed from the file (that froze the count
    // at a mid-audit snapshot) and never resurrect an emptied selection.
    private val knownUsernames = HashSet<String>()
    // Serializes selection persistence off the main thread.
    private val saveExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    private lateinit var txtServiceDot: TextView
    private lateinit var txtServiceStatus: TextView
    private lateinit var btnEnableService: Button
    private lateinit var btnEnableOverlay: Button

    private lateinit var btnSpeedTurbo: Button
    private lateinit var btnSpeedHybrid: Button
    private lateinit var btnSpeedSafe: Button

    private lateinit var txtStatTotal: TextView
    private lateinit var txtStatCandidates: TextView
    private lateinit var txtStatActive: TextView
    private lateinit var txtStatSpeed: TextView
    private lateinit var txtPhaseBanner: TextView
    private lateinit var progressAudit: ProgressBar

    private lateinit var btnStartAudit: Button
    private lateinit var btnResumeAudit: Button
    private lateinit var btnUnfollowSelected: Button
    private lateinit var btnStopRun: Button

    private lateinit var editSearch: EditText
    private lateinit var chipAll: TextView
    private lateinit var chipCandidates: TextView
    private lateinit var chipGone: TextView
    private lateinit var chipFake: TextView
    private lateinit var chipDead: TextView
    private lateinit var chipActive: TextView
    private lateinit var chipUnknown: TextView

    private lateinit var listContainer: LinearLayout
    private lateinit var txtLog: TextView

    private val statsListener: (EngineStats) -> Unit = { stats ->
        runOnUiThread { renderEngineStats(stats) }
    }

    private val logListener: () -> Unit = {
        runOnUiThread { txtLog.text = LogStore.joinText(120) }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        setupListeners()
        refreshAllData()
    }

    override fun onResume() {
        super.onResume()
        Engine.addStatsListener(statsListener)
        LogStore.addListener(logListener)
        refreshPermissionBanner()
        refreshSpeedButtons()
        refreshAllData()
    }

    override fun onPause() {
        Engine.removeStatsListener(statsListener)
        LogStore.removeListener(logListener)
        super.onPause()
    }

    private fun bindViews() {
        txtServiceDot = findViewById(R.id.txtServiceDot)
        txtServiceStatus = findViewById(R.id.txtServiceStatus)
        btnEnableService = findViewById(R.id.btnEnableService)
        btnEnableOverlay = findViewById(R.id.btnEnableOverlay)

        btnSpeedTurbo = findViewById(R.id.btnSpeedTurbo)
        btnSpeedHybrid = findViewById(R.id.btnSpeedHybrid)
        btnSpeedSafe = findViewById(R.id.btnSpeedSafe)

        txtStatTotal = findViewById(R.id.txtStatTotal)
        txtStatCandidates = findViewById(R.id.txtStatCandidates)
        txtStatActive = findViewById(R.id.txtStatActive)
        txtStatSpeed = findViewById(R.id.txtStatSpeed)
        txtPhaseBanner = findViewById(R.id.txtPhaseBanner)
        progressAudit = findViewById(R.id.progressAudit)

        btnStartAudit = findViewById(R.id.btnStartAudit)
        btnResumeAudit = findViewById(R.id.btnResumeAudit)
        btnUnfollowSelected = findViewById(R.id.btnUnfollowSelected)
        btnStopRun = findViewById(R.id.btnStopRun)

        editSearch = findViewById(R.id.editSearch)
        chipAll = findViewById(R.id.chipAll)
        chipCandidates = findViewById(R.id.chipCandidates)
        chipGone = findViewById(R.id.chipGone)
        chipFake = findViewById(R.id.chipFake)
        chipDead = findViewById(R.id.chipDead)
        chipActive = findViewById(R.id.chipActive)
        chipUnknown = findViewById(R.id.chipUnknown)

        listContainer = findViewById(R.id.listContainer)
        txtLog = findViewById(R.id.txtLog)
    }

    private fun setupListeners() {
        findViewById<View>(R.id.btnOpenSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        findViewById<View>(R.id.btnToggleLang).setOnClickListener {
            val next = if (Prefs.getLanguage(this) == "ar") "en" else "ar"
            Prefs.setLanguage(this, next)
            recreate()
        }

        btnEnableService.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        btnEnableOverlay.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }

        btnSpeedTurbo.setOnClickListener {
            Prefs.setSpeedMode(this, ScanSpeedMode.TURBO_IN_LIST)
            refreshSpeedButtons()
        }
        btnSpeedHybrid.setOnClickListener {
            Prefs.setSpeedMode(this, ScanSpeedMode.HYBRID_SMART)
            refreshSpeedButtons()
        }
        btnSpeedSafe.setOnClickListener {
            Prefs.setSpeedMode(this, ScanSpeedMode.SAFE_DEEP_LINK)
            refreshSpeedButtons()
        }

        btnStartAudit.setOnClickListener {
            if (!Engine.startAudit(resume = false)) {
                Toast.makeText(this, getString(R.string.msg_enable_service_first), Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        btnResumeAudit.setOnClickListener {
            if (!Engine.startAudit(resume = true)) {
                Toast.makeText(this, getString(R.string.msg_enable_service_first), Toast.LENGTH_LONG).show()
            }
        }

        btnUnfollowSelected.setOnClickListener {
            if (selectedHandles.isEmpty()) {
                Toast.makeText(this, getString(R.string.msg_no_candidates_selected), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            Engine.startUnfollow(HashSet(selectedHandles))
        }

        btnStopRun.setOnClickListener {
            Engine.stop()
            refreshAllData()
        }

        editSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchQuery = s?.toString()?.trim().orEmpty()
                renderResultsList()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        chipAll.setOnClickListener { setFilter(null, false) }
        chipCandidates.setOnClickListener { setFilter(null, true) }
        chipGone.setOnClickListener { setFilter(Category.GONE, false) }
        chipFake.setOnClickListener { setFilter(Category.FAKE, false) }
        chipDead.setOnClickListener { setFilter(Category.DEAD, false) }
        chipActive.setOnClickListener { setFilter(Category.ACTIVE, false) }
        chipUnknown.setOnClickListener { setFilter(Category.UNKNOWN, false) }

        findViewById<View>(R.id.btnExportCsv).setOnClickListener {
            val file = AppFiles.exportCsvFile(this, allResults)
            LogStore.log("📁 تم تصدير ملف CSV إلى: ${file.absolutePath}")
            Toast.makeText(this, "CSV: ${file.name}", Toast.LENGTH_LONG).show()
        }

        findViewById<View>(R.id.btnCopyLog).setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("TikTokCleanerLog", LogStore.joinText(500)))
            Toast.makeText(this, getString(R.string.msg_log_copied), Toast.LENGTH_SHORT).show()
        }

        findViewById<View>(R.id.btnClearLog).setOnClickListener {
            LogStore.clear()
        }
    }

    private var candidatesOnlyFilter = false

    private fun setFilter(category: Category?, candidatesOnly: Boolean) {
        activeFilter = category
        candidatesOnlyFilter = candidatesOnly
        renderResultsList()
    }

    private fun refreshPermissionBanner() {
        val connected = Engine.isServiceConnected()
        val overlayGranted = Settings.canDrawOverlays(this)

        if (connected) {
            txtServiceDot.setTextColor(Color.parseColor("#10B981"))
            txtServiceStatus.text = getString(R.string.service_status_active)
            btnEnableService.visibility = View.GONE
        } else {
            txtServiceDot.setTextColor(Color.parseColor("#FE2C55"))
            txtServiceStatus.text = getString(R.string.service_status_inactive)
            btnEnableService.visibility = View.VISIBLE
        }

        btnEnableOverlay.visibility = if (overlayGranted) View.GONE else View.VISIBLE
    }

    private fun refreshSpeedButtons() {
        val current = Prefs.getSpeedMode(this)
        styleSpeedBtn(btnSpeedTurbo, current == ScanSpeedMode.TURBO_IN_LIST)
        styleSpeedBtn(btnSpeedHybrid, current == ScanSpeedMode.HYBRID_SMART)
        styleSpeedBtn(btnSpeedSafe, current == ScanSpeedMode.SAFE_DEEP_LINK)
    }

    private fun styleSpeedBtn(btn: Button, selected: Boolean) {
        btn.setTextColor(if (selected) Color.parseColor("#0B0E14") else Color.parseColor("#94A3B8"))
        btn.background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(if (selected) Color.parseColor("#00F2FE") else Color.parseColor("#1E2638"))
        }
    }

    private fun refreshAllData() {
        allResults = AppFiles.loadAuditResults(this)
        // v2.0.3-gemini: sync, don't reseed. New candidates join the
        // selection; seen results keep the user's choice; an emptied
        // selection stays empty. (Old code seeded once when the set
        // was empty — a mid-audit snapshot — and later candidates
        // never joined, freezing the count while candidates grew.)
        knownUsernames.addAll(
            SelectionState.syncSelection(knownUsernames, selectedHandles, allResults)
        )
        renderEngineStats(Engine.stats)
        renderResultsList()
        txtLog.text = LogStore.joinText(120)
    }

    /** v2.0.3-gemini: a checkbox toggle is persisted to the results
     * file, so an Activity recreation restores the user's actual
     * selection instead of the classification-time defaults. */
    private fun persistSelection() {
        val snapshot = allResults
        val ctx = applicationContext
        saveExecutor.execute { AppFiles.saveAllAuditResults(ctx, snapshot) }
    }

    override fun onDestroy() {
        saveExecutor.shutdown()
        super.onDestroy()
    }

    private fun renderEngineStats(stats: EngineStats) {
        val totalAudited = maxOf(allResults.size, stats.auditedCount)
        val candidatesCount = allResults.count { it.category.isDefaultUnfollowCandidate() }
        val activeCount = allResults.count { it.category == Category.ACTIVE }

        txtStatTotal.text = totalAudited.toString()
        txtStatCandidates.text = candidatesCount.toString()
        txtStatActive.text = activeCount.toString()
        txtStatSpeed.text = if (stats.accountsPerMinute > 0) "${stats.accountsPerMinute}/د" else "جاهز"

        txtPhaseBanner.text = if (stats.statusMessage.isNotEmpty()) {
            stats.statusMessage
        } else {
            getString(R.string.status_ready_subtitle)
        }

        val denom = stats.totalToAudit.coerceAtLeast(1)
        progressAudit.max = denom
        progressAudit.progress = stats.auditedCount.coerceAtMost(denom)

        btnStopRun.visibility = if (Engine.isRunning()) View.VISIBLE else View.GONE
        btnUnfollowSelected.text = "${getString(R.string.btn_unfollow_selected)} (${selectedHandles.size})"
    }

    private fun renderResultsList() {
        listContainer.removeAllViews()
        val filtered = allResults.filter { r ->
            val matchesCat = when {
                candidatesOnlyFilter -> r.category.isDefaultUnfollowCandidate()
                activeFilter != null -> r.category == activeFilter
                else -> true
            }
            val matchesQuery = searchQuery.isEmpty() ||
                r.username.contains(searchQuery, ignoreCase = true) ||
                r.detail.contains(searchQuery, ignoreCase = true)
            matchesCat && matchesQuery
        }

        if (filtered.isEmpty()) {
            val emptyView = TextView(this).apply {
                text = getString(R.string.empty_results_hint)
                setTextColor(Color.parseColor("#64748B"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(28), dp(16), dp(28))
            }
            listContainer.addView(emptyView)
            return
        }

        for (item in filtered.take(150)) {
            listContainer.addView(createAccountRowView(item))
        }
    }

    private fun createAccountRowView(item: AuditResult): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#151C2C"))
                setStroke(dp(1), Color.parseColor("#243049"))
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            layoutParams = lp
        }

        val cb = CheckBox(this).apply {
            isChecked = item.username.lowercase() in selectedHandles
            setOnCheckedChangeListener { _, checked ->
                // v2.0.3-gemini: persist the toggle — previously it
                // lived only in memory and vanished on recreation.
                allResults = SelectionState.applyToggle(allResults, selectedHandles, item.username, checked)
                persistSelection()
                btnUnfollowSelected.text = "${getString(R.string.btn_unfollow_selected)} (${selectedHandles.size})"
            }
        }

        val infoCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(8)
                marginEnd = dp(8)
            }
        }

        val handleView = TextView(this).apply {
            text = "@${item.username}"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }

        val detailView = TextView(this).apply {
            text = item.detail
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }

        infoCol.addView(handleView)
        infoCol.addView(detailView)

        val badgeColor = when (item.category) {
            Category.GONE -> "#EF4444"
            Category.FAKE -> "#F59E0B"
            Category.DEAD -> "#FB7185"
            Category.ACTIVE -> "#10B981"
            Category.UNKNOWN -> "#64748B"
        }

        val badge = TextView(this).apply {
            text = item.category.name
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(Color.parseColor(badgeColor))
            }
        }

        val openBtn = Button(this).apply {
            text = "↗"
            setTextColor(Color.parseColor("#00F2FE"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(8), dp(2), dp(8), dp(2))
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(Color.parseColor("#1E293B"))
            }
            val lp = LinearLayout.LayoutParams(dp(36), dp(34)).apply { marginStart = dp(6) }
            layoutParams = lp
            setOnClickListener {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.tiktok.com/@${item.username}")))
                } catch (_: Exception) {
                }
            }
        }

        row.addView(cb)
        row.addView(infoCol)
        row.addView(badge)
        row.addView(openBtn)
        // v2.0.3-gemini: tapping the row toggles its checkbox — the
        // box alone is a tiny target. The ↗ button keeps its own
        // click (clickable children consume the touch first).
        row.setOnClickListener { cb.toggle() }
        return row
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
}
