package com.ts10.inspector.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GithubReleasesTest {
    @Test
    fun `parses tag notes and assets of the latest release`() {
        val release = parseGithubRelease(
            """
            {"tag_name":"v0.4.48","body":"Novedades",
             "assets":[
               {"name":"astra-h-launcher-0.4.48.apk","browser_download_url":"https://github.com/a/b/releases/download/v0.4.48/astra-h-launcher-0.4.48.apk","size":27363054},
               {"name":"astra-h-launcher-0.4.48.apk.sha256","browser_download_url":"https://github.com/a/b/releases/download/v0.4.48/astra-h-launcher-0.4.48.apk.sha256","size":99}
             ]}
            """.trimIndent(),
        )

        assertEquals("0.4.48", release.version)
        assertEquals("Novedades", release.notes)
        val apk = release.asset { it.endsWith(".apk") }!!
        assertEquals(27363054L, apk.sizeBytes)
        assertEquals("astra-h-launcher-0.4.48.apk.sha256", release.checksumFor(apk)!!.name)
    }

    @Test
    fun `release without checksum has no checksum asset`() {
        val release = parseGithubRelease(
            """{"tag_name":"0.5.0","assets":[{"name":"a.apk","browser_download_url":"https://x/a.apk","size":1}]}""",
        )
        assertNull(release.checksumFor(release.assets.single()))
    }

    @Test
    fun `compares versions numerically`() {
        assertTrue(compareVersions("0.4.48", "0.4.47") > 0)
        assertTrue(compareVersions("0.4.10", "0.4.9") > 0)
        assertTrue(compareVersions("0.5", "0.4.99") > 0)
        assertEquals(0, compareVersions("v0.4.47", "0.4.47"))
        assertEquals(0, compareVersions("0.4.47-beta", "0.4.47"))
        assertTrue(compareVersions("0.4.46", "0.4.47") < 0)
    }

    @Test
    fun `reads sha256sum output and bare hashes`() {
        val hash = "6666ed875820da384a3df2adfb482f389d3076956034423c45c1672542e17400"
        assertEquals(hash, parseSha256File("$hash  astra-h-launcher-0.4.47.apk\n"))
        assertEquals(hash, parseSha256File(hash.uppercase()))
        assertNull(parseSha256File("no es un hash"))
    }
}
