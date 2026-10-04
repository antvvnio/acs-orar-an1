package ro.upb.orarreader.parser

import ro.upb.orarreader.model.GridSheet
import ro.upb.orarreader.model.SubjectCatalog
import ro.upb.orarreader.model.SubjectInfo
import ro.upb.orarreader.model.canonicalizeSubjectCode
import java.text.Normalizer

/**
 * Extracts discipline abbreviations and their full names from the timetable itself and its legend.
 * ACS files are not fully standardized, so this intentionally supports several forms:
 *   (ISO) INTRODUCERE ÎN SISTEME DE OPERARE (curs)
 *   ANALIZĂ MATEMATICĂ (AMat) (c)
 *   IF - ISTORIA FILOSOFIEI, ...
 */
object SubjectCatalogParser {
    private val leadingCodeRegex = Regex("""^\s*\(([A-Za-z0-9ĂÂÎȘȚăâîșț ._-]{2,14})\)\s+(.+)$""")
    private val middleCodeRegex = Regex("""^\s*(.+?)\s+\(([A-Za-z][A-Za-z0-9 ._-]{1,12})\)\s*(?:\((?:c|curs|l|lab|laborator|s|seminar)\)|[,\-]|$)""", RegexOption.IGNORE_CASE)
    private val dashCodeRegex = Regex("""^\s*([A-Za-zĂÂÎȘȚăâîșț][A-Za-zĂÂÎȘȚăâîșț0-9 ._-]{1,10})\s*[-–]\s*(.+)$""")
    private val bareCodeTypeRegex = Regex("""^\s*([A-Za-zĂÂÎȘȚăâîșț][A-Za-zĂÂÎȘȚăâîșț0-9 ._-]{1,12})\s*\((?:c|curs|l|lab|laborator|s|seminar|op(?:ț|ţ|t)ional|curs\s+op(?:ț|ţ|t)ional)\)""", RegexOption.IGNORE_CASE)

    private val explicitOptionalRegex = Regex("""(?i)\b(?:op(?:ț|ţ|t)ional(?:e|ul|a)?|disciplin(?:ă|a)\s+la\s+alegere)\b""")
    private val typeOrMetadataRegex = Regex(
        """(?i)\s*(?:\((?:c|curs|l|lab|laborator|s|seminar|curs\s+op(?:ț|ţ|t)ional|op(?:ț|ţ|t)ional)\)|,\s*(?:prof\.?|conf\.?|lect(?:or)?\.?|ș\.?\s*l\.?|s\.?\s*l\.?|dr\.?)|\s+-\s+(?=(?:AN|BN|EC|ECS|ED|EG|PR)\s*\d|A\s*\d{2}\s*Leu|Canti?CTI)).*$"""
    )


    // First-year ACS elective codes that may be listed only in the legend.
    // The timetable format sometimes labels the whole legend block as "cursuri opționale"
    // without repeating the word "opțional" on every discipline row.
    private val knownFirstYearElectives = setOf(
        "IA1", "IA2", "GAC",
        "CC", "DP", "CA", "IFR", "FILO", "LOG",
        "TC", "IF", "ANT", "IDST",
    )

    private val ignoredCodes = setOf(
        "CURS", "CURSURI", "LAB", "LABORATOR", "LABORATOARE", "SEMINAR", "SEMINARII",
        "OPTIONAL", "OPTIONALE", "CONFORM", "LEGENDEI", "LEGEND", "PAR", "IMPAR", "PROF",
        "CONF", "LECT", "LECTOR", "ZIUA", "ORA", "ORAR", "SALA", "ED", "AN", "EC", "ECS",
        "EG", "PR", "BN", "CANTI", "CTI",
    )

