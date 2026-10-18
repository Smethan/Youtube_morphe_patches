/*
 * Copyright 2026 Morphe.
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.interaction.savepostimage

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.util.findInstructionIndicesReversedOrThrow
import com.android.tools.smali.dexlib2.Opcode

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/SavePostImagePatch;"

// Keep this experimental patch scoped to the APK version whose binder was inspected.
private val COMPATIBILITY_YOUTUBE_21_42_241 = Compatibility(
    name = "YouTube",
    packageName = "com.google.android.youtube",
    apkFileType = ApkFileType.APK_REQUIRED,
    appIconColor = 0xFF0033,
    signatures = setOf(
        // Android 13+
        "5aad2bee6db95d17e05a08d7d1e64c10a1511879154483916b6ae6c7fd9cb0c6",
        // Android 7+
        "3d7a1223019aa39d9ea0e3436ab7c0896bfb4fb679f4de5fe7c23f326c8f994a",
    ),
    targets = listOf(
        AppTarget(
            version = "21.42.241",
            minSdk = 29,
            isExperimental = true,
        ),
    ),
)

@Suppress("unused")
val savePostImagePatch = bytecodePatch(
    name = "Save community post images",
    description = "Adds image-saving actions to a community post's three-dot menu.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE_21_42_241)

    execute {
        // aefq.rq binds bcty.b (the image model) into its actual backstage
        // ImageView. Keep this mapping fresh as recycled post rows are rebound.
        BackstageImageBindFingerprint.method.addInstructions(
            0,
            """
                move-object/from16 v0, p2
                check-cast v0, Lbcty;
                iget-object v1, v0, Lbcty;->b:Lbnfs;
                move-object/from16 v0, p0
                iget-object v2, v0, ${BackstageImageBindFingerprint.method.definingClass}->f:Landroid/widget/ImageView;
                invoke-static { v2, v1 }, $EXTENSION_CLASS->bindVisiblePostImage(Landroid/widget/ImageView;Ljava/lang/Object;)V
            """,
        )

        BackstageImageUnbindFingerprint.method.apply {
            findInstructionIndicesReversedOrThrow(Opcode.RETURN_VOID).forEach { index ->
                addInstructions(
                    index,
                    """
                        iget-object v0, p0, ${BackstageImageUnbindFingerprint.method.definingClass}->f:Landroid/widget/ImageView;
                        const/4 v1, 0x0
                        invoke-static { v0, v1 }, $EXTENSION_CLASS->bindVisiblePostImage(Landroid/widget/ImageView;Ljava/lang/Object;)V
                    """,
                )
            }
        }

        // auoo.f receives both the post row root and its three-dot anchor.
        // Copy parameters into low locals for a verifier-safe invoke-static.
        PostMenuAnchorBindFingerprint.method.addInstructions(
            0,
            """
                move-object/from16 v0, p1
                move-object/from16 v1, p2
                invoke-static { v0, v1 }, $EXTENSION_CLASS->bindPostMenuAnchor(Landroid/view/View;Landroid/view/View;)V
            """,
        )

        // p2 is the button, p3 is YouTube's menu tag, and p0 holds the native
        // popup. The extension captures post image URLs before the menu opens,
        // then appends its own actions after YouTube has shown the popup. These
        // parameters occupy high registers in this method, so copy them into
        // unused entry locals for the non-range invoke-static instruction.
        PostOverflowMenuFingerprint.method.addInstructions(
            0,
            """
                move-object/from16 v0, p2
                move-object/from16 v1, p0
                move-object/from16 v2, p3
                invoke-static { v0, v1, v2 }, $EXTENSION_CLASS->onPostMenuOpening(Landroid/view/View;Ljava/lang/Object;Ljava/lang/Object;)V
            """,
        )
    }
}
