package ru.polytech.jira

import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnalyzerTest {
    private val analyzer = Analyzer(ZoneId.of("UTC"), 1)
    private fun at(day: Int, hour: Int = 0) = Instant.parse("2024-01-%02dT%02d:00:00Z".format(day, hour))
    private fun issue(
        key: String = "TEST-1", created: Instant = at(1), resolved: Instant? = at(2),
        changes: List<StatusChange> = emptyList(), spent: Long = 0,
        assignee: JiraUser? = null, reporter: JiraUser? = null, priority: String = "Medium"
    ) = Issue(key, created, resolved, "Done", changes, assignee, reporter, priority, spent)

    @Test fun `closed issue duration is binned in hours`() {
        val report = analyzer.analyze(listOf(issue(resolved = at(1, 5))))
        assertEquals(1, report.openDuration.sumOf(HistogramBin::count))
        assertTrue(report.openDuration.any { 5 in it.fromHours until it.toHours && it.count == 1 })
    }

    @Test fun `open issues do not enter closed issue histograms`() {
        val report = analyzer.analyze(listOf(issue(resolved = null)))
        assertEquals(0, report.closedCount)
        assertTrue(report.openDuration.isEmpty())
        assertTrue(report.worklogDuration.isEmpty())
    }

    @Test fun `status visits are summed and clipped at resolution`() {
        val changes = listOf(
            StatusChange(at(1, 2), "Open", "Doing"),
            StatusChange(at(1, 5), "Doing", "Open"),
            StatusChange(at(1, 7), "Open", "Done"),
            StatusChange(at(2), "Done", "Reopened")
        )
        val times = analyzer.statusDurations(issue(resolved = at(1, 8), changes = changes))
        assertEquals(4 * 3600L, times["Open"])
        assertEquals(3 * 3600L, times["Doing"])
        assertEquals(3600L, times["Done"])
        assertTrue("Reopened" !in times)
    }

    @Test fun `daily counts include empty days and cumulative totals`() {
        val report = analyzer.analyze(listOf(issue(resolved = at(3))))
        assertEquals(listOf(1, 0, 0), report.daily.map(DailyCount::created))
        assertEquals(listOf(0, 0, 1), report.daily.map(DailyCount::closed))
        assertEquals(listOf(1, 1, 1), report.daily.map(DailyCount::createdTotal))
        assertEquals(listOf(0, 0, 1), report.daily.map(DailyCount::closedTotal))
    }

    @Test fun `user roles count separately and sort by total`() {
        val alice = JiraUser("a", "Alice")
        val bob = JiraUser("b", "Bob")
        val report = analyzer.analyze(listOf(
            issue(assignee = alice, reporter = alice),
            issue("TEST-2", assignee = alice, reporter = bob)
        ))
        assertEquals(UserCount("Alice", 2, 1), report.users.first())
        assertEquals(UserCount("Bob", 0, 1), report.users.last())
    }

    @Test fun `worklog and priorities include expected task counts`() {
        val report = analyzer.analyze(listOf(
            issue(spent = 7200, priority = "High"),
            issue("TEST-2", spent = 0, priority = "Low")
        ))
        assertEquals(1, report.worklogDuration.sumOf(HistogramBin::count))
        assertEquals(1, report.unloggedClosedCount)
        assertEquals(mapOf("High" to 1, "Low" to 1), report.priorities)
    }

    @Test fun `long duration is kept in a separate overflow interval`() {
        val durations = List(20) { 3600L } + 3600L * 1000
        val bins = analyzer.histogram(durations)
        assertEquals(20, bins.dropLast(1).sumOf(HistogramBin::count))
        assertEquals(1, bins.last().count)
        assertTrue(bins.last().overflow)
    }

    @Test fun `top users are limited to thirty`() {
        val issues = (1..31).map { issue("T-$it", assignee = JiraUser("$it", "User $it")) }
        assertEquals(30, analyzer.analyze(issues).users.size)
    }
}
