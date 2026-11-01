package ru.polytech.jira

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReportWriterTest {
    @TempDir lateinit var directory: Path

    @Test fun `report contains six graph categories and escapes Jira text`() {
        val issue = Issue("T-1", Instant.parse("2024-01-01T00:00:00Z"), Instant.parse("2024-01-02T00:00:00Z"),
            "Done", listOf(StatusChange(Instant.parse("2024-01-01T12:00:00Z"), "Open", "Done")),
            JiraUser("a", "<Alice>"), null, "High", 3600)
        val report = Analyzer(ZoneId.of("UTC"), 24).analyze(listOf(issue))
        val file = ReportWriter().write(report, "T", directory, "Выборка: 1 задача")
        val html = Files.readString(file)
        assertEquals(6, Regex("<h2>").findAll(html).count())
        assertTrue(html.contains("&lt;Alice&gt;"))
        assertTrue(html.contains("<svg"))
        assertTrue(html.contains("Создано всего"))
        assertTrue(html.contains("Создано и закрыто за день"))
        assertTrue(html.contains("Накопительный итог"))
        assertTrue(html.contains("Выборка: 1 задача"))
    }

    @Test fun `report explains missing worklog data`() {
        val issue = Issue("T-2", Instant.parse("2024-01-01T00:00:00Z"), Instant.parse("2024-01-02T00:00:00Z"),
            "Done", emptyList(), null, null, "Medium", 0)
        val report = Analyzer(ZoneId.of("UTC"), 24).analyze(listOf(issue))
        val html = Files.readString(ReportWriter().write(report, "T", directory))
        assertTrue(html.contains("без него: 1"))
        assertTrue(html.contains("Распределение по времени построить не из чего"))
    }

    @Test fun `standard priorities follow severity order`() {
        val report = Analyzer(ZoneId.of("UTC"), 24).analyze(emptyList())
            .copy(priorities = mapOf("Minor" to 8, "Blocker" to 1, "Major" to 3))
        val html = Files.readString(ReportWriter().write(report, "T", directory))
        val priorities = html.substringAfter("6. Степень серьёзности")
        assertTrue(priorities.indexOf(">Blocker<") < priorities.indexOf(">Major<"))
        assertTrue(priorities.indexOf(">Major<") < priorities.indexOf(">Minor<"))
    }

    @Test fun `histogram labels every interval boundary in the displayed unit`() {
        val report = Analyzer(ZoneId.of("UTC"), 24).analyze(emptyList()).copy(
            openDuration = listOf(
                HistogramBin(0, 72, 2), HistogramBin(72, 144, 3),
                HistogramBin(144, 216, 1), HistogramBin(216, 216, 1, overflow = true)
            )
        )
        val html = Files.readString(ReportWriter().write(report, "T", directory))
        val chart = html.substringAfter("1. Время от создания до закрытия").substringBefore("</svg>")
        assertTrue(chart.contains("Ширина обычного интервала: 3 дн."))
        for (boundary in listOf("0", "3", "6", "≥9")) {
            assertTrue(chart.contains(">$boundary</text>"), "Missing boundary $boundary")
        }
        assertEquals(4, Regex("rotate\\(-55").findAll(chart).count())
    }
}
