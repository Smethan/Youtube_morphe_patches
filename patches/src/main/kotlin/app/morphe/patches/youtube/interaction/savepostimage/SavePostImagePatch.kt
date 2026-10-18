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
    description = "Adds a long-press action to save community post images.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE_21_42_241)

    execute {
        val method = BackstageImageBindFingerprint.method

        // `rq(aufh, Object)` receives a bcty image model in p2. The renderer stores its
        // primary image view in `f`; the secondary badge is `h`. Use local temporaries at
        // method entry, before the original body assigns its own locals, and invoke on every
        // bind so the extension can clear stale state when a recycled item has no image.
        method.addInstructions(
            0,
            """
                iget-object v0, p0, ${method.definingClass}->f:Landroid/widget/ImageView;
                check-cast p2, Lbcty;
                iget-object v1, p2, Lbcty;->b:Lbnfs;
                invoke-static { v0, v1 }, $EXTENSION_CLASS->bindImage(Landroid/widget/ImageView;Ljava/lang/Object;)V
            """,
        )

        // The renderer's unbind runs when its host recycles the image view without another
        // rq() call. Clear the listener after the original image cleanup, on every unbind.
        BackstageImageUnbindFingerprint.method.apply {
            findInstructionIndicesReversedOrThrow(Opcode.RETURN_VOID).forEach { index ->
                addInstructions(
                    index,
                    """
                        iget-object v0, p0, ${BackstageImageUnbindFingerprint.method.definingClass}->f:Landroid/widget/ImageView;
                        const/4 p1, 0x0
                        invoke-static { v0, p1 }, $EXTENSION_CLASS->bindImage(Landroid/widget/ImageView;Ljava/lang/Object;)V
                    """,
                )
            }
        }

        // Route host touches to the extension, which only installs long-click handling on
        // the existing clickable ancestor when ACTION_DOWN belongs to a currently bound image.
        SavePostImageComponentHostTouchFingerprint.method.addInstructions(
            0,
            "invoke-static { p0, p1 }, $EXTENSION_CLASS->" +
                    "onComponentHostTouch(Lcom/facebook/litho/ComponentHost;Landroid/view/MotionEvent;)V",
        )
    }
}
