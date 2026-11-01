package ru.polytech.jira

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.nio.file.Path
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JiraClientIntegrationTest {
    @Test fun `search returns full changelogs in batches without issue requests`() {
        FakeJira { path, body ->
            when {
                path == "/rest/api/2/search" && body.contains("\"startAt\":0") -> fixture("search-page-0.json")
                path == "/rest/api/2/search" -> fixture("search-page-1.json")
                else -> error("Unexpected request $path")
            }
        }.use { jira ->
            val config = jira.config()
            val issues = JiraClient(config).fetchIssues()
            assertEquals(2, issues.size)
            assertEquals(1, issues.first().changes.size)
            assertEquals(3600, issues.first().timeSpentSeconds)
            assertEquals(2, jira.requests.count { it == "/rest/api/2/search" })
            assertTrue(jira.requests.none { it.startsWith("/rest/api/2/issue/") })
            assertTrue(jira.searchBodies.all { it.contains("\"expand\":[\"changelog\"]") })
            assertEquals(1, Analyzer(config.zone, 24).analyze(issues).closedCount)

            val preview = JiraClient(config.copy(maxIssues = 1, newestFirst = true)).fetchIssues()
            assertEquals(1, preview.size)
            assertEquals(3, jira.requests.count { it == "/rest/api/2/search" })
            assertTrue(jira.searchBodies.last().contains("ORDER BY created DESC"))
        }
    }

    @Test fun `truncated batch changelog falls back to full issue history`() {
        FakeJira { path, _ ->
            when (path) {
                "/rest/api/2/search" -> fixture("search-truncated.json")
                "/rest/api/2/issue/T-1?expand=changelog" -> fixture("issue-full.json")
                else -> error("Unexpected request $path")
            }
        }.use { jira ->
            val issues = JiraClient(jira.config()).fetchIssues()
            assertEquals(2, issues.single().changes.size)
            assertEquals(1, jira.requests.count { it.startsWith("/rest/api/2/issue/") })
        }
    }

    @Test fun `full project uses ID cursor beyond search offset windows`() {
        FakeJira { path, body ->
            require(path == "/rest/api/2/search")
            when {
                body.contains("id > 102") -> """{"total":0,"issues":[]}"""
                body.contains("id > 101") -> fixture("search-page-1.json").replace("\"total\": 2", "\"total\": 1")
                else -> fixture("search-page-0.json")
            }
        }.use { jira ->
            val issues = JiraClient(jira.config().copy(maxIssues = 0)).fetchIssues()
            assertEquals(setOf("T-1", "T-2"), issues.map(Issue::key).toSet())
            assertEquals(3, jira.searchBodies.size)
            assertTrue(jira.searchBodies.all { it.contains("\"startAt\":0") })
            assertTrue(jira.searchBodies.any { it.contains("id > 101") })
        }
    }

    @Test fun `missing search total fails instead of producing empty report`() {
        FakeJira { _, _ -> """{"issues":[]}""" }.use { jira ->
            assertFailsWith<IllegalStateException> { JiraClient(jira.config()).fetchIssues() }
        }
    }

    @Test fun `rate limit response is retried`() {
        val attempts = AtomicInteger()
        FakeJira { _, _ -> """{"total":0,"issues":[]}""" }.use { jira ->
            jira.statusFor = { _, _ -> if (attempts.getAndIncrement() == 0) 429 else 200 }
            assertTrue(JiraClient(jira.config()).fetchIssues().isEmpty())
            assertEquals(2, attempts.get())
        }
    }

    private fun fixture(name: String): String = javaClass.getResource("/fixtures/$name")
        ?.readText(Charsets.UTF_8) ?: error("Missing test fixture $name")

    private class FakeJira(private val respond: (String, String) -> String) : AutoCloseable {
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requests = CopyOnWriteArrayList<String>()
        val searchBodies = CopyOnWriteArrayList<String>()
        var statusFor: (String, String) -> Int = { _, _ -> 200 }

        init {
            server.createContext("/") { exchange ->
                val path = exchange.requestURI.toString()
                val body = exchange.requestBody.bufferedReader().readText()
                requests += path
                if (path == "/rest/api/2/search") searchBodies += body
                val status = statusFor(path, body)
                val response = if (status == 200) respond(path, body) else """{"error":"temporarily unavailable"}"""
                val bytes = response.toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                if (status == 429) exchange.responseHeaders.add("Retry-After", "0")
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()
        }

        fun config() = AppConfig("http://127.0.0.1:${server.address.port}", "T", "", "",
            ZoneId.of("UTC"), Path.of("report"), 24, 1, maxIssues = 2)

        override fun close() = server.stop(0)
    }
}