    // Fallbacks seen across the ACS timetable set supplied with the app. Workbook text always wins.
    private val knownSubjects = listOf(
        "AMat" to "Analiză matematică",
        "ALGAED" to "Algebră liniară, geometrie analitică și ecuații diferențiale",
        "PCLP 1" to "Programarea calculatoarelor și limbaje de programare 1",
        "PCLP" to "Programarea calculatoarelor și limbaje de programare 1",
        "Engl" to "Limba engleză",
        "Mec" to "Mecanică",
        "ISO" to "Introducere în sisteme de operare",
        "USO" to "Utilizarea sistemelor de operare",
        "PL" to "Proiectare logică",
        "IA1" to "Informatică aplicată 1 (Introducere în automatică)",
        "IA2" to "Informatică aplicată 2 (Prelucrarea informației)",
        "GAC" to "Grafică asistată de calculator",
        "DP" to "Doctrine politice",
        "Filo" to "Filosofie",
        "Log" to "Logică",
        "CC" to "Cultură și civilizație",
        "CA" to "Cultură antreprenorială",
        "IFR" to "Istoria și filosofia religiilor",
        "IDST" to "Istoria dezvoltării științei și tehnicii",
        "IF" to "Istoria filosofiei",
        "TC" to "Tehnici de comunicare",
        "Ant" to "Antropologie",
        "IFC" to "Istoria și filosofia religiilor",
        "IS" to "Identificarea sistemelor",
        "SBC" to "Sisteme bazate pe cunoștințe",
        "RCA" to "Rețele de calculatoare în automatizări",
        "SCR" to "Sisteme de conducere a roboților",
    )

    fun parse(sheet: GridSheet): SubjectCatalog {
        val candidates = linkedMapOf<String, Candidate>()

        for (cell in sheet.cells) {
            val text = cleanWhitespace(cell.text)
            if (text.isBlank()) continue
            extractSubject(text)?.let { (displayCode, fullName) ->
                val canonical = canonicalizeSubjectCode(displayCode)
                if (isPlausibleCode(displayCode, canonical) && fullName.length >= 4) {
                    val candidate = Candidate(
                        displayCode = normalizeDisplayCode(displayCode),
                        fullName = prettifyName(fullName),
                        optional = explicitOptionalRegex.containsMatchIn(text),
                    )
                    val old = candidates[canonical]
                    if (old == null || score(candidate) > score(old)) candidates[canonical] = candidate
                }
            }
        }

        for ((displayCode, fullName) in knownSubjects) {
            val canonical = canonicalizeSubjectCode(displayCode)
            candidates.putIfAbsent(canonical, Candidate(displayCode, fullName, false))
        }

        val optionalCodes = linkedSetOf<String>()
        for ((canonical, candidate) in candidates) {
            if (candidate.optional) optionalCodes += canonical
        }

        // Some ACS first-year files put the "(cursuri opționale)" label in a narrow
        // side column while the discipline rows themselves do not contain the word optional.
        // When that marker exists, elective discipline entries in the legend must still be selectable.
        val hasOptionalLegendMarker = sheet.cells.any { cell ->
            val n = normalize(cleanWhitespace(cell.text))
            n.contains("CURSURI") && n.contains("OPTIONALE")
        } || run {
            val markerRows = sheet.cells
                .filter { cell ->
                    val n = normalize(cleanWhitespace(cell.text))
                    n.contains("CURSURI") || n.contains("OPTIONALE")
                }
                .map { it.rowStart }
                .toSet()
            markerRows.any { row -> markerRows.contains(row + 1) || markerRows.contains(row - 1) }
        }

        if (hasOptionalLegendMarker) {
            for (cell in sheet.cells) {
                val code = extractCode(cell.text) ?: continue
                val canonical = canonicalizeSubjectCode(code)
                if (canonical in knownFirstYearElectives) optionalCodes += canonical
            }
        }

        // Optional declarations often contain only codes: "Cursuri opționale TC, IF, Ant".
        for (cell in sheet.cells) {
            val text = cleanWhitespace(cell.text)
            if (!explicitOptionalRegex.containsMatchIn(text)) continue
            for ((canonical, candidate) in candidates) {
                if (containsCode(text, candidate.displayCode)) optionalCodes += canonical
            }
            if (!isOptionalDeclaration(text)) continue
            extractLooseOptionalCodes(text).forEach { code ->
                val canonical = canonicalizeSubjectCode(code)
                if (canonical.isNotBlank()) {
                    optionalCodes += canonical
                    if (canonical !in candidates && isPlausibleCode(code, canonical)) {
                        candidates[canonical] = Candidate(normalizeDisplayCode(code), normalizeDisplayCode(code), true)
                    }
                }
            }
        }

        val subjects = candidates.mapValues { (canonical, candidate) ->
            SubjectInfo(
                code = candidate.displayCode,
                canonicalCode = canonical,
                fullName = candidate.fullName,
                optional = canonical in optionalCodes,
            )
        }
        return SubjectCatalog(subjects, optionalCodes)
    }

