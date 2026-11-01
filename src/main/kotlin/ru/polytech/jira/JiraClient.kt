package ru.polytech.jira

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64

/** Fetches Jira issues through the Server/Data Center REST API. */
class JiraClient(private val config: AppConfig, private val http: HttpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(20)).build()) {
    /** Reads every search page, including full changelogs for closed issues. */
    fun fetchIssues(): List<Issue> {
        val issues = mutableListOf<Issue>()
        val seenKeys = mutableSetOf<String>()
        var start = 0
        var lastId: Long? = null
        var initialTotal: Int? = null
        val fullProject = config.maxIssues == 0
        while (true) {
            val remaining = if (fullProject) config.pageSize else minOf(config.pageSize, config.maxIssues - issues.size)
            val jql = if (fullProject) {
                "project = ${config.project}" + (lastId?.let { " AND id > $it" } ?: "") + " ORDER BY id ASC"
            } else {
                "project = ${config.project} ORDER BY created ${config.sortOrder}, id ${config.sortOrder}"
            }
            val body = JsonObject().apply {
                addProperty("jql", jql)
                addProperty("startAt", if (fullProject) 0 else start)
                addProperty("maxResults", remaining)
                add("expand", JsonArray().apply { add("changelog") })
                add("fields", JsonArray().apply {
                    listOf("created", "resolutiondate", "status", "assignee", "reporter", "priority", "timespent").forEach { add(it) }
                })
            }
            val page = request("POST", "/rest/api/2/search", body.toString())
            val entries = page.requiredArray("issues")
            val total = page.requiredInt("total")
            require(total >= 0) { "Jira returned a negative search total" }
            if (initialTotal == null) initialTotal = total
            if (entries.size() == 0) {
                if (!fullProject && start < total) error("Jira returned an empty page before all issues were read")
                break
            }
            val target = if (fullProject) initialTotal else minOf(total, config.maxIssues)
            System.err.println("Jira: ${issues.size}/$target loaded; search returned ${entries.size()} issues")
            if (fullProject) {
                val ids = entries.map { it.asJsonObject.requiredString("id").toLongOrNull()
                    ?: error("Jira returned a nonnumeric issue ID") }
                require(ids.zipWithNext().all { (a, b) -> a < b } && (lastId == null || ids.first() > lastId!!)) {
                    "Jira returned issue IDs out of order"
                }
                lastId = ids.last()
            }
            entries.forEachIndexed { index, entry ->
                val summary = entry.asJsonObject
                val fields = summary.requiredObject("fields")
                val resolved = fields.string("resolutiondate")?.let(::parseDate)
                val detail = if (resolved != null && !summary.hasCompleteChangelog()) {
                    request("GET", "/rest/api/2/issue/${encode(summary.requiredString("key"))}?expand=changelog")
                } else null
                val issue = parseIssue(summary, detail ?: summary)
                check(seenKeys.add(issue.key)) { "Jira returned duplicate issue ${issue.key} while paging" }
                issues += issue
                if ((index + 1) % 10 == 0 || index + 1 == entries.size()) {
                    System.err.println("Jira: ${issues.size}/$target loaded")
                }
            }
            if (!fullProject) {
                start += entries.size()
                if (start >= total || issues.size >= config.maxIssues) break
            }
        }
        return issues
    }

    private fun parseIssue(summary: JsonObject, detail: JsonObject): Issue {
        val fields = summary.requiredObject("fields")
        val resolved = fields.string("resolutiondate")?.let(::parseDate)
        val changelog = detail.objOrNull("changelog")
        if (resolved != null && !detail.hasCompleteChangelog()) {
            error("Incomplete changelog for ${summary.requiredString("key")}: Jira did not return the full history")
        }
        val histories = changelog?.requiredArray("histories") ?: JsonArray()
        val changes = histories.flatMap { history ->
            val h = history.asJsonObject
            val at = parseDate(h.requiredString("created"))
            h.requiredArray("items").mapNotNull { item ->
                val i = item.asJsonObject
                if (i.string("field")?.equals("status", true) == true) StatusChange(at, i.string("fromString"), i.string("toString")) else null
            }
        }.sortedBy(StatusChange::at)
        fun user(field: String): JiraUser? = fields.objOrNull(field)?.let {
            JiraUser(it.string("accountId") ?: it.string("key") ?: it.string("name") ?: it.string("displayName") ?: "unknown",
                it.string("displayName") ?: it.string("name") ?: "Unknown")
        }
        return Issue(
            summary.requiredString("key"),
            parseDate(fields.requiredString("created")),
            resolved,
            fields.requiredObject("status").requiredString("name"),
            changes, user("assignee"), user("reporter"),
            fields.objOrNull("priority")?.string("name") ?: "Unspecified",
            fields.long("timespent") ?: 0L
        )
    }

