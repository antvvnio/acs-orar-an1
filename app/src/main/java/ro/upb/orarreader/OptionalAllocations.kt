package ro.upb.orarreader

import android.content.SharedPreferences
import ro.upb.orarreader.model.ActivityType
import ro.upb.orarreader.model.GridCell
import ro.upb.orarreader.model.GridSheet
import ro.upb.orarreader.model.ScheduleActivity
import ro.upb.orarreader.model.ScheduleSlot
import ro.upb.orarreader.model.SubjectCatalog
import ro.upb.orarreader.model.WeekParity
import ro.upb.orarreader.model.canonicalizeSubjectCode
import ro.upb.orarreader.parser.ActivityTextParser
import ro.upb.orarreader.parser.SubjectCatalogParser
import java.text.Normalizer

/**
 * Reads alternative seminar/lab allocations from the optional-subject legend.
 *
 * This deliberately lives outside ScheduleParser/OptionalScheduleParser. The official timetable
 * parser stays untouched; a student's chosen allocation is merged on top afterwards.
 */
object OptionalAllocations {
    data class Candidate(
        val type: ActivityType,
        val day: String,
        val startHour: Int,
        val endHour: Int,
        val parity: WeekParity,
        val room: String?,
    ) {
        val id: String
            get() = listOf(
                type.name,
                day,
                startHour.toString(),
                endHour.toString(),
                parity.name,
                room.orEmpty(),
            ).joinToString("|")

        val displayLabel: String
            get() {
                val parityLabel = when (parity) {
                    WeekParity.ODD -> "impar"
                    WeekParity.EVEN -> "par"
                    WeekParity.BOTH -> "în fiecare săptămână"
                }
                return buildString {
                    append(day.lowercase().replaceFirstChar { it.titlecase() })
                    append(" · %02d:00–%02d:00".format(startHour, endHour))
                    append(" · ")
                    append(parityLabel)
                    if (!room.isNullOrBlank()) append(" · $room")
                }
            }
    }

    private val dayPattern = "LUNI|MARȚI|MARTI|MIERCURI|JOI|VINERI|SÂMBĂTĂ|SAMBATA"
    private val sessionRegex = Regex(
        """(?iu)(?:(curs(?:\s+op(?:ț|ţ|t)ional)?|laborator(?:e)?|seminar(?:ii)?)\s+)?(?:(LUNI|MARȚI|MARTI|MIERCURI|JOI|VINERI|SÂMBĂTĂ|SAMBATA)\s+)?(\d{1,2})\s*[-–]\s*(\d{1,2})(?:\s+((?:impar|par)(?:\s*(?:și|si|/|\+)\s*(?:impar|par))?))?"""
    )

    fun candidates(
        sheet: GridSheet,
        catalog: SubjectCatalog,
    ): Map<String, Map<ActivityType, List<Candidate>>> {
        val orderedCells = sheet.cells
            .map { it to cleanWhitespace(it.text) }
            .filter { (_, text) -> text.isNotBlank() }
            .sortedWith(compareBy<Pair<GridCell, String>> { it.first.rowStart }.thenBy { it.first.colStart })

        val result = linkedMapOf<String, MutableMap<ActivityType, MutableList<Candidate>>>()

        for (subject in catalog.optionalSubjects) {
            val canonical = subject.canonicalCode
            val entries = orderedCells.withIndex()
                .filter { (_, item) -> containsCodeAtEntryStart(item.second, subject.code) }
                .map { (startIndex, _) -> buildLegendEntryText(orderedCells, startIndex, catalog) }
                .filter {
                    Regex("(?iu)\\b(?:$dayPattern)\\b").containsMatchIn(it) &&
                        Regex("\\b\\d{1,2}\\s*[-–]\\s*\\d{1,2}\\b").containsMatchIn(it)
                }

            for (text in entries) {
                var currentDay: String? = null
                var currentType: ActivityType? = null
                val matches = sessionRegex.findAll(text).toList()
                val parsed = mutableListOf<Candidate>()

                for ((index, match) in matches.withIndex()) {
                    ActivityTextParser.typeFromWord(match.groupValues[1])?.let { currentType = it }
                    if (match.groupValues[2].isNotBlank()) currentDay = prettyDay(match.groupValues[2])

                    val type = currentType ?: continue
                    if (type == ActivityType.COURSE) continue
                    val day = currentDay ?: continue
                    val start = match.groupValues[3].toIntOrNull() ?: continue
                    val end = match.groupValues[4].toIntOrNull() ?: continue
                    if (start !in 6..23 || end !in 7..24 || end <= start) continue

                    val nextStart = matches.getOrNull(index + 1)?.range?.first ?: text.length
                    val trailing = text.substring(match.range.last + 1, nextStart)
                    val room = ActivityTextParser.roomRegex.find(trailing)?.value?.let(::cleanWhitespace)

                    parsed += Candidate(
                        type = type,
                        day = day,
                        startHour = start,
                        endHour = end,
                        parity = parseParity(match.groupValues[5]),
                        room = room,
                    )
                }

                val filled = parsed.map { candidate ->
                    if (!candidate.room.isNullOrBlank()) return@map candidate
                    val peerRooms = parsed
                        .filter { it.day == candidate.day && it.type == candidate.type }
                        .mapNotNull { it.room }
                        .distinct()
                    if (peerRooms.size == 1) candidate.copy(room = peerRooms.single()) else candidate
                }

                for (candidate in filled) {
                    result
                        .getOrPut(canonical) { linkedMapOf() }
                        .getOrPut(candidate.type) { mutableListOf() }
                        .add(candidate)
                }
            }
        }

        return result.mapValues { (_, byType) ->
            byType.mapValues { (_, items) -> items.distinctBy { it.id } }
        }
    }

