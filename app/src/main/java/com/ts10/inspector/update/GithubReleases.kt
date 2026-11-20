package com.ts10.inspector.update

import com.ts10.inspector.ui.fail
import com.ts10.inspector.R
import com.ts10.inspector.AppVersion
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

internal data class GithubAsset(val name: String, val downloadUrl: String, val sizeBytes: Long)

internal data class GithubRelease(
    val tag: String,
    /** Versión sin el prefijo `v` de la etiqueta, p. ej. `0.4.48`. */
    val version: String,
    val notes: String,
    val assets: List<GithubAsset>,
) {
    fun asset(predicate: (String) -> Boolean): GithubAsset? = assets.firstOrNull { predicate(it.name) }

    /** Suma SHA-256 publicada junto a un recurso: `<nombre>.sha256`. */
    fun checksumFor(asset: GithubAsset): GithubAsset? = asset { it == "${asset.name}.sha256" }
}

/** Acceso de solo lectura a las releases públicas de GitHub; nunca envía datos del usuario. */
internal object GithubReleases {
    private const val API = "https://api.github.com/repos"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val MAX_METADATA_CHARS = 512 * 1024

    fun latest(repository: String): GithubRelease {
        val connection = open("$API/$repository/releases/latest")
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> Unit
                // GitHub responde 404 a un repositorio privado o sin releases publicadas.
                HttpURLConnection.HTTP_NOT_FOUND -> fail(R.string.gh_no_public_releases)
                else -> fail(R.string.gh_http_error, code)
            }
            return parseGithubRelease(connection.inputStream.bufferedReader().use { it.readBounded(MAX_METADATA_CHARS) })
        } finally {
            connection.disconnect()
        }
    }

    fun readText(asset: GithubAsset, maxChars: Int = 4_096): String {
        val connection = open(asset.downloadUrl)
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) fail(R.string.gh_download_error, connection.responseCode)
            return connection.inputStream.bufferedReader().use { it.readBounded(maxChars) }
        } finally {
            connection.disconnect()
        }
    }

    /** Descarga a un temporal y lo renombra al terminar; devuelve el SHA-256 calculado. */
    fun download(asset: GithubAsset, target: File, maxBytes: Long, onProgress: (Int) -> Unit): String {
        if (asset.sizeBytes > maxBytes) fail(R.string.gh_file_too_large)
        target.parentFile?.mkdirs()
        val partial = File(target.path + ".part")
        val digest = MessageDigest.getInstance("SHA-256")
        val connection = open(asset.downloadUrl)
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) fail(R.string.gh_download_error, connection.responseCode)
            var written = 0L
            var lastPercent = -1
            connection.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        written += read
                        if (written > maxBytes) fail(R.string.gh_file_too_large)
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                        val percent = if (asset.sizeBytes > 0) (written * 100 / asset.sizeBytes).toInt() else 0
                        if (percent != lastPercent) {
                            lastPercent = percent
                            onProgress(percent.coerceIn(0, 100))
                        }
                    }
                }
            }
            if (asset.sizeBytes > 0 && written != asset.sizeBytes) fail(R.string.gh_incomplete)
            target.delete()
            if (!partial.renameTo(target)) fail(R.string.gh_save_failed)
            return digest.digest().joinToString("") { "%02x".format(it) }
        } catch (error: Throwable) {
            partial.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection {
        require(url.startsWith("https://")) { "Solo se admiten descargas HTTPS" }
        return (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "AstraHLauncher/${AppVersion.name}")
        }
    }

    private fun java.io.Reader.readBounded(maxChars: Int): String {
        val result = StringBuilder()
        val buffer = CharArray(8 * 1024)
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            if (result.length + read > maxChars) fail(R.string.gh_response_too_large)
            result.append(buffer, 0, read)
        }
        return result.toString()
    }
}

internal fun parseGithubRelease(json: String): GithubRelease {
    val root = JSONObject(json)
    val tag = root.getString("tag_name")
    val assets = root.optJSONArray("assets")
    return GithubRelease(
        tag = tag,
        version = tag.trim().removePrefix("v").removePrefix("V"),
        notes = root.optString("body").orEmpty(),
        assets = buildList {
            for (index in 0 until (assets?.length() ?: 0)) {
                val asset = assets!!.getJSONObject(index)
                add(
                    GithubAsset(
                        name = asset.getString("name"),
                        downloadUrl = asset.getString("browser_download_url"),
                        sizeBytes = asset.optLong("size", 0L),
                    ),
                )
            }
        },
    )
}

/** Compara versiones numéricas `a.b.c`; un sufijo no numérico (`-beta`) se ignora. */
internal fun compareVersions(left: String, right: String): Int {
    fun parts(version: String) = version.trim().removePrefix("v")
        .substringBefore('-')
        .split('.')
        .map { part -> part.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
    val a = parts(left)
    val b = parts(right)
    for (index in 0 until maxOf(a.size, b.size)) {
        val difference = a.getOrElse(index) { 0 }.compareTo(b.getOrElse(index) { 0 })
        if (difference != 0) return difference
    }
    return 0
}

/** Admite el formato de `sha256sum` (`<hash>  <archivo>`) o solo el hash. */
internal fun parseSha256File(text: String): String? =
    Regex("\\b([0-9a-fA-F]{64})\\b").find(text)?.groupValues?.get(1)?.lowercase()
