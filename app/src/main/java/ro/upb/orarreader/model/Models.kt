package ro.upb.orarreader.model

data class GridCell(
    val rowStart: Int,
    val rowEnd: Int,
    val colStart: Int,
    val colEnd: Int,
    val text: String,
)

data class GridStyleCell(
    val row: Int,
    val col: Int,
    val topBorder: Boolean = false,
    val bottomBorder: Boolean = false,
    val leftBorder: Boolean = false,
    val rightBorder: Boolean = false,
)

data class GridRange(
    val rowStart: Int,
    val rowEnd: Int,
    val colStart: Int,
    val colEnd: Int,
)

data class GridSheet(
    val name: String,
    val cells: List<GridCell>,
    val styleCells: List<GridStyleCell> = emptyList(),
    val mergedRanges: List<GridRange> = emptyList(),
)

data class GroupInfo(
    val name: String,
    val startCol: Int,
    val endCol: Int,
) {
    val number: String get() = name.trim().takeWhile(Char::isDigit)
    val subgroupCount: Int get() = endCol - startCol + 1
}

enum class WeekParity {
    ODD,
    EVEN,
    BOTH,
}

enum class ActivityType(val displayName: String) {
    COURSE("curs"),
    LAB("laborator"),
    SEMINAR("seminar"),
}

data class SubjectInfo(
    val code: String,
    val canonicalCode: String,
    val fullName: String,
    val optional: Boolean = false,
)

data class SubjectCatalog(
    val subjects: Map<String, SubjectInfo>,
    val optionalCodes: Set<String>,
) {
    fun find(code: String?): SubjectInfo? {
        if (code.isNullOrBlank()) return null
        return subjects[canonicalizeSubjectCode(code)]
    }

    fun isOptional(code: String?): Boolean {
        if (code.isNullOrBlank()) return false
        return canonicalizeSubjectCode(code) in optionalCodes
    }

    val optionalSubjects: List<SubjectInfo>
        get() = optionalCodes.mapNotNull(subjects::get).sortedBy { it.code.lowercase() }
}

data class ScheduleActivity(
    /** Full human-readable discipline name when it can be resolved from the workbook legend. */
    val subject: String,
    /** Abbreviation/code as written by UPB, e.g. ISO, AMat, PCLP 1. */
    val code: String?,
    val type: ActivityType?,
    val room: String?,
    /** 0 means the activity applies to the entire selected group. */
    val subgroupIndex: Int,
    val optional: Boolean = false,
)

data class ScheduleSlot(
    val day: String,
    val startHour: Int,
    val endHour: Int,
    val parity: WeekParity,
    val activities: List<ScheduleActivity>,
) {
    val timeLabel: String get() = "%02d:00 – %02d:00".format(startHour, endHour)
}

fun canonicalizeSubjectCode(value: String): String =
    value.uppercase()
        .replace('Ș', 'S')
        .replace('Ş', 'S')
        .replace('Ț', 'T')
        .replace('Ţ', 'T')
        .filter { it.isLetterOrDigit() }
