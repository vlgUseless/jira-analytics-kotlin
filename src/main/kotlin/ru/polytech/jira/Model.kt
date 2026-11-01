package ru.polytech.jira

import java.time.Instant

data class JiraUser(val id: String, val name: String)
data class StatusChange(val at: Instant, val from: String?, val to: String?)
data class Issue(
    val key: String,
    val created: Instant,
    val resolved: Instant?,
    val currentStatus: String,
    val changes: List<StatusChange>,
    val assignee: JiraUser?,
    val reporter: JiraUser?,
    val priority: String,
    val timeSpentSeconds: Long
)

data class DailyCount(val day: java.time.LocalDate, val created: Int, val closed: Int, val createdTotal: Int, val closedTotal: Int)
data class UserCount(val name: String, val assignee: Int, val reporter: Int) {
    val total: Int get() = assignee + reporter
}
data class HistogramBin(val fromHours: Long, val toHours: Long, val count: Int)
data class Report(
    val issueCount: Int,
    val closedCount: Int,
    val openDuration: List<HistogramBin>,
    val statusDurations: Map<String, List<HistogramBin>>,
    val daily: List<DailyCount>,
    val users: List<UserCount>,
    val worklogDuration: List<HistogramBin>,
    val priorities: Map<String, Int>
)
