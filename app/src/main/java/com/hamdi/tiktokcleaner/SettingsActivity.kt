package com.hamdi.tiktokcleaner

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast

/**
 * v1.7: adds a language switcher (AR default / EN). The choice is persisted
 * under key "uiLang" in the SAME "cleaner_prefs" SharedPreferences file,
 * WITHOUT touching Prefs.kt (delivery constraint). Applying the choice is
 * immediate: this Activity recreates itself; MainActivity picks up the
 * change in onResume and recreates too.
 *
 * All v1.6 thresholds/pacing wiring preserved. NOT BUILT, NOT TESTED.
 */
class SettingsActivity : Activity() {

    private lateinit var prefs: Prefs

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        prefs = Prefs(this)

        val edtThreshold = findViewById<EditText>(R.id.edtThresholdDays)
        val edtDayCap = findViewById<EditText>(R.id.edtDayCap)
        val edtHourCap = findViewById<EditText>(R.id.edtHourCap)
        val edtGapMin = findViewById<EditText>(R.id.edtGapMin)
        val edtGapMax = findViewById<EditText>(R.id.edtGapMax)
        val edtBatch = findViewById<EditText>(R.id.edtBatchSize)

        fun load() {
            edtThreshold.setText(prefs.thresholdDays.toString())
            edtDayCap.setText(prefs.dayCap.toString())
            edtHourCap.setText(prefs.hourCap.toString())
            edtGapMin.setText(prefs.gapMinSec.toString())
            edtGapMax.setText(prefs.gapMaxSec.toString())
            edtBatch.setText(prefs.batchSize.toString())
        }
        load()

        // -------- language radio group --------
        val radioGroup = findViewById<RadioGroup>(R.id.radioLangGroup)
        val radioAr = findViewById<RadioButton>(R.id.radioLangArabic)
        val radioEn = findViewById<RadioButton>(R.id.radioLangEnglish)
        val currentLang = LocaleHelper.readLang(this)
        if (currentLang == LocaleHelper.LANG_EN) radioEn.isChecked = true else radioAr.isChecked = true

        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            val newLang = when (checkedId) {
                R.id.radioLangEnglish -> LocaleHelper.LANG_EN
                else -> LocaleHelper.LANG_AR
            }
            if (newLang != LocaleHelper.readLang(this)) {
                LocaleHelper.writeLang(this, newLang)
                Toast.makeText(this, R.string.settings_lang_changed_toast, Toast.LENGTH_SHORT).show()
                // Apply immediately (option ج). MainActivity.onResume will
                // detect the change and recreate itself when we return.
                recreate()
            }
        }

        // -------- save thresholds/pacing (unchanged logic) --------
        findViewById<Button>(R.id.btnSaveSettings).setOnClickListener {
            fun num(e: EditText, fallback: Int) = e.text.toString().toIntOrNull() ?: fallback
            prefs.thresholdDays = num(edtThreshold, prefs.thresholdDays)
            prefs.dayCap = num(edtDayCap, prefs.dayCap)
            prefs.hourCap = num(edtHourCap, prefs.hourCap)
            prefs.gapMinSec = num(edtGapMin, prefs.gapMinSec)
            prefs.gapMaxSec = num(edtGapMax, prefs.gapMaxSec)
            prefs.batchSize = num(edtBatch, prefs.batchSize)
            load()
            Toast.makeText(
                this,
                getString(
                    R.string.settings_saved_toast,
                    prefs.dayCap, prefs.hourCap, prefs.thresholdDays
                ),
                Toast.LENGTH_LONG
            ).show()
            LogStore.log(
                "إعدادات جديدة: خمول=${prefs.thresholdDays} يوم، حد يومي=${prefs.dayCap}، " +
                    "حد ساعة=${prefs.hourCap}، فاصل=${prefs.gapMinSec}–${prefs.gapMaxSec} ث، دفعة=${prefs.batchSize}"
            )
        }
    }
}
