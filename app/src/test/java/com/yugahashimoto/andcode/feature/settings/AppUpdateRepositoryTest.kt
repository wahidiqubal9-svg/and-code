package com.yugahashimoto.andcode.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppUpdateRepositoryTest {
    @Test
    fun `parses the version code from release notes`() {
        assertEquals(100123, parseVersionCode("Preview build.\n\nversionCode: 100123\n"))
        assertEquals(42, parseVersionCode("versioncode=42"))
        assertNull(parseVersionCode("no version here"))
        assertNull(parseVersionCode(null))
    }

    @Test
    fun `picks the newest published release above the installed version`() {
        val json =
            """
            [
              {"tag_name":"v1.0","body":"versionCode: 5","draft":false,
               "assets":[{"name":"app.apk","browser_download_url":"https://example.com/5.apk"}]},
              {"tag_name":"v2.0","body":"versionCode: 20","draft":false,
               "assets":[{"name":"app.apk","browser_download_url":"https://example.com/20.apk"}]},
              {"tag_name":"v3.0","body":"versionCode: 30","draft":true,
               "assets":[{"name":"app.apk","browser_download_url":"https://example.com/30.apk"}]},
              {"tag_name":"v4.0","body":"no code","draft":false,
               "assets":[{"name":"app.apk","browser_download_url":"https://example.com/40.apk"}]}
            ]
            """.trimIndent()

        val update = parseLatestUpdate(json, currentVersionCode = 10)

        assertEquals(20, update?.versionCode)
        assertEquals("v2.0", update?.versionName)
        assertEquals("https://example.com/20.apk", update?.apkUrl)
        assertNull(parseLatestUpdate(json, currentVersionCode = 25))
    }
}
