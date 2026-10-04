package ro.upb.orarreader.parser

import ro.upb.orarreader.model.ActivityType
import ro.upb.orarreader.model.ScheduleActivity
import ro.upb.orarreader.model.SubjectCatalog

object ActivityTextParser {
    val roomRegex = Regex(
        """(?i)\b(?:ECS|AN|BN|EC|ED|EG|PR)\s*\d{2,3}[a-z]?\b|\bA\s*\d{2}\s*Leu\b|\bCanti?CTI\b"""
    )

    private val roomOnlyRegex = Regex(
        """(?i)^(?:ECS|AN|BN|EC|ED|EG|PR)\s*\d{2,3}[a-z]?$|^A\s*\d{2}\s*Leu$|^Canti?CTI$"""
    )

    private val typeMarkerRegex = Regex(
        """(?i)\((?:curs(?:\s+op(?:ț|ţ|t)ional)?|c|l|lab|laborator|s|seminar|op(?:ț|ţ|t)ional)\)"""
    )

    private val professorSuffixRegex = Regex(
        """(?i)[,\-\s]*(?:prof\.?|conf\.?|lector|lect\.?|[ȘŞșşSs]\.?\s*l\.?|dr\.?).*$"""
    )

    fun isRoomOnly(text: String): Boolean = roomOnlyRegex.matches(cleanWhitespace(text))

    private fun findSubjectByFullName(raw: String, catalog: SubjectCatalog?): ro.upb.orarreader.model.SubjectInfo? {
        if (catalog == null) return null
        val normalizedRaw = normalizeForName(raw)
        return catalog.subjects.values
            .distinctBy { it.canonicalCode }
            .filter { it.fullName.length >= 5 }
            .sortedByDescending { it.fullName.length }
            .firstOrNull { subject ->
                val full = normalizeForName(subject.fullName)
                full.isNotBlank() && normalizedRaw.contains(full)
            }
    }

    fun parse(
        subjectRaw: String,
        pairedRoom: String?,
        subgroupIndex: Int,
        catalog: SubjectCatalog? = null,
    ): ScheduleActivity {
        val raw = cleanWhitespace(subjectRaw)
        val normalized = raw.lowercase()

        val type = when {
            "conform legendei" in normalized -> null
            Regex("""(?i)\((?:curs(?:\s+op(?:ț|ţ|t)ional)?|c|op(?:ț|ţ|t)ional)\)|\bcurs(?:uri)?\b""").containsMatchIn(raw) -> ActivityType.COURSE
            Regex("""(?i)\((?:l|lab|laborator)\)|\blaborator(?:e)?\b""").containsMatchIn(raw) -> ActivityType.LAB
            Regex("""(?i)\((?:s|seminar)\)|\bseminar(?:ii)?\b""").containsMatchIn(raw) -> ActivityType.SEMINAR
            else -> null
        }

        val inlineRoomMatch = roomRegex.find(raw)
        val room = cleanWhitespace(pairedRoom ?: inlineRoomMatch?.value.orEmpty()).ifBlank { null }

        var extractedCode = SubjectCatalogParser.extractCode(raw)
        if (extractedCode == null && catalog != null) {
            val mentioned = SubjectCatalogParser.codesMentioned(raw, catalog)
            if (mentioned.size == 1) extractedCode = catalog.subjects[mentioned.single()]?.code
        }
        var catalogSubject = catalog?.find(extractedCode)
        if (catalogSubject == null) {
            catalogSubject = findSubjectByFullName(raw, catalog)
            if (extractedCode == null) extractedCode = catalogSubject?.code
        }

        var subject = raw
        if (inlineRoomMatch != null) subject = subject.removeRange(inlineRoomMatch.range)
        subject = removeCode(subject, extractedCode)
        subject = typeMarkerRegex.replace(subject, " ")
        subject = professorSuffixRegex.replace(subject, "")
        subject = subject
            .replace(Regex("""(?i)\b(?:cursuri?|laboratoare?|seminarii?)\s+op(?:ț|ţ|t)ionale?\b.*$"""), "")
            .replace(Regex("""\(\s*\)"""), " ")
            .replace(Regex("""\s*[-–]\s*[-–]+\s*"""), " - ")
            .replace(Regex("""\s+"""), " ")
            .trim(' ', '-', '–', ',', ';')

        if (catalogSubject != null && catalogSubject.fullName.isNotBlank()) {
            subject = catalogSubject.fullName
        } else if (subject.isBlank() || SubjectCatalogParser.isOptionalText(subjectRaw) && subject.length < 4) {
            subject = catalogSubject?.fullName ?: extractedCode ?: raw
        }

        val optional = SubjectCatalogParser.isOptionalText(raw) || catalog?.isOptional(extractedCode) == true

        return ScheduleActivity(
            subject = subject,
            code = catalogSubject?.code ?: extractedCode,
            type = type,
            room = room,
            subgroupIndex = subgroupIndex,
            optional = optional,
        )
    }

    fun typeFromWord(value: String?): ActivityType? {
        if (value.isNullOrBlank()) return null
        val normalized = value.lowercase()
        return when {
            normalized.startsWith("curs") -> ActivityType.COURSE
            normalized.startsWith("lab") -> ActivityType.LAB
            normalized.startsWith("semin") -> ActivityType.SEMINAR
            else -> null
        }
    }

    private fun removeCode(value: String, code: String?): String {
        if (code.isNullOrBlank()) return value
        var result = value
        result = result.replace(Regex("""^\s*\(${Regex.escape(code)}\)\s*""", RegexOption.IGNORE_CASE), "")
        result = result.replace(Regex("""\s*\(${Regex.escape(code)}\)\s*""", RegexOption.IGNORE_CASE), " ")
        result = result.replace(Regex("""^\s*${Regex.escape(code)}\s*[-–]\s*""", RegexOption.IGNORE_CASE), "")
        return result
    }

    private fun normalizeForName(value: String): String = java.text.Normalizer.normalize(value.uppercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .replace(Regex("[^A-Z0-9]+"), " ")
        .trim()

    private fun cleanWhitespace(value: String): String = value.trim().replace(Regex("""\s+"""), " ")
}