    fun extractCode(text: String): String? {
        val cleaned = cleanWhitespace(text)
        val leading = leadingCodeRegex.find(cleaned)?.groupValues?.getOrNull(1)
        if (!leading.isNullOrBlank() && isPlausibleCode(leading, canonicalizeSubjectCode(leading))) {
            return normalizeDisplayCode(leading)
        }

        val bare = bareCodeTypeRegex.find(cleaned)?.groupValues?.getOrNull(1)
        if (!bare.isNullOrBlank() && isPlausibleCode(bare, canonicalizeSubjectCode(bare))) {
            return normalizeDisplayCode(bare)
        }

        val middle = middleCodeRegex.find(cleaned)?.groupValues?.getOrNull(2)
        if (!middle.isNullOrBlank() && isPlausibleCode(middle, canonicalizeSubjectCode(middle))) {
            return normalizeDisplayCode(middle)
        }

        val dash = dashCodeRegex.find(cleaned)?.groupValues?.getOrNull(1)
        if (!dash.isNullOrBlank() && isPlausibleCode(dash, canonicalizeSubjectCode(dash))) {
            return normalizeDisplayCode(dash)
        }
        return null
    }

    fun isOptionalText(text: String): Boolean = explicitOptionalRegex.containsMatchIn(cleanWhitespace(text))

    fun codesMentioned(text: String, catalog: SubjectCatalog): Set<String> {
        val result = linkedSetOf<String>()
        for ((canonical, subject) in catalog.subjects) {
            if (containsCode(text, subject.code)) result += canonical
        }
        if (isOptionalDeclaration(text)) {
            extractLooseOptionalCodes(text).mapTo(result, ::canonicalizeSubjectCode)
        }
        return result.filterTo(linkedSetOf()) { it.isNotBlank() }
    }

    private fun extractSubject(text: String): Pair<String, String>? {
        leadingCodeRegex.find(text)?.let { match ->
            val code = match.groupValues[1]
            val name = stripMetadata(match.groupValues[2])
            if (name.isNotBlank()) return code to name
        }
        middleCodeRegex.find(text)?.let { match ->
            val name = stripMetadata(match.groupValues[1])
            val code = match.groupValues[2]
            if (name.isNotBlank()) return code to name
        }
        dashCodeRegex.find(text)?.let { match ->
            val code = match.groupValues[1]
            val name = stripMetadata(match.groupValues[2].substringBefore(','))
            val normalizedName = normalize(name)
            val looksLikeScheduleFragment = normalizedName.startsWith("CURS ") ||
                normalizedName.startsWith("LABORATOR ") || normalizedName.startsWith("SEMINAR ") ||
                Regex("^(LUNI|MARTI|MIERCURI|JOI|VINERI|SAMBATA)\\b").containsMatchIn(normalizedName)
            if (name.isNotBlank() && !looksLikeScheduleFragment) return code to name
        }
        return null
    }

    private fun stripMetadata(value: String): String {
        var result = value
        result = typeOrMetadataRegex.replace(result, "")
        result = result.replace(Regex("""(?i)\s*\((?:c|curs|l|lab|laborator|s|seminar|op(?:ț|ţ|t)ional|curs\s+op(?:ț|ţ|t)ional)\)\s*.*$"""), "")
        result = result.substringBefore(" - ")
        return cleanWhitespace(result).trim(' ', '-', '–', ',', ';')
    }

    private fun isOptionalDeclaration(text: String): Boolean {
        // A full discipline entry such as "(GAC) ... (curs optional)" is not a declaration list.
        if (extractCode(text) != null && (text.trim().startsWith("(") || text.contains(" - "))) return false
        val normalized = normalize(text)
        return normalized.contains("CURS OPTIONAL") ||
            normalized.contains("CURSURI OPTIONALE") ||
            normalized.contains("LABORATOARELE OPTIONALE") ||
            normalized.contains("LABORATOARE OPTIONALE") ||
            normalized.contains("DISCIPLINA LA ALEGERE") ||
            normalized.contains("DISCIPLINE LA ALEGERE")
    }

