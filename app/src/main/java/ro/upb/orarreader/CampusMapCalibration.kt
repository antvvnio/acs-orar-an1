package ro.upb.orarreader

import android.graphics.PointF
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Georeferencing for the three official UPB campus map pages.
 *
 * Each page uses an affine least-squares fit over four GPS/pixel control points.
 * Reference image sizes are the images used while collecting calibration points;
 * coordinates are scaled automatically to the bitmap rendered in the app.
 */
object CampusMapCalibration {
    private const val METERS_PER_DEGREE_LAT = 111_320.0

    private data class Calibration(
        val referenceWidth: Double,
        val referenceHeight: Double,
        val referenceLat: Double,
        val referenceLon: Double,
        val xLon: Double,
        val xLat: Double,
        val xOffset: Double,
        val yLon: Double,
        val yLat: Double,
        val yOffset: Double,
    )

    data class ReferencePoint(
        val x: Double,
        val y: Double,
    )

    private val calibrations = mapOf(
        // Campus Noul Local — reference image 3000 x 2000.
        0 to Calibration(
            referenceWidth = 3000.0,
            referenceHeight = 2000.0,
            referenceLat = 44.438,
            referenceLon = 26.050,
            xLon = 124637.30616606,
            xLat = 1709.37792033,
            xOffset = 1598.71571674,
            yLon = -100.381885,
            yLat = -171517.108,
            yOffset = 1254.03891,
        ),

        // Campus Leu — reference image 1408 x 1408.
        1 to Calibration(
            referenceWidth = 1408.0,
            referenceHeight = 1408.0,
            referenceLat = 44.433,
            referenceLon = 26.057,
            xLon = 194899.656,
            xLat = 85.0979235,
            xOffset = 742.733276,
            yLon = -1488.17733952,
            yLat = -271582.15381498,
            yOffset = 869.26082667,
        ),

        // Campus Polizu — reference image 1408 x 1408.
        2 to Calibration(
            referenceWidth = 1408.0,
            referenceHeight = 1408.0,
            referenceLat = 44.448,
            referenceLon = 26.078,
            xLon = 205755.45091464,
            xLat = -226069.89843815,
            xOffset = 528.96157374,
            yLon = -136565.42568266,
            yLat = -245434.21647465,
            yOffset = 786.83181708,
        ),
    )

    fun hasCalibration(pageIndex: Int): Boolean = calibrations.containsKey(pageIndex)

    private fun calibration(pageIndex: Int): Calibration? = calibrations[pageIndex]

    private fun toReferencePixel(
        calibration: Calibration,
        latitude: Double,
        longitude: Double,
    ): ReferencePoint {
        val dLon = longitude - calibration.referenceLon
        val dLat = latitude - calibration.referenceLat
        return ReferencePoint(
            x = calibration.xLon * dLon + calibration.xLat * dLat + calibration.xOffset,
            y = calibration.yLon * dLon + calibration.yLat * dLat + calibration.yOffset,
        )
    }

    private fun isInsideMap(calibration: Calibration, point: ReferencePoint): Boolean =
        point.x in 0.0..calibration.referenceWidth &&
            point.y in 0.0..calibration.referenceHeight

    fun toDrawablePoint(
        pageIndex: Int,
        latitude: Double,
        longitude: Double,
        drawableWidth: Int,
        drawableHeight: Int,
    ): PointF? {
        val calibration = calibration(pageIndex) ?: return null
        if (drawableWidth <= 0 || drawableHeight <= 0) return null

        val point = toReferencePixel(calibration, latitude, longitude)
        if (!isInsideMap(calibration, point)) return null

        return PointF(
            (point.x / calibration.referenceWidth * drawableWidth).toFloat(),
            (point.y / calibration.referenceHeight * drawableHeight).toFloat(),
        )
    }

    fun accuracyRadiusInDrawable(
        pageIndex: Int,
        latitude: Double,
        longitude: Double,
        accuracyMeters: Float,
        drawableWidth: Int,
        drawableHeight: Int,
    ): Float {
        val calibration = calibration(pageIndex) ?: return 0f
        if (accuracyMeters <= 0f) return 0f

        val center = toReferencePixel(calibration, latitude, longitude)
        val latDelta = accuracyMeters / METERS_PER_DEGREE_LAT
        val lonMetersPerDegree = METERS_PER_DEGREE_LAT * cos(Math.toRadians(latitude))
        val lonDelta = if (lonMetersPerDegree > 1.0) accuracyMeters / lonMetersPerDegree else 0.0

        val north = toReferencePixel(calibration, latitude + latDelta, longitude)
        val east = toReferencePixel(calibration, latitude, longitude + lonDelta)
        val radiusReference = (
            hypot(north.x - center.x, north.y - center.y) +
                hypot(east.x - center.x, east.y - center.y)
            ) / 2.0

        val scaleX = drawableWidth / calibration.referenceWidth
        val scaleY = drawableHeight / calibration.referenceHeight
        return (radiusReference * (scaleX + scaleY) / 2.0).toFloat()
    }

    /**
     * Returns the direction angle in drawable coordinates, where 0° points right and
     * positive angles rotate clockwise because image Y grows downwards.
     */
    fun headingAngleInDrawable(
        pageIndex: Int,
        latitude: Double,
        longitude: Double,
        headingDegrees: Float,
    ): Float? {
        val calibration = calibration(pageIndex) ?: return null
        val distanceMeters = 10.0
        val heading = Math.toRadians(headingDegrees.toDouble())
        val northMeters = cos(heading) * distanceMeters
        val eastMeters = sin(heading) * distanceMeters

        val latDelta = northMeters / METERS_PER_DEGREE_LAT
        val lonMetersPerDegree = METERS_PER_DEGREE_LAT * cos(Math.toRadians(latitude))
        val lonDelta = if (lonMetersPerDegree > 1.0) eastMeters / lonMetersPerDegree else 0.0

        val start = toReferencePixel(calibration, latitude, longitude)
        val end = toReferencePixel(calibration, latitude + latDelta, longitude + lonDelta)
        return Math.toDegrees(
            kotlin.math.atan2(end.y - start.y, end.x - start.x)
        ).toFloat()
    }
}
