/*
 * Copyright 2026 Morphe.
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.interaction.savepostimage

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.checkCast
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.resource.ResourceType
import app.morphe.patcher.resourceLiteral
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

/** Identifies the backstage image renderer by the layout and image view it inflates. */
private object BackstageImageLayoutFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    filters = listOf(
        resourceLiteral(ResourceType.LAYOUT, "backstage_image"),
        methodCall(definingClass = "Landroid/view/View;", name = "inflate"),
        resourceLiteral(ResourceType.ID, "image_view"),
        methodCall(definingClass = "Landroid/view/View;", name = "findViewById"),
    ),
)

/** Binds a backstage post image model to the actual rendered ImageView. */
internal object BackstageImageBindFingerprint : Fingerprint(
    classFingerprint = BackstageImageLayoutFingerprint,
    name = "rq",
    returnType = "V",
    parameters = listOf("Laufh;", "Ljava/lang/Object;"),
    filters = listOf(
        checkCast("Lbcty;"),
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            type = "Lbnfs;",
        ),
        methodCall(definingClass = "Lawda;", name = "aA"),
        methodCall(definingClass = "Lawda;", name = "aw"),
        methodCall(definingClass = "Lauac;", name = "i"),
    ),
)

/** Clears the image mapping when YouTube recycles a backstage image renderer. */
internal object BackstageImageUnbindFingerprint : Fingerprint(
    classFingerprint = BackstageImageLayoutFingerprint,
    name = "hB",
    returnType = "V",
    parameters = listOf("Laufq;"),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            type = "Landroid/widget/ImageView;",
        ),
        methodCall(
            definingClass = "Landroid/widget/ImageView;",
            name = "removeOnLayoutChangeListener",
        ),
        methodCall(definingClass = "Lauac;", name = "f"),
    ),
)

/** Binds the containing post row and its three-dot anchor for later menu actions. */
internal object PostMenuAnchorBindFingerprint : Fingerprint(
    definingClass = "Lauoo;",
    name = "f",
    returnType = "V",
    parameters = listOf(
        "Landroid/view/View;",
        "Landroid/view/View;",
        "Lbjak;",
        "Ljava/lang/Object;",
        "Lanei;",
    ),
)

/**
 * YouTube's innertube overflow controller. Its `h` method builds and shows the
 * three-dot menu for a post row as a ListPopupWindow on the usual phone path.
 */
internal object PostOverflowMenuFingerprint : Fingerprint(
    definingClass = "Lauoo;",
    name = "h",
    returnType = "V",
    parameters = listOf("Lbjak;", "Landroid/view/View;", "Ljava/lang/Object;", "Lanei;"),
    filters = listOf(
        methodCall(definingClass = "Landroid/widget/ListPopupWindow;", name = "show"),
    ),
)
