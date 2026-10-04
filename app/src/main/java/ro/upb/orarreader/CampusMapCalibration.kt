package ro.upb.orarreader

import android.graphics.PointF
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Georeferencing for page 1 (Campus Noul Local) of the official UPB campus map.
 *
 * Calibration reference image: 3000 x 2000 px.
 * The affine transform is a least-squares fit over four GPS/pixel control points.
 */
object CampusMapCalibration {
    private const val REFERENCE_WIDTH = 3000.0
    private const val REFERENCE_HEIGHT = 2000.0
    private const val REFERENCE_LAT = 44.438
    private const val REFERENCE_LON = 26.050

    // x = X_LON * dLon + X_LAT * dLat + X_OFFSET
    private const val X_LON = 124637.30616606
    private const val X_LAT = 1709.37792033
    private const val X_OFFSET = 1598.71571674

    // y = Y_LON * dLon + Y_LAT * dLat + Y_OFFSET
    private const val Y_LON = -100.381885
    private const val Y_LAT = -171517.108
    private const val Y_OFFSET = 1254.03891

    private const val METERS_PER_DEGREE_LAT = 111_320.0

    data class ReferencePoint(
        val x: Double,
        val y: Double,
    )

    fun toReferencePixel(latitude: Double, longitude: Double): ReferencePoint {
        val dLon = longitude - REFERENCE_LON
        val dLat = latitude - REFERENCE_LAT
        return ReferencePoint(
            x = X_LON * dLon + X_LAT * dLat + X_OFFSET,
            y = Y_LON * dLon + Y_LAT * dLat + Y_OFFSET,
        )
    }

    fun isInsideMap(point: ReferencePoint): Boolean =
        point.x in 0.0..REFERENCE_WIDTH && point.y in 0.0..REFERENCE_HEIGHT

    fun toDrawablePoint(
        latitude: Double,
        longitude: Double,
        drawableWidth: Int,
        drawableHeight: Int,
    ): PointF? {
        if (drawableWidth <= 0 || drawableHeight <= 0) return null
        val point = toReferencePixel(latitude, longitude)
        if (!isInsideMap(point)) return null
        return PointF(
            (point.x / REFERENCE_WIDTH * drawableWidth).toFloat(),
            (point.y / REFERENCE_HEIGHT * drawableHeight).toFloat(),
        )
    }

    fun accuracyRadiusInDrawable(
        latitude: Double,
        longitude: Double,
        accuracyMeters: Float,
        drawableWidth: Int,
        drawableHeight: Int,
    ): Float {
        if (accuracyMeters <= 0f) return 0f
        val center = toReferencePixel(latitude, longitude)
        val latDelta = accuracyMeters / METERS_PER_DEGREE_LAT
        val lonMetersPerDegree = METERS_PER_DEGREE_LAT * cos(Math.toRadians(latitude))
        val lonDelta = if (lonMetersPerDegree > 1.0) accuracyMeters / lonMetersPerDegree else 0.0

        val north = toReferencePixel(latitude + latDelta, longitude)
        val east = toReferencePixel(latitude, longitude + lonDelta)
        val radiusReference = (
            hypot(north.x - center.x, north.y - center.y) +
                hypot(east.x - center.x, east.y - center.y)
            ) / 2.0

        val scaleX = drawableWidth / REFERENCE_WIDTH
        val scaleY = drawableHeight / REFERENCE_HEIGHT
        return (radiusReference * (scaleX + scaleY) / 2.0).toFloat()
    }

    /**
     * Returns the direction angle in drawable coordinates, where 0° points right and
     * positive angles rotate clockwise because image Y grows downwards.
     */
    fun headingAngleInDrawable(
        latitude: Double,
        longitude: Double,
        headingDegrees: Float,
    ): Float {
        val distanceMeters = 10.0
        val heading = Math.toRadians(headingDegrees.toDouble())
        val northMeters = cos(heading) * distanceMeters
        val eastMeters = sin(heading) * distanceMeters

        val latDelta = northMeters / METERS_PER_DEGREE_LAT
        val lonMetersPerDegree = METERS_PER_DEGREE_LAT * cos(Math.toRadians(latitude))
        val lonDelta = if (lonMetersPerDegree > 1.0) eastMeters / lonMetersPerDegree else 0.0

        val start = toReferencePixel(latitude, longitude)
        val end = toReferencePixel(latitude + latDelta, longitude + lonDelta)
        return Math.toDegrees(kotlin.math.atan2(end.y - start.y, end.x - start.x)).toFloat()
    }
}