    private fun extractLooseOptionalCodes(text: String): Set<String> {
        val normalized = normalize(text)
        val markerIndex = listOf("OPTIONAL", "OPTIONALE", "OPTIONALUL", "ALEGERE")
            .map { normalized.indexOf(it) }
            .filter { it >= 0 }
            .minOrNull() ?: return emptySet()

        val originalTail = cleanWhitespace(text).drop(markerIndex.coerceAtMost(cleanWhitespace(text).length))
        val tokens = Regex("""(?iu)\b[A-Za-zĂÂÎȘȚăâîșț][A-Za-zĂÂÎȘȚăâîșț0-9]{1,6}\b""")
            .findAll(originalTail)
            .map { it.value }
            .filter { token ->
                val canonical = canonicalizeSubjectCode(token)
                canonical !in ignoredCodes && canonical.length in 2..7 &&
                    (token.any(Char::isUpperCase) || token.any(Char::isDigit))
            }
            .toList()

        return tokens.toSet()
    }

    private fun containsCode(text: String, code: String): Boolean {
        val normalizedText = normalize(text)
        val chunks = Regex("[A-Z0-9]+").findAll(normalize(code)).map { it.value }.toList()
        if (chunks.isEmpty()) return false
        val body = chunks.joinToString("\\s*") { Regex.escape(it) }
        return Regex("(?<![A-Z0-9])$body(?![A-Z0-9])").containsMatchIn(normalizedText)
    }

    private fun canonicalizeForCodeSearch(value: String): String {
        val normalized = normalize(value)
        return normalized.replace(Regex("[^A-Z0-9]+"), " ").trim()
    }

    private fun isPlausibleCode(display: String, canonical: String): Boolean {
        if (canonical.length !in 2..12) return false
        if (canonical in ignoredCodes) return false

        // Room identifiers often appear in parentheses after the discipline and must not
        // become fake subject abbreviations (e.g. "EC 105").
        if (Regex("^(?:AN|BN|EC|ECS|ED|EG|PR)\\d{2,3}[A-Z]?$", RegexOption.IGNORE_CASE).matches(canonical)) return false
        if (Regex("^A\\d{2}LEU$", RegexOption.IGNORE_CASE).matches(canonical)) return false
        if (canonical == "CANTCTI" || canonical == "CANTICTI") return false

        val normalizedDisplay = normalize(display)
        if (Regex("^(?:LUNI|MARTI|MIERCURI|JOI|VINERI|SAMBATA)\\b").containsMatchIn(normalizedDisplay)) return false
        if (display.trim().contains(' ') && display.trim().split(Regex("\\s+")).size > 3) return false
        return canonical.any(Char::isLetter)
    }

    private fun score(candidate: Candidate): Int {
        var value = candidate.fullName.length.coerceAtMost(80)
        if (candidate.fullName != candidate.displayCode) value += 50
        if (candidate.optional) value += 3
        return value
    }

    private fun normalizeDisplayCode(code: String): String = cleanWhitespace(code).trim(' ', '-', '–', ',', ';')

    private fun prettifyName(name: String): String {
        val cleaned = cleanWhitespace(name).trim(' ', '-', '–', ',', ';')
        val letters = cleaned.filter(Char::isLetter)
        if (letters.isEmpty()) return cleaned
        val uppercaseRatio = letters.count(Char::isUpperCase).toDouble() / letters.length
        if (uppercaseRatio < 0.85) return cleaned

        return cleaned.lowercase().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

    private fun cleanWhitespace(value: String): String = value.trim().replace(Regex("\\s+"), " ")

    private fun normalize(value: String): String {
        return Normalizer.normalize(value.trim().uppercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace('Ş', 'S')
            .replace('Ț', 'T')
            .replace('Ţ', 'T')
            .replace(Regex("\\s+"), " ")
    }

    private data class Candidate(
        val displayCode: String,
        val fullName: String,
        val optional: Boolean,
    )
}
