package ru.polytech.jira

import java.time.Duration
import java.time.ZoneId
import kotlin.math.ceil

/** Computes all six report datasets independently of Jira and rendering. */
class Analyzer(private val zone: ZoneId, private val minimumBinHours: Long) {
    /** Builds histogram, timeline, user, and priority series from project issues. */
    fun analyze(issues: List<Issue>): Report {
        val closed = issues.filter { it.resolved != null && !it.resolved.isBefore(it.created) }
        val statusTimes = mutableMapOf<String, MutableList<Long>>()
        closed.forEach { issue ->
            statusDurations(issue).forEach { (status, seconds) ->
                statusTimes.getOrPut(status) { mutableListOf() }.add(seconds)
            }
        }
        val createdByDay = issues.groupingBy { it.created.atZone(zone).toLocalDate() }.eachCount()
        val closedByDay = closed.groupingBy { it.resolved!!.atZone(zone).toLocalDate() }.eachCount()
        val days = if (issues.isEmpty()) emptyList() else {
            val first = minOf(createdByDay.keys.min(), closedByDay.keys.minOrNull() ?: createdByDay.keys.min())
            val last = maxOf(createdByDay.keys.max(), closedByDay.keys.maxOrNull() ?: createdByDay.keys.max())
            val result = mutableListOf<DailyCount>()
            var day = first
            var createdTotal = 0
            var closedTotal = 0
            while (!day.isAfter(last)) {
                val opened = createdByDay[day] ?: 0
                val closedNow = closedByDay[day] ?: 0
                createdTotal += opened
                closedTotal += closedNow
                result += DailyCount(day, opened, closedNow, createdTotal, closedTotal)
                day = day.plusDays(1)
            }
            result
        }
        val users = mutableMapOf<String, Triple<String, Int, Int>>()
        issues.forEach { issue ->
            issue.assignee?.let { user ->
                val previous = users[user.id]
                users[user.id] = Triple(user.name, (previous?.second ?: 0) + 1, previous?.third ?: 0)
            }
            issue.reporter?.let { user ->
                val previous = users[user.id]
                users[user.id] = Triple(user.name, previous?.second ?: 0, (previous?.third ?: 0) + 1)
            }
        }
        val topUsers = users.values.map { UserCount(it.first, it.second, it.third) }
            .sortedWith(compareByDescending<UserCount> { it.total }.thenBy { it.name }).take(30)
        return Report(
            issues.size, closed.size,
            histogram(closed.map { Duration.between(it.created, it.resolved!!).seconds }),
            statusTimes.toSortedMap().mapValues { histogram(it.value) },
            days, topUsers,
            histogram(closed.map { it.timeSpentSeconds }.filter { it > 0 }, 1),
            closed.count { it.timeSpentSeconds <= 0 },
            issues.groupingBy(Issue::priority).eachCount().toSortedMap()
        )
    }

    /** Returns seconds spent in each status before resolution, including repeated visits. */
    fun statusDurations(issue: Issue): Map<String, Long> {
        val end = issue.resolved ?: return emptyMap()
        if (end.isBefore(issue.created)) return emptyMap()
        val relevant = issue.changes.filter { !it.at.isBefore(issue.created) && !it.at.isAfter(end) }.sortedBy(StatusChange::at)
        var status = relevant.firstOrNull()?.from?.takeIf(String::isNotBlank) ?: issue.currentStatus
        var cursor = issue.created
        val result = mutableMapOf<String, Long>()
        relevant.forEach { change ->
            result[status] = (result[status] ?: 0L) + Duration.between(cursor, change.at).seconds
            status = change.to?.takeIf(String::isNotBlank) ?: status
            cursor = change.at
        }
        result[status] = (result[status] ?: 0L) + Duration.between(cursor, end).seconds
        return result
    }

    /** Groups durations by time; larger samples put the longest values beyond the main range in an overflow bin. */
    fun histogram(seconds: List<Long>, minimumHours: Long = minimumBinHours): List<HistogramBin> {
        if (seconds.isEmpty()) return emptyList()
        require(minimumHours > 0) { "minimumHours must be positive" }
        val hours = seconds.map { it.coerceAtLeast(0) / 3600 }.sorted()
        val mainRange = if (hours.size < 10) hours.last() else hours[((hours.size - 1) * 0.95).toInt()]
        val rawWidth = maxOf(minimumHours, ceil((mainRange + 1) / 30.0).toLong())
        val width = if (minimumHours >= 24) ((rawWidth + 23) / 24) * 24 else rawWidth
        val regularCount = minOf(30L, mainRange / width + 1).toInt()
        val counts = IntArray(regularCount)
        var overflowCount = 0
        hours.forEach { value ->
            val bin = value / width
            if (bin >= regularCount) overflowCount++ else counts[bin.toInt()]++
        }
        val regular = counts.mapIndexed { index, count ->
            HistogramBin(index * width, (index + 1) * width, count)
        }
        return regular + if (overflowCount > 0) listOf(HistogramBin(regularCount * width, regularCount * width, overflowCount, true)) else emptyList()
    }
}
