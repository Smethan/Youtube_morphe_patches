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

/**
 * Binds a community post image model to the ImageView from [BackstageImageLayoutFingerprint].
 * The `bcty` cast and image-model field, plus both image-loading paths, distinguish this from
 * other `aufj.rq` binders.
 */
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

/** Unbinds and recycles the same backstage image view after its post item leaves the host. */
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

/** Receives host touches so the extension can route long presses from a bound image safely. */
internal object SavePostImageComponentHostTouchFingerprint : Fingerprint(
    definingClass = "Lcom/facebook/litho/ComponentHost;",
    name = "dispatchTouchEvent",
    returnType = "Z",
    parameters = listOf("Landroid/view/MotionEvent;"),
)
