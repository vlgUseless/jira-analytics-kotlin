package ru.polytech.jira

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.time.ZoneId
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JiraClientIntegrationTest {
    @Test fun `client reads all search pages and status history from HTTP server`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requests = mutableListOf<String>()
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.toString()
            requests += path
            val response = when {
                path == "/rest/api/2/search" -> {
                    val body = exchange.requestBody.bufferedReader().readText()
                    if (body.contains("\"startAt\":0")) """{"total":2,"issues":[{"key":"T-1","fields":{"created":"2024-01-01T00:00:00.000+0000","resolutiondate":"2024-01-02T00:00:00.000+0000","status":{"name":"Done"},"priority":{"name":"High"},"timespent":3600}}]}"""
                    else """{"total":2,"issues":[{"key":"T-2","fields":{"created":"2024-01-03T00:00:00.000+0000","resolutiondate":null,"status":{"name":"Open"},"priority":null,"timespent":null}}]}"""
                }
                path == "/rest/api/2/issue/T-1?expand=changelog" -> """{"changelog":{"total":1,"histories":[{"created":"2024-01-01T12:00:00.000+0000","items":[{"field":"status","fromString":"Open","toString":"Done"}]}]}}"""
                else -> error("Unexpected path $path")
            }
            val bytes = response.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val config = AppConfig("http://127.0.0.1:${server.address.port}", "T", "", "", ZoneId.of("UTC"), Path.of("report"), 24, 1)
            val issues = JiraClient(config).fetchIssues()
            assertEquals(2, issues.size)
            assertEquals(1, issues.first().changes.size)
            assertEquals(3600, issues.first().timeSpentSeconds)
            assertTrue(requests.count { it == "/rest/api/2/search" } == 2)
            assertEquals(1, Analyzer(config.zone, 24).analyze(issues).closedCount)
        } finally {
            server.stop(0)
        }
    }
}
