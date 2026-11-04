package com.forge.workout.updates

import android.app.Application
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class GitHubReleaseSourceTest {
    @Test fun equalVersionIsCurrentEvenWithPrefixOrBuildMetadata() {
        assertEquals(UpdateCheckResult.UpToDate, check(release("v1.3.2"), installed = "V1.3.2+local"))
    }

    @Test fun comparesVersionComponentsNumerically() {
        val result = check(release("v1.10.0")) as UpdateCheckResult.Available
        assertEquals("1.10.0", result.release.version)
        assertEquals("v1.10.0", result.release.tag)
        assertEquals("Forge update", result.release.title)
        assertEquals(LocalDate.of(2026, 11, 1), result.release.publishedOn)
        assertEquals("https://github.com/ritikverma098/Forge-Fitness-Tracker/releases/download/v1.10.0/Forge-1.10.0.apk",
            result.release.apkUrl)
    }

    @Test fun olderReleaseIsNotOffered() {
        assertEquals(UpdateCheckResult.UpToDate, check(release("v1.2.999")))
    }

    @Test fun rejectsMalformedTagsAndPrereleases() {
        for (tag in listOf("latest", "v1.4", "v01.4.0", "v1.4.0-rc.1", "v1.4.0+")) {
            assertEquals(tag, UpdateCheckResult.NoValidRelease, check(release(tag)))
        }
        for (flag in listOf("draft", "prerelease")) {
            assertEquals(flag, UpdateCheckResult.NoValidRelease, check(release().put(flag, true)))
        }
    }

    @Test fun missingUnpublishedOrAmbiguousApkIsNotOffered() {
        assertEquals(UpdateCheckResult.NoValidRelease, check(release().put("assets", JSONArray())))
        val missing = asset("v1.4.0", "Forge-1.4.0.apk").put("state", "starter")
        assertEquals(UpdateCheckResult.NoValidRelease, check(release().put("assets", JSONArray().put(missing))))
        val ambiguous = JSONArray().put(asset("v1.4.0", "other.apk")).put(asset("v1.4.0", "another.apk"))
        assertEquals(UpdateCheckResult.NoValidRelease, check(release().put("assets", ambiguous)))
        ambiguous.put(asset("v1.4.0", "Forge-1.4.0.apk"))
        assertTrue(check(release().put("assets", ambiguous)) is UpdateCheckResult.Available)
    }

    @Test fun rejectsUntrustedAssetUrls() {
        val path = "/ritikverma098/Forge-Fitness-Tracker/releases/download/v1.4.0/Forge-1.4.0.apk"
        for (url in listOf(
            "http://github.com$path",
            "https://github.com.evil.test$path",
            "https://github.com@evil.test$path",
            "https://someone@github.com$path",
            "https://github.com:443$path",
            "https://github.com$path?redirect=1",
            "https://github.com$path#fragment",
            "https://github.com${path.replace("v1.4.0", "v1.5.0")}",
            "https://github.com${path.replace("ritikverma098", "someone-else")}",
            "https://github.com${path.replace("/releases/download/", "/releases%2Fdownload/")}",
            "https://github.com${path.replace("/releases/download/", "/../releases/download/")}"
        )) {
            val assets = JSONArray().put(asset("v1.4.0", "Forge-1.4.0.apk").put("browser_download_url", url))
            assertEquals(url, UpdateCheckResult.NoValidRelease, check(release().put("assets", assets)))
        }
    }

    @Test fun acceptsEmptyAndLongPlainChangelogs() {
        val long = "A fix with [link](https://example.com) and <script> text.\n".repeat(1500).trim()
        for (body in listOf("", long)) {
            val result = check(release().put("body", body)) as UpdateCheckResult.Available
            assertEquals(body, result.release.changelog)
        }
        val missingBody = release().apply { remove("body") }
        assertEquals("", (check(missingBody) as UpdateCheckResult.Available).release.changelog)
    }

    @Test fun handlesHttpFailuresAndRedirects() {
        assertEquals(UpdateCheckResult.NoValidRelease, check(release(), status = 404))
        for (status in listOf(302, 403, 429, 500)) {
            assertEquals(status.toString(), UpdateCheckResult.Unavailable, check(release(), status = status))
        }
    }

    @Test fun handlesOfflineAndTimeout() = runBlocking {
        for (failure in listOf(UnknownHostException(), SocketTimeoutException())) {
            val source = GitHubReleaseSource { throw failure }
            assertEquals(UpdateCheckResult.Unavailable, source.check("1.3.2"))
        }
    }

    @Test fun rejectsInvalidAndOversizedResponses() {
        for (body in listOf("{", "[]", release().toString() + "garbage", "x".repeat(512 * 1024 + 1))) {
            assertEquals(UpdateCheckResult.NoValidRelease, checkBody(body))
        }
        assertEquals(UpdateCheckResult.NoValidRelease, check(release().put("published_at", "bad date")))
    }

    @Test fun requestsOnlyOfficialEndpointOffTheCallerThreadAndClosesConnection() = runBlocking {
        val caller = Thread.currentThread()
        lateinit var connection: TestConnection
        val source = GitHubReleaseSource { url ->
            assertNotSame(caller, Thread.currentThread())
            assertEquals("https://api.github.com/repos/ritikverma098/Forge-Fitness-Tracker/releases/latest", url.toString())
            TestConnection(url, release().toString(), 200).also { connection = it }
        }
        assertTrue(source.check("1.3.2") is UpdateCheckResult.Available)
        assertFalse(connection.instanceFollowRedirects)
        assertTrue(connection.disconnected)
        assertNull(connection.getRequestProperty("Authorization"))
        assertEquals("application/vnd.github+json", connection.getRequestProperty("Accept"))
        assertTrue(connection.connectTimeout > 0 && connection.readTimeout > 0)
    }

    private fun check(json: JSONObject, installed: String = "1.3.2", status: Int = 200) =
        checkBody(json.toString(), installed, status)

    private fun checkBody(body: String, installed: String = "1.3.2", status: Int = 200) = runBlocking {
        GitHubReleaseSource { TestConnection(it, body, status) }.check(installed)
    }

    private fun release(tag: String = "v1.4.0") = JSONObject()
        .put("tag_name", tag)
        .put("name", "Forge update")
        .put("published_at", "2026-11-01T17:05:36Z")
        .put("draft", false)
        .put("prerelease", false)
        .put("assets", JSONArray().put(asset(tag, "Forge-${tag.removePrefix("v")}.apk")))

    private fun asset(tag: String, name: String) = JSONObject()
        .put("name", name).put("state", "uploaded").put("size", 123)
        .put("browser_download_url", "https://github.com/ritikverma098/Forge-Fitness-Tracker/releases/download/$tag/$name")

    private class TestConnection(url: URL, private val body: String, private val status: Int) : HttpURLConnection(url) {
        var disconnected = false
        override fun getResponseCode(): Int = status
        override fun getInputStream() = body.byteInputStream()
        override fun connect() = Unit
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
    }
}
