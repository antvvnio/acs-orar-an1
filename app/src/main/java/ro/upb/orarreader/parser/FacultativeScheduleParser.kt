package ro.upb.orarreader.parser

import ro.upb.orarreader.model.ActivityType
import ro.upb.orarreader.model.GridSheet
import ro.upb.orarreader.model.ScheduleActivity
import ro.upb.orarreader.model.ScheduleSlot
import ro.upb.orarreader.model.WeekParity
import java.text.Normalizer

object FacultativeScheduleParser {
    data class Info(
        val psychologyCourses: List<ScheduleSlot>,
        val psychologySeminarManual: Boolean,
        val frenchSeminarManual: Boolean,
        val physicalEducationExternalSchedule: Boolean,
    )

    private val dayTimeRegex = Regex(
        """(?iu)\b(LUNI|MARȚI|MARTI|MIERCURI|JOI|VINERI)\s+(\d{1,2})\s*[-–]\s*(\d{1,2})"""
    )

    fun parse(sheet: GridSheet): Info {
        val cells = sheet.cells.map { clean(it.text) }.filter { it.isNotBlank() }
        val psychologyText = cells.firstOrNull {
            val n = normalize(it)
            n.contains("PSIHOLOGIA EDUCATIEI") && n.contains("CURS FACULTATIV")
        }.orEmpty()

        val psychologyCourses = parsePsychologyCourses(psychologyText)
        val psychologySeminarManual = cells.any {
            val n = normalize(it)
            n.contains("PSIHOLOGIA EDUCATIEI") &&
                n.contains("SE STABILESC LA CURS") &&
                n.contains("SEMINAR")
        }
        val frenchSeminarManual = cells.any {
            val n = normalize(it)
            n.contains("FRANCEZA") &&
                n.contains("SEMINAR") &&
                (n.contains("SE STABILESTE CU PROFESORUL") || n.contains("SE STABILESC CU PROFESORUL"))
        }
        val physicalEducationExternalSchedule = cells.any {
            val n = normalize(it)
            n.contains("ORARUL PENTRU EDUCATIA FIZICA") &&
                n.contains("SALA DE SPORT")
        }

        return Info(
            psychologyCourses = psychologyCourses,
            psychologySeminarManual = psychologySeminarManual,
            frenchSeminarManual = frenchSeminarManual,
            physicalEducationExternalSchedule = physicalEducationExternalSchedule,
        )
    }

    private fun parsePsychologyCourses(text: String): List<ScheduleSlot> {
        if (text.isBlank()) return emptyList()
        val matches = dayTimeRegex.findAll(text).toList()
        return matches.mapNotNull { match ->
            val day = prettyDay(match.groupValues[1])
            val start = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
            val end = match.groupValues[3].toIntOrNull() ?: return@mapNotNull null
            if (end <= start) return@mapNotNull null

            val nextStart = matches.firstOrNull { it.range.first > match.range.first }?.range?.first ?: text.length
            val trailing = text.substring(match.range.last + 1, nextStart)
            val room = ActivityTextParser.roomRegex.find(trailing)?.value?.let(::clean)

            ScheduleSlot(
                day = day,
                startHour = start,
                endHour = end,
                parity = WeekParity.BOTH,
                activities = listOf(
                    ScheduleActivity(
                        subject = "Psihologia educației",
                        code = null,
                        type = ActivityType.COURSE,
                        room = room,
                        subgroupIndex = 0,
                        facultative = true,
                    )
                ),
            )
        }.distinct()
    }

    private fun prettyDay(raw: String): String = when (normalize(raw)) {
        "LUNI" -> "LUNI"
        "MARTI" -> "MARȚI"
        "MIERCURI" -> "MIERCURI"
        "JOI" -> "JOI"
        "VINERI" -> "VINERI"
        else -> raw.uppercase()
    }

    private fun clean(value: String): String = value.trim().replace(Regex("""\s+"""), " ")

    private fun normalize(value: String): String =
        Normalizer.normalize(value.uppercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace('Ş', 'S')
            .replace('Ț', 'T')
            .replace('Ţ', 'T')
            .replace(Regex("""\s+"""), " ")
}