    private fun request(method: String, path: String, body: String? = null): JsonObject {
        val builder = HttpRequest.newBuilder(URI.create(config.baseUrl + path))
            .timeout(Duration.ofSeconds(60)).header("Accept", "application/json")
        if (config.token.isNotBlank()) {
            val credentials = if (config.user.isBlank()) "Bearer ${config.token}" else
                "Basic " + Base64.getEncoder().encodeToString("${config.user}:${config.token}".toByteArray(StandardCharsets.UTF_8))
            builder.header("Authorization", credentials)
        }
        if (method == "POST") builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body!!))
        else builder.GET()
        val builtRequest = builder.build()
        for (attempt in 1..3) {
            val response = try {
                http.send(builtRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            } catch (error: IOException) {
                if (attempt == 3) throw IOException("Jira request failed at $path after 3 attempts", error)
                pauseBeforeRetry(attempt, null)
                continue
            }
            if (response.statusCode() in setOf(429, 500, 502, 503, 504) && attempt < 3) {
                System.err.println("Jira HTTP ${response.statusCode()} at $path; retrying ($attempt/3)")
                pauseBeforeRetry(attempt, response.headers().firstValue("Retry-After").orElse(null))
                continue
            }
            if (response.statusCode() !in 200..299) {
                error("Jira HTTP ${response.statusCode()} at $path: ${response.body().take(300)}")
            }
            return try { JsonParser().parse(response.body()).asJsonObject }
            catch (e: RuntimeException) { throw IllegalStateException("Invalid Jira JSON at $path", e) }
        }
        error("Jira request failed at $path")
    }

    private fun pauseBeforeRetry(attempt: Int, retryAfter: String?) {
        val delayMs = retryAfter?.toLongOrNull()?.coerceIn(0, 5)?.times(1000)
            ?: minOf(2000L, 500L * (1L shl (attempt - 1)))
        Thread.sleep(delayMs)
    }

    private fun encode(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8)
}

internal fun parseDate(value: String): Instant = try {
    OffsetDateTime.parse(value).toInstant()
} catch (_: java.time.format.DateTimeParseException) {
    OffsetDateTime.parse(value, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss[.SSS]Z")).toInstant()
}
private fun JsonObject.string(key: String): String? = get(key)?.takeUnless(JsonElement::isJsonNull)?.asString
private fun JsonObject.requiredString(key: String): String = string(key) ?: error("Jira response is missing '$key'")
private fun JsonObject.requiredInt(key: String): Int = get(key)?.takeUnless(JsonElement::isJsonNull)?.asInt
    ?: error("Jira response is missing '$key'")
private fun JsonObject.long(key: String): Long? = get(key)?.takeUnless(JsonElement::isJsonNull)?.asLong
private fun JsonObject.requiredObject(key: String): JsonObject = objOrNull(key) ?: error("Jira response is missing '$key'")
private fun JsonObject.objOrNull(key: String): JsonObject? = get(key)?.takeUnless(JsonElement::isJsonNull)?.asJsonObject
private fun JsonObject.requiredArray(key: String): JsonArray = get(key)?.takeUnless(JsonElement::isJsonNull)?.asJsonArray
    ?: error("Jira response is missing '$key'")
private fun JsonObject.hasCompleteChangelog(): Boolean {
    val changelog = objOrNull("changelog") ?: return false
    val total = changelog.get("total")?.takeUnless(JsonElement::isJsonNull)?.asInt ?: return false
    val histories = changelog.get("histories")?.takeUnless(JsonElement::isJsonNull)?.asJsonArray ?: return false
    return total >= 0 && histories.size() == total
}
