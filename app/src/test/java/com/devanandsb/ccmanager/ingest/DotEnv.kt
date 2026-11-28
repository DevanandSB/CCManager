package com.devanandsb.ccmanager.ingest

import java.io.File

/**
 * Loads developer-local configuration from the repository `.env` file with `System.getenv` priority.
 * Keeps private test expectations and paths out of version-controlled source files.
 */
internal object DotEnv {
    private val values: Map<String, String> by lazy { load() }

    fun get(key: String): String? =
        System.getenv(key)?.takeIf { it.isNotBlank() } ?: values[key]?.takeIf { it.isNotBlank() }

    private fun load(): Map<String, String> {
        val envFile = listOf(File(".env"), File("../.env")).firstOrNull { it.isFile } ?: return emptyMap()
        val map = LinkedHashMap<String, String>()
        envFile.readLines().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            val cleaned = line.removePrefix("export ").trim()
            val eq = cleaned.indexOf('=')
            if (eq <= 0) return@forEach
            val k = cleaned.substring(0, eq).trim()
            var v = cleaned.substring(eq + 1).trim()
            if ((v.startsWith('"') && v.endsWith('"') && v.length >= 2) ||
                (v.startsWith('\'') && v.endsWith('\'') && v.length >= 2)
            ) {
                v = v.substring(1, v.length - 1)
            }
            if (k.isNotEmpty()) map[k] = v
        }
        return map
    }
}
