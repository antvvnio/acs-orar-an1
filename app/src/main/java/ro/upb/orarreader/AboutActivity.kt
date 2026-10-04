package ro.upb.orarreader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView

class AboutActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_about)
        applyInsets()

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        val version = packageManager.getPackageInfo(packageName, 0).versionName ?: "1.2"
        findViewById<TextView>(R.id.versionText).text = "Versiunea $version"
        findViewById<MaterialCardView>(R.id.githubCard).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/antvvnio")))
        }
        findViewById<TextView>(R.id.mapSourceText).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(CampusMapActivity.MAP_URL)))
        }
    }

    private fun applyInsets() {
        val root = findViewById<View>(R.id.aboutRoot)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }
}
