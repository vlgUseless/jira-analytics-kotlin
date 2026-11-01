package ru.polytech.jira

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
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
        var start = 0
        while (true) {
            val body = JsonObject().apply {
                addProperty("jql", "project = ${config.project} ORDER BY created ASC")
                addProperty("startAt", start)
                addProperty("maxResults", config.pageSize)
                add("fields", com.google.gson.JsonArray().apply {
                    listOf("created", "resolutiondate", "status", "assignee", "reporter", "priority", "timespent").forEach { add(it) }
                })
            }
            val page = request("POST", "/rest/api/2/search", body.toString())
            val entries = page.array("issues")
            if (entries.size() == 0 && start < page.int("total")) error("Jira returned an empty page before all issues were read")
            for (entry in entries) {
                val summary = entry.asJsonObject
                val fields = summary.obj("fields")
                val resolved = fields.string("resolutiondate")?.let(::parseDate)
                val detail = if (resolved != null) request("GET", "/rest/api/2/issue/${encode(summary.string("key")!!)}?expand=changelog") else null
                issues += parseIssue(summary, detail)
            }
            start += entries.size()
            if (start >= page.int("total")) break
        }
        return issues
    }

    private fun parseIssue(summary: JsonObject, detail: JsonObject?): Issue {
        val fields = summary.obj("fields")
        val changelog = detail?.obj("changelog")
        val histories = changelog?.array("histories") ?: JsonArray()
        if (changelog != null && histories.size() < changelog.int("total")) {
            error("Incomplete changelog for ${summary.string("key")}: Jira returned ${histories.size()} of ${changelog.int("total")}")
        }
        val changes = histories.flatMap { history ->
            val h = history.asJsonObject
            val at = parseDate(h.string("created")!!)
            h.array("items").mapNotNull { item ->
                val i = item.asJsonObject
                if (i.string("field")?.equals("status", true) == true) StatusChange(at, i.string("fromString"), i.string("toString")) else null
            }
        }.sortedBy(StatusChange::at)
        fun user(field: String): JiraUser? = fields.objOrNull(field)?.let {
            JiraUser(it.string("accountId") ?: it.string("key") ?: it.string("name") ?: it.string("displayName") ?: "unknown",
                it.string("displayName") ?: it.string("name") ?: "Unknown")
        }
        return Issue(
            summary.string("key") ?: error("Issue has no key"),
            parseDate(fields.string("created") ?: error("Issue has no creation date")),
            fields.string("resolutiondate")?.let(::parseDate),
            fields.obj("status").string("name") ?: "Unknown",
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
        val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        if (response.statusCode() !in 200..299) error("Jira HTTP ${response.statusCode()} at $path: ${response.body().take(300)}")
        return try { JsonParser().parse(response.body()).asJsonObject }
        catch (e: RuntimeException) { throw IllegalStateException("Invalid Jira JSON at $path", e) }
    }

    private fun encode(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8)
}

internal fun parseDate(value: String): Instant = try {
    OffsetDateTime.parse(value).toInstant()
} catch (_: java.time.format.DateTimeParseException) {
    OffsetDateTime.parse(value, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss[.SSS]Z")).toInstant()
}
private fun JsonObject.string(key: String): String? = get(key)?.takeUnless(JsonElement::isJsonNull)?.asString
private fun JsonObject.int(key: String): Int = get(key)?.takeUnless(JsonElement::isJsonNull)?.asInt ?: 0
private fun JsonObject.long(key: String): Long? = get(key)?.takeUnless(JsonElement::isJsonNull)?.asLong
private fun JsonObject.obj(key: String): JsonObject = objOrNull(key) ?: JsonObject()
private fun JsonObject.objOrNull(key: String): JsonObject? = get(key)?.takeUnless(JsonElement::isJsonNull)?.asJsonObject
private fun JsonObject.array(key: String): JsonArray = get(key)?.takeUnless(JsonElement::isJsonNull)?.asJsonArray ?: JsonArray()
