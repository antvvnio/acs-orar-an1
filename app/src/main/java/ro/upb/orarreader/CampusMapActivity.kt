package ro.upb.orarreader

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.progressindicator.CircularProgressIndicator
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class CampusMapActivity : AppCompatActivity() {
    private lateinit var mapView: ZoomImageView
    private lateinit var chipGroup: ChipGroup
    private lateinit var progress: CircularProgressIndicator
    private lateinit var statusText: TextView
    private lateinit var resetButton: MaterialButton

    private val executor = Executors.newSingleThreadExecutor()
    private var renderer: PdfRenderer? = null
    private var descriptor: ParcelFileDescriptor? = null
    private var currentBitmap: Bitmap? = null
    private var currentPage = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_campus_map)
        applyInsets()

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        mapView = findViewById(R.id.mapImageView)
        chipGroup = findViewById(R.id.mapPageChipGroup)
        progress = findViewById(R.id.mapProgress)
        statusText = findViewById(R.id.mapStatusText)
        resetButton = findViewById(R.id.resetZoomButton)

        val pageIds = listOf(R.id.chipNoulLocal, R.id.chipLeu, R.id.chipPolizu)
        pageIds.forEachIndexed { index, id ->
            findViewById<Chip>(id).setOnClickListener { renderPage(index) }
        }
        resetButton.setOnClickListener { mapView.resetZoom() }
        findViewById<MaterialButton>(R.id.openOfficialPdfButton).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(MAP_URL)))
        }

        loadMap()
    }

    override fun onDestroy() {
        super.onDestroy()
        currentBitmap?.recycle()
        renderer?.close()
        descriptor?.close()
        executor.shutdownNow()
    }

    private fun loadMap() {
        progress.visibility = View.VISIBLE
        statusText.visibility = View.VISIBLE
        statusText.text = "Se pregătește harta oficială UPB…"
        val cacheFile = File(cacheDir, "HARTI-CAMPUS-UPB.pdf")

        executor.execute {
            val result = runCatching {
                if (!cacheFile.exists() || cacheFile.length() < 10_000L) downloadPdf(cacheFile)
                openRenderer(cacheFile)
            }
            runOnUiThread {
                result.onSuccess {
                    progress.visibility = View.GONE
                    statusText.visibility = View.GONE
                    chipGroup.visibility = View.VISIBLE
                    resetButton.isEnabled = true
                    renderPage(0)
                }.onFailure { error ->
                    progress.visibility = View.GONE
                    statusText.visibility = View.VISIBLE
                    statusText.text = "Nu am putut descărca harta. Verifică internetul sau deschide PDF-ul oficial."
                    Toast.makeText(this, error.message ?: "Eroare la hartă", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun downloadPdf(target: File) {
        val temp = File(target.parentFile, "${target.name}.tmp")
        if (temp.exists()) temp.delete()
        val connection = URL(MAP_URL).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "ACS-Orar/1.9 Android")
        try {
            val code = connection.responseCode
            require(code in 200..299) { "Serverul UPB a răspuns cu HTTP $code." }
            connection.inputStream.use { input ->
                FileOutputStream(temp).use { output -> input.copyTo(output) }
            }
            require(temp.length() > 10_000L) { "PDF-ul descărcat pare incomplet." }
            if (target.exists()) target.delete()
            require(temp.renameTo(target)) { "Nu am putut salva harta în cache." }
        } finally {
            connection.disconnect()
            if (temp.exists()) temp.delete()
        }
    }

    private fun openRenderer(file: File) {
        renderer?.close()
        descriptor?.close()
        descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        renderer = PdfRenderer(requireNotNull(descriptor))
        require(requireNotNull(renderer).pageCount >= 3) { "PDF-ul oficial nu mai are formatul așteptat." }
    }

    private fun renderPage(index: Int) {
        val pdf = renderer ?: return
        if (index !in 0 until pdf.pageCount) return
        currentPage = index
        progress.visibility = View.VISIBLE
        statusText.visibility = View.VISIBLE
        statusText.text = "Se randază ${PAGE_NAMES.getOrElse(index) { "pagina ${index + 1}" }}…"
        for (i in 0 until chipGroup.childCount) {
            (chipGroup.getChildAt(i) as? Chip)?.isChecked = i == index
        }

        executor.execute {
            val result = runCatching {
                pdf.openPage(index).use { page ->
                    val targetWidth = 2400
                    val targetHeight = (targetWidth.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                    Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888).also { bitmap ->
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }
            runOnUiThread {
                result.onSuccess { bitmap ->
                    currentBitmap?.recycle()
                    currentBitmap = bitmap
                    mapView.setImageBitmap(bitmap)
                    progress.visibility = View.GONE
                    statusText.visibility = View.GONE
                }.onFailure { error ->
                    progress.visibility = View.GONE
                    statusText.visibility = View.VISIBLE
                    statusText.text = "Nu am putut afișa pagina ${index + 1}."
                    Toast.makeText(this, error.message ?: "Eroare PDF", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun applyInsets() {
        val root = findViewById<View>(R.id.campusMapRoot)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    companion object {
        const val MAP_URL = "https://upb.ro/wp-content/uploads/2017/11/HARTI-CAMPUS-UPB.pdf"
        private val PAGE_NAMES = listOf("Campus Noul Local", "Campus Leu", "Campus Polizu")
    }
}
