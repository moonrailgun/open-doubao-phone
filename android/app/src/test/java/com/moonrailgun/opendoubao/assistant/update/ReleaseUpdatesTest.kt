package com.moonrailgun.opendoubao.assistant.update

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ReleaseUpdatesTest {
    private val download = "https://github.com/moonrailgun/open-doubao-phone/releases/download/v1.10.0/open-doubao-phone-1.10.0.apk"
    private fun release() = JSONObject().put("tag_name", "v1.10.0").put("body", "修复问题")
        .put("assets", JSONArray().put(JSONObject().put("name", "open-doubao-phone-1.10.0.apk").put("browser_download_url", download)))

    @Test fun comparesNumericVersions() {
        assertEquals("1.10.0", parseRelease(release(), "1.9.0")?.version)
        assertNull(parseRelease(release(), "1.10.0"))
        assertNull(parseRelease(release(), "2.0.0"))
        assertNotNull(parseRelease(release(), "1.10.0-beta.1"))
        assertNull(parseRelease(release().put("prerelease", true), "1.9.0"))
        assertNull(parseRelease(release().put("draft", true), "1.9.0"))
    }

    @Test fun selectsOnlyTheReleaseApk() {
        assertEquals(download, parseRelease(release(), "1.9.0")?.downloadUrl)
        assertNull(parseRelease(release().put("assets", JSONArray()), "1.9.0")?.downloadUrl)
        val foreign = release()
        foreign.getJSONArray("assets").getJSONObject(0).put("browser_download_url", "https://example.com/app.apk")
        assertNull(parseRelease(foreign, "1.9.0")?.downloadUrl)
        val wrong = release()
        wrong.getJSONArray("assets").getJSONObject(0).put("name", "open-doubao-tts.apk")
        assertNull(parseRelease(wrong, "1.9.0")?.downloadUrl)
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsInvalidVersion() {
        parseRelease(release().put("tag_name", "unknown"), "1.9.0")
    }
}
