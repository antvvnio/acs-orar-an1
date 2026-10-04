package ro.upb.orarreader.parser

import ro.upb.orarreader.model.ActivityType
import ro.upb.orarreader.model.GridSheet
import ro.upb.orarreader.model.ScheduleActivity
import ro.upb.orarreader.model.ScheduleSlot
import ro.upb.orarreader.model.SubjectCatalog
import ro.upb.orarreader.model.WeekParity
import ro.upb.orarreader.model.canonicalizeSubjectCode
import java.text.Normalizer

/** Reads fixed elective hours written in the legend when the group grid contains only an elective placeholder. */
object OptionalScheduleParser {
    private val dayPattern = "LUNI|MARȚI|MARTI|MIERCURI|JOI|VINERI|SÂMBĂTĂ|SAMBATA"
    private val sessionRegex = Regex(
        """(?iu)(?:(curs(?:\s+op(?:ț|ţ|t)ional)?|laborator(?:e)?|seminar(?:ii)?)\s+)?(?:(LUNI|MARȚI|MARTI|MIERCURI|JOI|VINERI|SÂMBĂTĂ|SAMBATA)\s+)?(\d{1,2})\s*[-–]\s*(\d{1,2})(?:\s+((?:impar|par)(?:\s*(?:și|si|/|\+)\s*(?:impar|par))?))?"""
    )

    fun manualActivityTypes(
        sheet: GridSheet,
        catalog: SubjectCatalog,
    ): Map<String, ActivityType> {
        val result = linkedMapOf<String, ActivityType>()

        for (cell in sheet.cells) {
            val text = cleanWhitespace(cell.text)
            if (text.isBlank()) continue
            val normalized = normalize(text)
            val scheduleIsTbd =
                normalized.contains("SE STABILESC LA CURS") ||
                normalized.contains("SE STABILESTE LA CURS") ||
                normalized.contains("SE STABILESC CU PROFESORUL") ||
                normalized.contains("SE STABILESTE CU PROFESORUL")
            if (!scheduleIsTbd) continue

            val type = when {
                Regex("""\bLABORATOR(?:UL|ELE|II|IILE|I)?\b""").containsMatchIn(normalized) -> ActivityType.LAB
                Regex("""\bSEMINAR(?:UL|ELE|II|IILE|I)?\b""").containsMatchIn(normalized) -> ActivityType.SEMINAR
                else -> null
            } ?: continue

            val mentioned = SubjectCatalogParser.codesMentioned(text, catalog).toMutableSet()
            SubjectCatalogParser.extractCode(text)?.let { mentioned += canonicalizeSubjectCode(it) }
            for (code in mentioned) {
                val canonical = canonicalizeSubjectCode(code)
                if (canonical in catalog.subjects) result[canonical] = type
            }
        }
        return result
    }

