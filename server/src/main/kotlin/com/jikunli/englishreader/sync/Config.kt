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
            )
        }
    }
}
