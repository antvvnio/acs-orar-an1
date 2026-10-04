package ro.upb.orarreader.parser

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import ro.upb.orarreader.model.GridCell
import ro.upb.orarreader.model.GridSheet
import ro.upb.orarreader.model.GridRange
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Lightweight XLSX reader for timetable-style workbooks.
 * It intentionally reads only what the schedule parser needs: strings, cell positions and merged cells.
 */
object XlsxReader {
    fun read(input: InputStream): GridSheet {
        val entries = unzip(input)
        val sharedStrings = entries["xl/sharedStrings.xml"]?.let(::parseSharedStrings).orEmpty()
        val (sheetName, relationshipId) = entries["xl/workbook.xml"]
            ?.let(::parseFirstSheet)
            ?: ("Sheet1" to null)

        val relationTarget = relationshipId?.let { id ->
            entries["xl/_rels/workbook.xml.rels"]?.let { parseRelationshipTarget(it, id) }
        }

        val sheetPath = normalizeSheetTarget(relationTarget) ?: "xl/worksheets/sheet1.xml"
        val sheetBytes = entries[sheetPath]
            ?: throw IllegalArgumentException("Nu am găsit prima foaie din fișierul .xlsx")

        return parseSheet(sheetName, sheetBytes, sharedStrings)
    }

    private fun unzip(input: InputStream): Map<String, ByteArray> {
        val result = mutableMapOf<String, ByteArray>()
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory && entry.name.startsWith("xl/")) {
                    val out = ByteArrayOutputStream()
                    zip.copyTo(out)
                    result[entry.name] = out.toByteArray()
                }
                zip.closeEntry()
            }
        }
        return result
    }

    private fun parseSharedStrings(bytes: ByteArray): List<String> {
        val parser = newParser(bytes)
        val result = mutableListOf<String>()
        var inSi = false
        var inText = false
        var buffer = StringBuilder()

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "si" -> {
                        inSi = true
                        buffer = StringBuilder()
                    }
                    "t" -> if (inSi) inText = true
                }
                XmlPullParser.TEXT -> if (inSi && inText) buffer.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.name) {
                    "t" -> inText = false
                    "si" -> {
                        result += buffer.toString()
                        inSi = false
                    }
                }
            }
            parser.next()
        }
        return result
    }

    private fun parseFirstSheet(bytes: ByteArray): Pair<String, String?> {
        val parser = newParser(bytes)
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "sheet") {
                val name = attribute(parser, "name") ?: "Sheet1"
                val id = attribute(parser, "id")
                return name to id
            }
            parser.next()
        }
        return "Sheet1" to null
    }

    private fun parseRelationshipTarget(bytes: ByteArray, relationshipId: String): String? {
        val parser = newParser(bytes)
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "Relationship") {
                if (attribute(parser, "Id") == relationshipId) {
                    return attribute(parser, "Target")
                }
            }
            parser.next()
        }
        return null
    }

    private fun parseSheet(
        sheetName: String,
        bytes: ByteArray,
        sharedStrings: List<String>,
    ): GridSheet {
        val parser = newParser(bytes)
        val rawCells = mutableListOf<RawCell>()
        val merges = mutableListOf<MergeRange>()

        var currentRef: String? = null
        var currentType: String? = null
        var currentValue: String? = null
        var inlineText = StringBuilder()
        var inValue = false
        var inInlineText = false

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "c" -> {
                        currentRef = attribute(parser, "r")
                        currentType = attribute(parser, "t")
                        currentValue = null
                        inlineText = StringBuilder()
                    }
                    "v" -> if (currentRef != null) inValue = true
                    "t" -> if (currentRef != null && currentType == "inlineStr") inInlineText = true
                    "mergeCell" -> attribute(parser, "ref")?.let { parseRange(it)?.let(merges::add) }
                }
                XmlPullParser.TEXT -> {
                    if (inValue) currentValue = (currentValue ?: "") + parser.text
                    if (inInlineText) inlineText.append(parser.text)
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "v" -> inValue = false
                    "t" -> inInlineText = false
                    "c" -> {
                        val ref = currentRef
                        if (ref != null) {
                            val (row, col) = parseCellRef(ref)
                            val text = when (currentType) {
                                "s" -> currentValue?.toIntOrNull()?.let(sharedStrings::getOrNull).orEmpty()
                                "inlineStr" -> inlineText.toString()
                                else -> currentValue.orEmpty()
                            }.trim()
                            if (text.isNotBlank()) rawCells += RawCell(row, col, text)
                        }
                        currentRef = null
                        currentType = null
                        currentValue = null
                    }
                }
            }
            parser.next()
        }

        val mergeByTopLeft = merges.associateBy { it.rowStart to it.colStart }
        val cells = rawCells.map { cell ->
            val range = mergeByTopLeft[cell.row to cell.col]
            GridCell(
                rowStart = cell.row,
                rowEnd = range?.rowEnd ?: cell.row,
                colStart = cell.col,
                colEnd = range?.colEnd ?: cell.col,
                text = cell.text,
            )
        }

        return GridSheet(
            name = sheetName,
            cells = cells,
            mergedRanges = merges.map {
                GridRange(
                    rowStart = it.rowStart,
                    rowEnd = it.rowEnd,
                    colStart = it.colStart,
                    colEnd = it.colEnd,
                )
            },
        )
    }

    private fun normalizeSheetTarget(target: String?): String? {
        if (target.isNullOrBlank()) return null
        val normalized = target.replace('\\', '/').removePrefix("/")
        return when {
            normalized.startsWith("xl/") -> normalized
            normalized.startsWith("worksheets/") -> "xl/$normalized"
            else -> "xl/$normalized"
        }
    }

    private fun parseRange(ref: String): MergeRange? {
        val pieces = ref.split(':')
        val first = pieces.firstOrNull() ?: return null
        val last = pieces.getOrNull(1) ?: first
        val (r1, c1) = parseCellRef(first)
        val (r2, c2) = parseCellRef(last)
        return MergeRange(r1, r2, c1, c2)
    }

    private fun parseCellRef(ref: String): Pair<Int, Int> {
        var col = 0
        var index = 0
        while (index < ref.length && ref[index].isLetter()) {
            col = col * 26 + (ref[index].uppercaseChar() - 'A' + 1)
            index++
        }
        val row = ref.substring(index).filter(Char::isDigit).toIntOrNull() ?: 1
        return (row - 1) to (col - 1)
    }

    private fun attribute(parser: XmlPullParser, localName: String): String? {
        for (i in 0 until parser.attributeCount) {
            if (parser.getAttributeName(i) == localName) return parser.getAttributeValue(i)
        }
        return null
    }

    private fun newParser(bytes: ByteArray): XmlPullParser {
        return Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            setInput(ByteArrayInputStream(bytes), "UTF-8")
        }
    }

    private data class RawCell(val row: Int, val col: Int, val text: String)
    private data class MergeRange(
        val rowStart: Int,
        val rowEnd: Int,
        val colStart: Int,
        val colEnd: Int,
    )
}
