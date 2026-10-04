package ro.upb.orarreader

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import ro.upb.orarreader.notifications.ReminderScheduler

class ReminderSettingsActivity : AppCompatActivity() {
    private lateinit var enabledSwitch: SwitchMaterial
    private lateinit var leadSlider: Slider
    private lateinit var leadValue: TextView
    private lateinit var permissionHint: TextView

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            persistAndSchedule(true)
        } else {
            enabledSwitch.isChecked = false
            getSharedPreferences(ReminderScheduler.PREFS_NAME, MODE_PRIVATE)
                .edit().putBoolean(ReminderScheduler.KEY_ENABLED, false).apply()
            Toast.makeText(this, "Fără permisiune nu pot trimite notificările de orar.", Toast.LENGTH_LONG).show()
            updatePermissionHint()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_reminder_settings)
        applyInsets()

        findViewById<MaterialToolbar>(R.id.toolbar).apply {
            setNavigationOnClickListener { finish() }
        }
        enabledSwitch = findViewById(R.id.remindersEnabledSwitch)
        leadSlider = findViewById(R.id.leadSlider)
        leadValue = findViewById(R.id.leadValueText)
        permissionHint = findViewById(R.id.permissionHintText)

        val prefs = getSharedPreferences(ReminderScheduler.PREFS_NAME, MODE_PRIVATE)
        enabledSwitch.isChecked = prefs.getBoolean(ReminderScheduler.KEY_ENABLED, false)
        leadSlider.value = prefs.getInt(ReminderScheduler.KEY_MINUTES, 15).coerceIn(0, 120).toFloat()
        updateLeadLabel(leadSlider.value.toInt())
        updatePermissionHint()

        leadSlider.addOnChangeListener { _, value, _ -> updateLeadLabel(value.toInt()) }
        findViewById<MaterialButton>(R.id.saveReminderButton).setOnClickListener { save() }
        findViewById<MaterialButton>(R.id.notificationSettingsButton).setOnClickListener { openNotificationSettings() }
    }

    private fun save() {
        val wantsEnabled = enabledSwitch.isChecked
        if (wantsEnabled && Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        persistAndSchedule(wantsEnabled)
    }

    private fun persistAndSchedule(enabled: Boolean) {
        val minutes = leadSlider.value.toInt().coerceIn(0, 120)
        getSharedPreferences(ReminderScheduler.PREFS_NAME, MODE_PRIVATE).edit()
            .putBoolean(ReminderScheduler.KEY_ENABLED, enabled)
            .putInt(ReminderScheduler.KEY_MINUTES, minutes)
            .apply()
        if (enabled) ReminderScheduler.rescheduleAsync(this) else ReminderScheduler.cancelAll(this)
        Toast.makeText(
            this,
            if (enabled) "Notificările au fost activate." else "Notificările au fost dezactivate.",
            Toast.LENGTH_SHORT,
        ).show()
        finish()
    }

    private fun updateLeadLabel(minutes: Int) {
        leadValue.text = when (minutes) {
            0 -> "La ora de început"
            1 -> "Cu 1 minut înainte"
            else -> "Cu $minutes minute înainte"
        }
    }

    private fun updatePermissionHint() {
        val allowed = ReminderScheduler.isNotificationPermissionGranted(this)
        permissionHint.text = if (allowed) {
            "Permisiunea de notificări este disponibilă. Mementourile se programează local pe telefon."
        } else {
            "Android blochează momentan notificările pentru aplicație. Activează-le când salvezi sau din setările sistemului."
        }
    }

    private fun openNotificationSettings() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            data = Uri.parse("package:$packageName")
        }
        runCatching { startActivity(intent) }
    }

    private fun applyInsets() {
        val root = findViewById<View>(R.id.reminderSettingsRoot)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }
}
