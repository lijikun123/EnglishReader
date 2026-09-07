package com.jikunli.englishreader.sync

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.response.respondText
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebReaderRoutesTest {
    @Test
    fun servesReaderAndModulesWithoutChangingApiRoutes() = testApplication {
        application {
            routing {
                webReaderRoutes()
                get("/v1/existing") { call.respondText("unchanged") }
            }
        }
        for (asset in listOf("", "index.html", "app.js", "api.js", "store.js", "sync.js", "reader.js", "styles.css", "icon.svg")) {
            val response = client.get("/web/$asset")
            assertEquals(HttpStatusCode.OK, response.status, asset)
            assertEquals("nosniff", response.headers["X-Content-Type-Options"])
            assertEquals("no-cache", response.headers["Cache-Control"])
            assertTrue(response.headers["Content-Security-Policy"]!!.contains("connect-src 'self'"))
            assertTrue(response.bodyAsText().isNotBlank())
        }
        assertEquals("unchanged", client.get("/v1/existing").bodyAsText())
        assertEquals(HttpStatusCode.NotFound, client.get("/web/db/migration/V1__initial_schema.sql").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/web/missing.js").status)
    }

    @Test
    fun trailingSlashRedirectPreservesReverseProxyPrefix() = testApplication {
        application { routing { webReaderRoutes() } }
        val noRedirect = createClient { followRedirects = false }
        val response = noRedirect.get("/web")
        assertEquals(HttpStatusCode.MovedPermanently, response.status)
        assertEquals("web/", response.headers["Location"])
        assertEquals("/kreader-sync/web/", java.net.URI("/kreader-sync/web").resolve(response.headers["Location"]).path)
    }
}
