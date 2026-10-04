package ro.upb.orarreader.parser

import android.content.res.AssetManager
import org.json.JSONArray
import org.json.JSONObject
import ro.upb.orarreader.model.GridCell
import ro.upb.orarreader.model.GridRange
import ro.upb.orarreader.model.GridSheet
import ro.upb.orarreader.model.GridStyleCell

object BundledScheduleReader {
    fun read(assets: AssetManager, series: String): GridSheet {
        val normalizedSeries = series.uppercase()
        val text = assets.open("bundled_schedules/$normalizedSeries.json")
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
        val obj = JSONObject(text)
        val cells = parseCells(obj.getJSONArray("cells"))
        val styles = obj.optJSONArray("styles")?.let(::parseStyles).orEmpty()
        val merges = obj.optJSONArray("merges")?.let(::parseMerges).orEmpty()

        return GridSheet(
            name = "Orar1$series.xls",
            cells = cells,
            styleCells = styles,
            mergedRanges = merges,
        )
    }

    private fun parseCells(rows: JSONArray): List<GridCell> = buildList(rows.length()) {
        for (index in 0 until rows.length()) {
            val row = rows.getJSONArray(index)
            add(
                GridCell(
                    rowStart = row.getInt(0),
                    rowEnd = row.getInt(1),
                    colStart = row.getInt(2),
                    colEnd = row.getInt(3),
                    text = row.getString(4),
                )
            )
        }
    }

    private fun parseStyles(rows: JSONArray): List<GridStyleCell> = buildList(rows.length()) {
        for (index in 0 until rows.length()) {
            val row = rows.getJSONArray(index)
            val mask = row.getInt(2)
            add(
                GridStyleCell(
                    row = row.getInt(0),
                    col = row.getInt(1),
                    topBorder = mask and 1 != 0,
                    bottomBorder = mask and 2 != 0,
                    leftBorder = mask and 4 != 0,
                    rightBorder = mask and 8 != 0,
                )
            )
        }
    }

    private fun parseMerges(rows: JSONArray): List<GridRange> = buildList(rows.length()) {
        for (index in 0 until rows.length()) {
            val row = rows.getJSONArray(index)
            add(
                GridRange(
                    rowStart = row.getInt(0),
                    rowEnd = row.getInt(1),
                    colStart = row.getInt(2),
                    colEnd = row.getInt(3),
                )
            )
        }
    }
}
