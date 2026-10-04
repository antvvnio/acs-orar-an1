package ro.upb.orarreader.parser

import ro.upb.orarreader.model.GridSheet
import java.io.InputStream

object ExcelReader {
    fun read(fileName: String, input: InputStream): GridSheet {
        val lower = fileName.lowercase()
        return when {
            lower.endsWith(".xls") -> XlsReader.read(input)
            lower.endsWith(".xlsx") -> XlsxReader.read(input)
            else -> throw IllegalArgumentException("Fișierul trebuie să fie .xls sau .xlsx")
        }
    }
}
