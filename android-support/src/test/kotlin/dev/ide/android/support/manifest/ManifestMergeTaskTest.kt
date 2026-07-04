package dev.ide.android.support.manifest

import dev.ide.android.support.tasks.ManifestMergeTask
import dev.ide.android.support.tasks.StartupKeepRules
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The edge-to-edge advisory ([ManifestMergeTask.edgeToEdgeAdvisory]): warn when an app relies on the build
 * config for a target that enforces edge-to-edge (Android 15+) but declares no `targetSdkVersion` of its own.
 */
class ManifestMergeTaskTest {

    private val noSdkXml = """<manifest package="com.example.app"><application/></manifest>"""
    private val withTargetXml = """<manifest package="com.example.app"><uses-sdk android:targetSdkVersion="35"/></manifest>"""

    @Test
    fun warnsWhenFacetTargetForcesEdgeToEdgeAndManifestOmitsTarget() {
        val advisory = ManifestMergeTask.edgeToEdgeAdvisory(ManifestMergeTask.EDGE_TO_EDGE_SDK, noSdkXml)
        assertNotNull(advisory)
        assertTrue("edge-to-edge" in advisory)
        assertTrue("35" in advisory)
    }

    @Test
    fun warnsForAnyTargetAtOrAboveTheThreshold() {
        assertNotNull(ManifestMergeTask.edgeToEdgeAdvisory(36, noSdkXml))
    }

    @Test
    fun silentBelowTheThreshold() {
        assertNull(ManifestMergeTask.edgeToEdgeAdvisory(34, noSdkXml))
    }

    @Test
    fun silentWhenTheAppDeclaresItsOwnTargetSdkVersion() {
        // The app owns its target explicitly, so the value is visible in the manifest and not a surprise.
        assertNull(ManifestMergeTask.edgeToEdgeAdvisory(ManifestMergeTask.EDGE_TO_EDGE_SDK, withTargetXml))
    }

    @Test
    fun startupMetadataProducesR8KeepRules() {
        val dir = Files.createTempDirectory("startup-keep-rules")
        try {
            val manifest = dir.resolve("AndroidManifest.xml")
            Files.writeString(
                manifest,
                """
                <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.app">
                    <application>
                        <provider android:name="androidx.startup.InitializationProvider"
                            android:authorities="com.example.app.androidx-startup"
                            android:exported="false">
                            <meta-data android:name="androidx.emoji2.text.EmojiCompatInitializer"
                                android:value="androidx.startup"/>
                            <meta-data android:name="com.example.NotStartup"
                                android:value="ignored"/>
                        </provider>
                    </application>
                </manifest>
                """.trimIndent(),
            )

            assertEquals(
                listOf("-keep class androidx.emoji2.text.EmojiCompatInitializer { *; }"),
                StartupKeepRules.fromManifest(manifest),
            )
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
