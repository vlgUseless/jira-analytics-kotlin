package ru.polytech.jira

import java.nio.file.Files
import java.nio.file.Path
import java.net.URI
import java.time.ZoneId
import java.util.Properties

data class AppConfig(
    val baseUrl: String,
    val project: String,
    val user: String,
    val token: String,
    val zone: ZoneId,
    val output: Path,
    val binHours: Long,
    val pageSize: Int,
    val maxIssues: Int = 0,
    val newestFirst: Boolean = true
) {
    val sortOrder: String get() = if (newestFirst) "DESC" else "ASC"

    override fun toString(): String =
        "AppConfig(baseUrl=$baseUrl, project=$project, user=$user, token=<redacted>, zone=$zone, output=$output, " +
            "binHours=$binHours, pageSize=$pageSize, maxIssues=$maxIssues, sortOrder=$sortOrder)"

    companion object {
        /** Loads and validates a distribution configuration file. Environment credentials take precedence. */
        fun load(path: Path, environment: Map<String, String> = System.getenv()): AppConfig {
            val properties = Properties()
            Files.newInputStream(path).use(properties::load)
            fun value(key: String) = properties.getProperty(key, "").trim()
            val baseUrl = value("jira.baseUrl").trimEnd('/')
            val uri = try { URI(baseUrl) } catch (_: Exception) { throw IllegalArgumentException("jira.baseUrl must be an HTTP(S) URL") }
            require(uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank() && uri.userInfo == null &&
                uri.query == null && uri.fragment == null) { "jira.baseUrl must be a plain HTTP(S) base URL" }
            val project = value("jira.project")
            require(project.matches(Regex("[A-Za-z][A-Za-z0-9_]*"))) { "jira.project must be a Jira project key" }
            val binHours = value("report.binHours").ifBlank { "24" }.toLongOrNull()
                ?: error("report.binHours must be an integer")
            require(binHours > 0) { "report.binHours must be positive" }
            val pageSize = value("jira.pageSize").ifBlank { "100" }.toIntOrNull()
                ?: error("jira.pageSize must be an integer")
            require(pageSize in 1..1000) { "jira.pageSize must be between 1 and 1000" }
            val maxIssues = value("jira.maxIssues").ifBlank { "0" }.toIntOrNull()
                ?: error("jira.maxIssues must be an integer")
            require(maxIssues >= 0) { "jira.maxIssues must be nonnegative (0 means all issues)" }
            require(!properties.containsKey("jira.sortOrder")) { "jira.sortOrder was replaced by jira.newestFirst=true or false" }
            val newestFirst = value("jira.newestFirst").ifBlank { "true" }.toBooleanStrictOrNull()
                ?: error("jira.newestFirst must be true or false")
            val output = Path.of(value("report.output").ifBlank { "report" })
            return AppConfig(
                baseUrl, project,
                environment["JIRA_USER"]?.takeIf(String::isNotBlank) ?: value("jira.user"),
                environment["JIRA_TOKEN"]?.takeIf(String::isNotBlank) ?: value("jira.token"),
                ZoneId.of(value("report.zone").ifBlank { "UTC" }), output, binHours, pageSize,
                maxIssues, newestFirst
            )
        }
    }
}
