package com.hamdi.tiktokcleaner

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Toast

class SettingsActivity : Activity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val editThreshold = findViewById<EditText>(R.id.editThresholdDays)
        val editMaxBatch = findViewById<EditText>(R.id.editMaxBatch)
        val cbGone = findViewById<CheckBox>(R.id.cbUnfollowGone)
        val cbFake = findViewById<CheckBox>(R.id.cbUnfollowFake)
        val cbDead = findViewById<CheckBox>(R.id.cbUnfollowDead)
        val cbProtectFriends = findViewById<CheckBox>(R.id.cbProtectFriends)
        val btnSave = findViewById<Button>(R.id.btnSaveSettings)
        val btnClearData = findViewById<Button>(R.id.btnClearData)

        editThreshold.setText(Prefs.getThresholdDays(this).toString())
        editMaxBatch.setText(Prefs.getMaxBatchUnfollow(this).toString())
        cbGone.isChecked = Prefs.isUnfollowGoneEnabled(this)
        cbFake.isChecked = Prefs.isUnfollowFakeEnabled(this)
        cbDead.isChecked = Prefs.isUnfollowDeadEnabled(this)
        cbProtectFriends.isChecked = Prefs.isProtectFriendsEnabled(this)

        btnSave.setOnClickListener {
            val days = editThreshold.text.toString().toIntOrNull() ?: 180
            val batch = editMaxBatch.text.toString().toIntOrNull() ?: 150
            Prefs.setThresholdDays(this, days)
            Prefs.setMaxBatchUnfollow(this, batch)
            Prefs.setUnfollowGoneEnabled(this, cbGone.isChecked)
            Prefs.setUnfollowFakeEnabled(this, cbFake.isChecked)
            Prefs.setUnfollowDeadEnabled(this, cbDead.isChecked)
            Prefs.setProtectFriendsEnabled(this, cbProtectFriends.isChecked)
            Toast.makeText(this, getString(R.string.settings_saved), Toast.LENGTH_SHORT).show()
            finish()
        }

        btnClearData.setOnClickListener {
            AppFiles.clearAll(this)
            LogStore.clear()
            Toast.makeText(this, getString(R.string.data_cleared), Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
