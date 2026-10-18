package app.morphe.patches.youtube.interaction.savepostimage

import app.morphe.patcher.Patcher
import app.morphe.patcher.PatcherConfig
import app.morphe.patcher.dex.SdkDexVerifier
import app.morphe.patches.youtube.interaction.feedactions.feedVideoActionsPatch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Real-APK gate for the inspected experimental target; skipped when no APK is supplied. */
class SavePostImagePatchTargetTest {
    @Test
    fun `post overflow menu hook applies to YouTube 21 42 241`() {
        val apkPath = System.getProperty("savePostImageApk")
        assumeTrue(!apkPath.isNullOrBlank()) {
            "Pass -PsavePostImageApk=/absolute/path/to/youtube-21.42.241.apk"
        }
        val sdk = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
        require(!sdk.isNullOrBlank()) { "Android SDK is required for DEX verification" }

        val workspace = createTempDirectory("save-post-image-target")
        val completed = mutableSetOf<String>()
        Patcher(
            PatcherConfig(
                File(apkPath).canonicalFile,
                workspace.toFile(),
                verifier = SdkDexVerifier(File(sdk)),
            )
        ).use { patcher ->
            // Keep a common upstream default feed patch in the same patch set.
            patcher += setOf(savePostImagePatch, feedVideoActionsPatch)
            runBlocking {
                patcher().collect { result ->
                    assertNull(result.exception, result.exception?.stackTraceToString())
                    completed += result.patch.name.orEmpty()
                }
            }
            assertTrue("Save community post images" in completed)
            assertTrue("Feed video actions" in completed)
            patcher.get()
        }
    }
}
