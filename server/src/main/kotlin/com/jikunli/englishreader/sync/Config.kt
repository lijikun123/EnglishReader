package com.jikunli.englishreader.sync

data class AppConfig(
    val host: String,
    val port: Int,
    val databaseUrl: String,
    val databaseUser: String,
    val databasePassword: String,
    val jwtSecret: String,
    val jwtIssuer: String,
    val jwtAudience: String,
    val allowRegistration: Boolean,
    val maxBundleBytes: Long,
    val maxJsonBytes: Long,
    val maxAuthAttemptsPerMinute: Int,
    val aiApiKey: String?,
    val aiBaseUrl: String,
    val aiModel: String,
    val maxAiInputChars: Int,
    val maxAiRequestsPerMinute: Int,
) {
    companion object {
        fun fromEnvironment(environment: Map<String, String> = System.getenv()): AppConfig {
            fun required(name: String): String =
                environment[name]?.takeIf { it.isNotBlank() }
                    ?: error("Missing required environment variable: $name")

            val jwtSecret = required("KREADER_JWT_SECRET")
            require(jwtSecret.length >= 32) {
                "KREADER_JWT_SECRET must be at least 32 characters long"
            }

            return AppConfig(
                host = environment["KREADER_HOST"] ?: "0.0.0.0",
                port = environment["KREADER_PORT"]?.toIntOrNull() ?: 8080,
                databaseUrl = required("KREADER_DATABASE_URL"),
                databaseUser = required("KREADER_DATABASE_USER"),
                databasePassword = required("KREADER_DATABASE_PASSWORD"),
                jwtSecret = jwtSecret,
                jwtIssuer = environment["KREADER_JWT_ISSUER"] ?: "kreader-sync",
                jwtAudience = environment["KREADER_JWT_AUDIENCE"] ?: "kreader-android",
                // Public registration is intentionally opt-in. Enable it only while
                // creating the owner's first account, then turn it back off.
                allowRegistration = environment["KREADER_ALLOW_REGISTRATION"]?.toBooleanStrictOrNull() ?: false,
                maxBundleBytes = environment["KREADER_MAX_BUNDLE_BYTES"]?.toLongOrNull() ?: 26_214_400L,
                maxJsonBytes = environment["KREADER_MAX_JSON_BYTES"]?.toLongOrNull() ?: 65_536L,
                maxAuthAttemptsPerMinute = environment["KREADER_MAX_AUTH_ATTEMPTS_PER_MINUTE"]?.toIntOrNull() ?: 10,
                // AI is optional. Without a key the reader still works and reports
                // the feature as unavailable instead of failing at startup.
                aiApiKey = environment["KREADER_AI_API_KEY"]?.trim()?.takeIf { it.isNotEmpty() },
                aiBaseUrl = environment["KREADER_AI_BASE_URL"]?.trim()?.ifEmpty { null }
                    ?: "https://api.deepseek.com",
                aiModel = environment["KREADER_AI_MODEL"]?.trim()?.ifEmpty { null }
                    ?: "deepseek-v4-flash",
                maxAiInputChars = environment["KREADER_MAX_AI_INPUT_CHARS"]?.toIntOrNull()?.coerceIn(500, 20_000)
                    ?: 6_000,
                maxAiRequestsPerMinute = environment["KREADER_MAX_AI_REQUESTS_PER_MINUTE"]?.toIntOrNull()
                    ?.coerceIn(1, 300) ?: 60,
            )
        }
    }
}