    fun parse(
        sheet: GridSheet,
        selectedCanonicalCodes: Set<String>,
        catalog: SubjectCatalog,
    ): List<ScheduleSlot> {
        if (selectedCanonicalCodes.isEmpty()) return emptyList()
        val events = mutableListOf<LegendEvent>()
        val orderedCells = sheet.cells
            .map { it to cleanWhitespace(it.text) }
            .filter { (_, text) -> text.isNotBlank() }
            .sortedWith(compareBy<Pair<ro.upb.orarreader.model.GridCell, String>> { it.first.rowStart }.thenBy { it.first.colStart })

        for (canonical in selectedCanonicalCodes) {
            val subject = catalog.subjects[canonical] ?: continue
            val candidates = orderedCells.withIndex()
                .filter { (_, item) -> containsCodeAtEntryStart(item.second, subject.code) }
                .mapNotNull { (startIndex, _) ->
                    val text = buildLegendEntryText(orderedCells, startIndex, catalog)
                    if (Regex("(?iu)\\b(?:$dayPattern)\\b").containsMatchIn(text) &&
                        Regex("\\b\\d{1,2}\\s*[-–]\\s*\\d{1,2}\\b").containsMatchIn(text)
                    ) text else null
                }

            for (text in candidates) {
                var currentDay: String? = null
                var currentType: ActivityType? = null
                val matches = sessionRegex.findAll(text).toList()
                val localEvents = mutableListOf<LegendEvent>()
                for ((index, match) in matches.withIndex()) {
                    ActivityTextParser.typeFromWord(match.groupValues[1]).let { if (it != null) currentType = it }
                    if (match.groupValues[2].isNotBlank()) currentDay = prettyDay(match.groupValues[2])
                    val day = currentDay ?: continue
                    val start = match.groupValues[3].toIntOrNull() ?: continue
                    val end = match.groupValues[4].toIntOrNull() ?: continue
                    if (start !in 6..23 || end !in 7..24 || end <= start) continue

                    val parity = parseParity(match.groupValues[5])
                    val nextStart = matches.getOrNull(index + 1)?.range?.first ?: text.length
                    val trailing = text.substring(match.range.last + 1, nextStart)
                    val room = ActivityTextParser.roomRegex.find(trailing)?.value?.let(::cleanWhitespace)

                    localEvents += LegendEvent(
                        day = day,
                        startHour = start,
                        endHour = end,
                        parity = parity,
                        activity = ScheduleActivity(
                            subject = subject.fullName,
                            code = subject.code,
                            type = currentType,
                            room = room,
                            subgroupIndex = 0,
                            optional = true,
                        ),
                    )
                }

                // In legend prose one room is sometimes written once for two consecutive seminar hours
                // (e.g. "14-16 și 18-20 în A04 Leu"). Reuse it only when the group has a single
                // unambiguous room for the same day + activity type.
                val filled = localEvents.map { event ->
                    if (!event.activity.room.isNullOrBlank()) return@map event
                    val peerRooms = localEvents
                        .filter { it.day == event.day && it.activity.type == event.activity.type }
                        .mapNotNull { it.activity.room }
                        .distinct()
                    if (peerRooms.size == 1) {
                        event.copy(activity = event.activity.copy(room = peerRooms.single()))
                    } else event
                }
                // If the legend lists several seminar/lab slots for the same elective, those are
                // alternative allocations and we cannot know which one belongs to the student from
                // the 3-digit group alone. Keep courses, and keep a non-course session only when it
                // is unambiguous (exactly one such slot in the legend entry).
                val safeEvents = filled.groupBy { it.activity.type }.flatMap { (type, typed) ->
                    when (type) {
                        ActivityType.COURSE, null -> typed
                        ActivityType.LAB, ActivityType.SEMINAR -> if (typed.size == 1) typed else emptyList()
                    }
                }
                events += safeEvents
            }
        }

        return events
            .distinct()
            .groupBy { SlotKey(it.day, it.startHour, it.endHour, it.parity) }
            .map { (key, values) ->
                ScheduleSlot(
                    day = key.day,
                    startHour = key.startHour,
                    endHour = key.endHour,
                    parity = key.parity,
                    activities = values.map { it.activity }.distinct(),
                )
            }
    }

    /**
     * Legend entries in CA/CB/CC/CD can wrap into another physical Excel cell. TC is the
     * clearest example: the first cell ends after one seminar slot, while the remaining
     * seminar alternatives continue on the next row. Join only nearby continuation cells and
     * stop as soon as the next catalog entry starts.
     */
    private fun buildLegendEntryText(
        orderedCells: List<Pair<ro.upb.orarreader.model.GridCell, String>>,
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
        return canonicalizeSubjectCode(SubjectCatalogParser.extractCode(text).orEmpty()) == canonicalizeSubjectCode(code)
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

    private fun normalize(value: String): String = Normalizer.normalize(value.uppercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .replace('Ş', 'S')
        .replace('Ț', 'T')
        .replace('Ţ', 'T')

    private data class LegendEvent(
        val day: String,
        val startHour: Int,
        val endHour: Int,
        val parity: WeekParity,
        val activity: ScheduleActivity,
    )

    private data class SlotKey(
        val day: String,
        val startHour: Int,
        val endHour: Int,
        val parity: WeekParity,
    )
}
