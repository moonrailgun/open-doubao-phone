package com.moonrailgun.opendoubao.assistant.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val REPOSITORY = "https://github.com/moonrailgun/open-doubao-phone"
private val client = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()

data class ReleaseUpdate(val version: String, val notes: String, val releaseUrl: String, val downloadUrl: String?)

private fun versionParts(version: String): List<Long> {
    val match = Regex("^v?(\\d+)\\.(\\d+)\\.(\\d+)(-[0-9A-Za-z.-]+)?(?:\\+[0-9A-Za-z.-]+)?$").matchEntire(version)
        ?: throw IllegalArgumentException("无法识别版本号")
    return match.groupValues.slice(1..3).map { it.toLong() } + if (match.groupValues[4].isEmpty()) 1L else 0L
}

internal fun parseRelease(release: JSONObject, currentVersion: String): ReleaseUpdate? {
    if (release.optBoolean("draft") || release.optBoolean("prerelease")) return null
    val tag = release.getString("tag_name")
    val latest = versionParts(tag)
    val current = versionParts(currentVersion)
    if (latest[3] == 0L) return null
    val difference = latest.indices.firstOrNull { latest[it] != current[it] } ?: return null
    if (latest[difference] < current[difference]) return null
    val version = tag.removePrefix("v")
    val assets = release.optJSONArray("assets")
    val download = (0 until (assets?.length() ?: 0)).map { assets!!.getJSONObject(it) }.firstOrNull {
        it.optString("name") == "open-doubao-phone-$version.apk" &&
            it.optString("browser_download_url").startsWith("$REPOSITORY/releases/download/$tag/")
    }?.getString("browser_download_url")
    return ReleaseUpdate(version, release.optString("body").takeUnless { it.isBlank() || it == "null" }
        ?: "此版本未提供更新说明。", "$REPOSITORY/releases/tag/$tag", download)
}

suspend fun checkReleaseUpdate(currentVersion: String): ReleaseUpdate? = withContext(Dispatchers.IO) {
    val request = Request.Builder().url("https://api.github.com/repos/moonrailgun/open-doubao-phone/releases/latest")
        .header("Accept", "application/vnd.github+json").header("User-Agent", "Open-Doubao/$currentVersion").build()
    client.newCall(request).execute().use { response ->
        if (response.code == 404) return@withContext null
        check(response.isSuccessful) { "GitHub HTTP ${response.code}" }
        parseRelease(JSONObject(checkNotNull(response.body).string()), currentVersion)
    }
}
