package com.forge.workout.backup

import android.app.Application
import android.content.pm.ApplicationInfo
import android.net.Uri
import com.forge.workout.R
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

/** OS backup must not export a whole catalog database; portable documents remain supported. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PlatformBackupPolicyHostTest {
    private val application get() = RuntimeEnvironment.getApplication() as Application

    @Test fun `installed manifest opts out of automatic platform backup`() {
        assertFalse(application.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP != 0)
    }

    @Test fun `legacy backup rules exclude every persistent storage domain`() {
        assertExcluded(R.xml.backup_rules, "full-backup-content")
    }

    @Test fun `cloud extraction rules exclude every persistent storage domain`() {
        assertExcluded(R.xml.data_extraction_rules, "cloud-backup")
    }

    @Test fun `device transfer rules also exclude every persistent storage domain`() {
        assertExcluded(R.xml.data_extraction_rules, "device-transfer")
    }

    @Test fun `portable local backup still writes and reads complete records`() {
        val file = File(application.cacheDir, "platform-policy-${UUID.randomUUID()}.json")
        try {
            val backup = completeBackup()
            val documents = LocalBackupDocuments(application)
            documents.write(Uri.fromFile(file), BackupCodec.encode(backup))
            assertTrue(file.length() > 0)
            assertEquals(backup, BackupCodec.decode(documents.read(Uri.fromFile(file))))
        } finally {
            file.delete()
        }
    }

    private fun assertExcluded(resource: Int, section: String) {
        val exclusions = mutableSetOf<Pair<String, String>>()
        var foundSection = false
        var sectionDepth: Int? = null
        application.resources.getXml(resource).use { parser ->
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == section) {
                    foundSection = true
                    sectionDepth = parser.depth
                } else if (sectionDepth != null && parser.eventType == XmlPullParser.START_TAG) {
                    assertFalse("$section must not include automatic backup data", parser.name == "include")
                    if (parser.name == "exclude") {
                        exclusions += parser.getAttributeValue(null, "domain") to parser.getAttributeValue(null, "path")
                    }
                } else if (parser.eventType == XmlPullParser.END_TAG && parser.depth == sectionDepth) {
                    sectionDepth = null
                }
            }
        }
        assertTrue("Missing $section rules", foundSection)
        assertEquals("$section must exclude all current persistent locations", DOMAINS.map { it to "." }.toSet(), exclusions)
    }

    private companion object {
        val DOMAINS = setOf("root", "file", "database", "sharedpref", "external", "device_root",
            "device_file", "device_database", "device_sharedpref")
    }
}
