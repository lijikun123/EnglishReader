package com.jikunli.englishreader.sync

import io.ktor.http.ContentType
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** Same-origin web client; the existing authenticated API and Android contract stay intact. */
fun Route.webReaderRoutes() {
    get("/web") {
        // Relative redirect preserves a reverse proxy's /kreader-sync/ prefix.
        call.respondRedirect("web/", permanent = true)
    }
    val assets = mapOf(
        "index.html" to "text/html; charset=utf-8",
        "styles.css" to "text/css; charset=utf-8",
        "app.js" to "text/javascript; charset=utf-8",
        "api.js" to "text/javascript; charset=utf-8",
        "sync.js" to "text/javascript; charset=utf-8",
        "store.js" to "text/javascript; charset=utf-8",
        "reader.js" to "text/javascript; charset=utf-8",
        "icon.svg" to "image/svg+xml",
    )
    for ((name, mime) in assets) {
        // Only explicitly listed public assets are exposed, never arbitrary classpath resources.
        val bytes = checkNotNull(object {}.javaClass.getResourceAsStream("/web/$name")) {
            "Missing web reader asset: $name"
        }.use { it.readBytes() }
        val paths = if (name == "index.html") listOf("/web/", "/web/index.html") else listOf("/web/$name")
        for (path in paths) {
            get(path) {
                call.response.header("Cache-Control", "no-cache")
                call.response.header("X-Content-Type-Options", "nosniff")
                call.response.header("Referrer-Policy", "no-referrer")
                call.response.header("X-Frame-Options", "DENY")
                call.response.header("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'")
                call.respondBytes(bytes, ContentType.parse(mime))
            }
        }
    }
}