    fun readSelection(
        preferences: SharedPreferences,
        series: String,
        code: String,
        type: ActivityType,
    ): String? = preferences.getString(selectionKey(series, code, type), null)

    fun writeSelection(
        preferences: SharedPreferences,
        series: String,
        code: String,
        type: ActivityType,
        candidateId: String?,
    ) {
        val editor = preferences.edit()
        val key = selectionKey(series, code, type)
        if (candidateId.isNullOrBlank()) editor.remove(key) else editor.putString(key, candidateId)
        editor.apply()
    }

    fun selectedSlots(
        preferences: SharedPreferences,
        series: String,
        selectedCodes: Set<String>,
        sheet: GridSheet,
        catalog: SubjectCatalog,
    ): List<ScheduleSlot> {
        val all = candidates(sheet, catalog)
        return selectedCodes
            .map(::canonicalizeSubjectCode)
            .distinct()
            .flatMap { canonical ->
                val subject = catalog.subjects[canonical] ?: return@flatMap emptyList()
                all[canonical].orEmpty().flatMap { (type, options) ->
                    if (options.size <= 1) return@flatMap emptyList()
                    val selectedId = readSelection(preferences, series, canonical, type)
                        ?: return@flatMap emptyList()
                    val candidate = options.firstOrNull { it.id == selectedId }
                        ?: return@flatMap emptyList()

                    listOf(
                        ScheduleSlot(
                            day = candidate.day,
                            startHour = candidate.startHour,
                            endHour = candidate.endHour,
                            parity = candidate.parity,
                            activities = listOf(
                                ScheduleActivity(
                                    subject = subject.fullName,
                                    code = subject.code,
                                    type = candidate.type,
                                    room = candidate.room,
                                    subgroupIndex = 0,
                                    optional = true,
                                )
                            ),
                        )
                    )
                }
            }
    }

    private fun selectionKey(series: String, code: String, type: ActivityType): String =
        "optional_allocation_${series.uppercase()}_${canonicalizeSubjectCode(code)}_${type.name}"

    private fun buildLegendEntryText(
        orderedCells: List<Pair<GridCell, String>>,
        startIndex: Int,
        catalog: SubjectCatalog,
    ): String {
        val (startCell, startText) = orderedCells[startIndex]
        val parts = mutableListOf(startText)
        var index = startIndex + 1

        while (index < orderedCells.size) {
            val (cell, text) = orderedCells[index]
            if (cell.rowStart > startCell.rowStart + 2) break
            if (isCatalogEntryStart(text, catalog)) break
            if (cell.rowStart >= startCell.rowStart) parts += text
            index++
        }
        return cleanWhitespace(parts.joinToString(" "))
    }

    private fun isCatalogEntryStart(text: String, catalog: SubjectCatalog): Boolean =
        catalog.subjects.values.any { containsCodeAtEntryStart(text, it.code) }

    private fun containsCodeAtEntryStart(text: String, code: String): Boolean {
        val escaped = Regex.escape(code.trim())
        if (Regex("""(?i)^\s*\($escaped\)""").containsMatchIn(text)) return true
        if (Regex("""(?i)^\s*$escaped\s*[-–]""").containsMatchIn(text)) return true
        return canonicalizeSubjectCode(SubjectCatalogParser.extractCode(text).orEmpty()) ==
            canonicalizeSubjectCode(code)
    }

    private fun parseParity(raw: String): WeekParity {
        val normalized = normalize(raw)
        val hasOdd = Regex("\\bIMPAR\\b").containsMatchIn(normalized)
        val hasEven = Regex("\\bPAR\\b").containsMatchIn(normalized.replace("IMPAR", ""))
        return when {
            hasOdd && hasEven -> WeekParity.BOTH
            hasOdd -> WeekParity.ODD
            hasEven -> WeekParity.EVEN
            else -> WeekParity.BOTH
        }
    }

    private fun prettyDay(raw: String): String = when (normalize(raw)) {
        "LUNI" -> "LUNI"
        "MARTI" -> "MARȚI"
        "MIERCURI" -> "MIERCURI"
        "JOI" -> "JOI"
        "VINERI" -> "VINERI"
        "SAMBATA" -> "SÂMBĂTĂ"
        else -> raw.uppercase()
    }

    private fun cleanWhitespace(value: String): String = value.trim().replace(Regex("\\s+"), " ")

    private fun normalize(value: String): String = Normalizer.normalize(
        value.uppercase(),
        Normalizer.Form.NFD,
    )
        .replace(Regex("\\p{Mn}+"), "")
        .replace('Ş', 'S')
        .replace('Ț', 'T')
        .replace('Ţ', 'T')
}
