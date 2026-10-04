package ro.upb.orarreader

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.Surface
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
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.floatingactionbutton.FloatingActionButton
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
    private lateinit var locationButton: FloatingActionButton
    private lateinit var locationManager: LocationManager
    private lateinit var sensorManager: SensorManager

    private val executor = Executors.newSingleThreadExecutor()
    private var renderer: PdfRenderer? = null
    private var descriptor: ParcelFileDescriptor? = null
    private var currentBitmap: Bitmap? = null
    private var currentPage = 0

    private var rotationVectorSensor: Sensor? = null
    private var locationModeRequested = false
    private var locationUpdatesActive = false
    private var pendingCenterOnLocation = false
    private var outsideMapNoticeShown = false
    private var lastLocation: Location? = null
    private var currentHeadingDegrees: Float? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted =
            result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            locationModeRequested = true
            pendingCenterOnLocation = true
            startLocationTracking()
        } else {
            locationModeRequested = false
            Toast.makeText(
                this,
                "Permisiunea de locație este necesară pentru poziția pe hartă.",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            lastLocation = location
            updateMapLocation(center = pendingCenterOnLocation)
        }

        override fun onProviderEnabled(provider: String) = Unit

        override fun onProviderDisabled(provider: String) = Unit

        @Deprecated("Deprecated in Android")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return

            val rotationMatrix = FloatArray(9)
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            val adjusted = FloatArray(9)

            @Suppress("DEPRECATION")
            val rotation = windowManager.defaultDisplay.rotation
            val (axisX, axisY) = when (rotation) {
                Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
                Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
                Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
                else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
            }
            SensorManager.remapCoordinateSystem(rotationMatrix, axisX, axisY, adjusted)

            val orientation = FloatArray(3)
            SensorManager.getOrientation(adjusted, orientation)
            var heading = Math.toDegrees(orientation[0].toDouble()).toFloat()
            val location = lastLocation
            if (location != null) {
                val field = GeomagneticField(
                    location.latitude.toFloat(),
                    location.longitude.toFloat(),
                    location.altitude.toFloat(),
                    System.currentTimeMillis(),
                )
                heading += field.declination
            }
            currentHeadingDegrees = (heading + 360f) % 360f
            updateMapLocation(center = false)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_campus_map)
        applyInsets()

        locationManager = getSystemService(LocationManager::class.java)
        sensorManager = getSystemService(SensorManager::class.java)
        rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        mapView = findViewById(R.id.mapImageView)
        chipGroup = findViewById(R.id.mapPageChipGroup)
        progress = findViewById(R.id.mapProgress)
        statusText = findViewById(R.id.mapStatusText)
        resetButton = findViewById(R.id.resetZoomButton)
        locationButton = findViewById(R.id.myLocationButton)

        val pageIds = listOf(R.id.chipNoulLocal, R.id.chipLeu, R.id.chipPolizu)
        pageIds.forEachIndexed { index, id ->
            findViewById<Chip>(id).setOnClickListener { renderPage(index) }
        }
        resetButton.setOnClickListener { mapView.resetZoom() }
        locationButton.setOnClickListener { requestOrCenterLocation() }
        findViewById<MaterialButton>(R.id.openOfficialPdfButton).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(MAP_URL)))
        }

        loadMap()
    }

    override fun onResume() {
        super.onResume()
        if (
            locationModeRequested &&
            CampusMapCalibration.hasCalibration(currentPage) &&
            hasLocationPermission()
        ) {
            startLocationTracking()
        }
    }

    override fun onPause() {
        stopLocationTracking()
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        currentBitmap?.recycle()
        renderer?.close()
        descriptor?.close()
        executor.shutdownNow()
    }

    private fun requestOrCenterLocation() {
        locationModeRequested = true
        pendingCenterOnLocation = true
        outsideMapNoticeShown = false

        if (!hasLocationPermission()) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                )
            )
            return
        }

        if (lastLocation != null) {
            updateMapLocation(center = true)
        }
        startLocationTracking()
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun startLocationTracking() {
        if (
            !locationModeRequested ||
            !CampusMapCalibration.hasCalibration(currentPage) ||
            !hasLocationPermission()
        ) return

        val providers = buildList {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                add(LocationManager.GPS_PROVIDER)
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                add(LocationManager.NETWORK_PROVIDER)
            }
        }

        if (providers.isEmpty()) {
            mapView.clearMapLocation()
            Toast.makeText(
                this,
                "Locația telefonului este oprită.",
                Toast.LENGTH_SHORT,
            ).show()
            return
        }

        if (!locationUpdatesActive) {
            providers.forEach { provider ->
                locationManager.requestLocationUpdates(
                    provider,
                    1_000L,
                    0.5f,
                    locationListener,
                )
            }
            locationUpdatesActive = true
        }

        val bestLastKnown = providers
            .mapNotNull { provider -> runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull() }
            .maxByOrNull { it.time }
        if (bestLastKnown != null) {
            lastLocation = bestLastKnown
            updateMapLocation(center = pendingCenterOnLocation)
        }

        rotationVectorSensor?.let { sensor ->
            sensorManager.registerListener(
                sensorListener,
                sensor,
                SensorManager.SENSOR_DELAY_UI,
            )
        }
    }

    private fun stopLocationTracking() {
        if (locationUpdatesActive && hasLocationPermission()) {
            runCatching { locationManager.removeUpdates(locationListener) }
        }
        locationUpdatesActive = false
        sensorManager.unregisterListener(sensorListener)
    }

    private fun updateMapLocation(center: Boolean) {
        if (!locationModeRequested || !CampusMapCalibration.hasCalibration(currentPage)) {
            mapView.clearMapLocation()
            return
        }

        val location = lastLocation ?: return
        val drawable = mapView.drawable ?: return
        val point = CampusMapCalibration.toDrawablePoint(
            pageIndex = currentPage,
            latitude = location.latitude,
            longitude = location.longitude,
            drawableWidth = drawable.intrinsicWidth,
            drawableHeight = drawable.intrinsicHeight,
        )

        if (point == null) {
            mapView.clearMapLocation()
            pendingCenterOnLocation = false
            if (center && !outsideMapNoticeShown) {
                outsideMapNoticeShown = true
                Toast.makeText(
                    this,
                    "Ești în afara zonei acoperite de această hartă.",
                    Toast.LENGTH_SHORT,
                ).show()
            }
            return
        }

        outsideMapNoticeShown = false
        val accuracyRadius = CampusMapCalibration.accuracyRadiusInDrawable(
            pageIndex = currentPage,
            latitude = location.latitude,
            longitude = location.longitude,
            accuracyMeters = location.accuracy,
            drawableWidth = drawable.intrinsicWidth,
            drawableHeight = drawable.intrinsicHeight,
        )
        val headingAngle = currentHeadingDegrees?.let { heading ->
            CampusMapCalibration.headingAngleInDrawable(
                pageIndex = currentPage,
                latitude = location.latitude,
                longitude = location.longitude,
                headingDegrees = heading,
            )
        }

        mapView.setMapLocation(
            drawableX = point.x,
            drawableY = point.y,
            accuracyRadiusDrawable = accuracyRadius,
            headingDegrees = headingAngle,
        )

        if (center) {
            mapView.focusOnDrawablePoint(point.x, point.y)
        }
        pendingCenterOnLocation = false
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
        val locationAvailable = CampusMapCalibration.hasCalibration(index)
        locationButton.visibility = if (locationAvailable) View.VISIBLE else View.GONE
        locationButton.isEnabled = false
        mapView.clearMapLocation()
        stopLocationTracking()

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
                    locationButton.isEnabled = locationAvailable

                    if (locationAvailable && locationModeRequested && hasLocationPermission()) {
                        updateMapLocation(center = pendingCenterOnLocation)
                        startLocationTracking()
                    }
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
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
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
