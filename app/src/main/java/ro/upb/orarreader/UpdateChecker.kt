package ro.upb.orarreader

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

object UpdateChecker {
    private const val API_URL = "https://api.github.com/repos/antvvnio/acs-orar-an1/releases/latest"
    private const val RELEASES_URL = "https://github.com/antvvnio/acs-orar-an1/releases/latest"
    private const val PREFS = "update_checker"
    private const val KEY_REMIND_AFTER = "remind_after"
    private const val REMIND_LATER_MS = 24L * 60L * 60L * 1000L

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    data class Release(
        val version: String,
        val url: String,
    )

    fun checkAutomatically(activity: Activity) {
        val preferences = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val remindAfter = preferences.getLong(KEY_REMIND_AFTER, 0L)
        if (now < remindAfter) return

        check(activity, manual = false)
    }

    fun checkManually(activity: Activity) {
        check(activity, manual = true)
    }

    private fun check(activity: Activity, manual: Boolean) {
        executor.execute {
            val result = runCatching { fetchLatestRelease() }
            mainHandler.post {
                if (activity.isFinishing || activity.isDestroyed) return@post

                result.onSuccess { release ->
                    val current = currentVersion(activity)
                    if (isNewer(release.version, current)) {
                        showUpdateDialog(activity, current, release)
                    } else if (manual) {
                        Toast.makeText(
                            activity,
                            "Ai deja ultima versiune ($current).",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }.onFailure {
                    if (manual) {
                        Toast.makeText(
                            activity,
                            "Nu am putut verifica actualizările. Verifică conexiunea la internet.",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }
        }
    }

    private fun fetchLatestRelease(): Release {
        val connection = (URL(API_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 8_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "ACS-Orar-Android")
        }

        try {
            if (connection.responseCode !in 200..299) {
                error("GitHub returned HTTP ${connection.responseCode}")
            }

            val json = connection.inputStream.bufferedReader().use { it.readText() }
            val objectValue = JSONObject(json)
            val tag = objectValue.getString("tag_name").trim()
            val version = tag.removePrefix("v").removePrefix("V")
            val url = objectValue.optString("html_url").takeIf { it.startsWith("https://github.com/") }
                ?: RELEASES_URL
            return Release(version = version, url = url)
        } finally {
            connection.disconnect()
        }
    }

    private fun showUpdateDialog(
        activity: Activity,
        currentVersion: String,
        release: Release,
    ) {
        MaterialAlertDialogBuilder(activity)
            .setTitle("Update nou")
            .setMessage(
                "Este disponibilă o versiune nouă de ACS Orar.\n\n" +
                    "Versiunea instalată: $currentVersion\n" +
                    "Versiunea nouă: ${release.version}"
            )
            .setNegativeButton("Nu acum", null)
            .setNeutralButton("Amintește-mi mai târziu") { _, _ ->
                activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putLong(KEY_REMIND_AFTER, System.currentTimeMillis() + REMIND_LATER_MS)
                    .apply()
            }
            .setPositiveButton("Actualizează") { _, _ ->
                activity.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(release.url))
                )
            }
            .show()
    }

    private fun currentVersion(context: Context): String {
        return context.packageManager
            .getPackageInfo(context.packageName, 0)
            .versionName
            ?.removePrefix("v")
            ?.removePrefix("V")
            .orEmpty()
            .ifBlank { "0" }
    }

    internal fun isNewer(candidate: String, current: String): Boolean {
        val candidateParts = numericVersionParts(candidate)
        val currentParts = numericVersionParts(current)
        val size = maxOf(candidateParts.size, currentParts.size)

        for (index in 0 until size) {
            val candidateValue = candidateParts.getOrElse(index) { 0 }
            val currentValue = currentParts.getOrElse(index) { 0 }
            if (candidateValue != currentValue) return candidateValue > currentValue
        }
        return false
    }

    private fun numericVersionParts(value: String): List<Int> {
        val clean = value.trim().removePrefix("v").removePrefix("V")
        return clean
            .split('.')
            .map { part -> part.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
    }
}
