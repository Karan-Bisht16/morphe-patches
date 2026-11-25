/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3516
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.mediaviewer

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.findFreeRegister
import app.morphe.patcher.util.proxy.mutableTypes.MutableField.Companion.toMutable
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.util.registersUsed
import app.morphe.patcher.util.returnEarly
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.misc.version.is_2026_24_0_or_greater
import app.morphe.patches.reddit.misc.version.is_2026_32_0_or_greater
import app.morphe.patches.reddit.misc.version.is_2026_38_0_or_greater
import app.morphe.patches.reddit.misc.version.versionCheckPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findInstructionIndicesReversedOrThrow
import app.morphe.util.removeFlags
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21t
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import java.util.logging.Logger

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/HideMediaViewerComponentsPatch;"

@Suppress("unused")
val hideMediaViewerComponentsPatch = patch(
    name = "Hide media viewer components",
    description = "Adds options to hide the \"See the conversation\" button and the title, buttons and video controls " +
            "of the media viewer. Hiding the title, buttons and video controls requires Reddit 2026.38.0 or newer."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch, versionCheckPatch)

    execute {
        if (!is_2026_24_0_or_greater) {
            return@execute Logger.getLogger(this::class.java.name).warning(
                "'Hide media viewer components' requires Reddit 2026.24.0+"
            )
        }

        if (is_2026_32_0_or_greater) {
            // Don't show the dock. Its button is then not drawn, and the media is not padded for it,
            // like when the comments sheet is open.
            JoinConversationDockFingerprint.let {
                it.method.apply {
                    val index = it.instructionMatches[2].index
                    val register = getInstruction<OneRegisterInstruction>(index).registerA
                    addInstructions(
                        index,
                        """
                            invoke-static/range { v$register .. v$register }, $EXTENSION_CLASS->showJoinConversationButton(Z)Z
                            move-result v$register
                        """
                    )
                }
            }

            // Without the dock, the media is centered between the top of the screen and the
            // navigation bar, so it is too high. Also pad images and videos for the status bar,
            // so the media is centered between the system bars.
            MediaViewerPageFingerprint.let {
                it.method.apply {
                    // Images and videos skip the padding.
                    val skipIndex = it.instructionMatches[3].index
                    val skipInstruction = getInstruction<BuilderInstruction21t>(skipIndex)
                    val register = skipInstruction.registerA
                    val skipTarget = skipInstruction.target.location.instruction!!

                    // Replace the branch, so branches to it still run the check.
                    replaceInstruction(
                        skipIndex,
                        "invoke-static/range { v$register .. v$register }, $EXTENSION_CLASS->skipStatusBarPadding(Z)Z"
                    )
                    addInstructionsWithLabels(
                        skipIndex + 1,
                        """
                            move-result v$register
                            if-nez v$register, :skip_padding
                        """,
                        ExternalLabel("skip_padding", skipTarget)
                    )
                }
            }
        } else {
            // Older versions show the button below the video controls, without a dock.
            JoinConversationButtonLegacyFingerprint.let {
                it.method.apply {
                    val index = it.instructionMatches[1].index
                    val register = getInstruction<OneRegisterInstruction>(index).registerA
                    addInstructions(
                        index,
                        """
                            invoke-static/range { v$register .. v$register }, $EXTENSION_CLASS->showJoinConversationButton(Z)Z
                            move-result v$register
                        """
                    )
                }
            }
        }

        // Older versions hide the overlay from the video player instead of the chrome state.
        if (!is_2026_38_0_or_greater) {
            Logger.getLogger(this::class.java.name).warning(
                "Hiding the media viewer overlay requires Reddit 2026.38.0+"
            )
        } else {
            // The chrome state 'isVisible' field is toggled when tapping the media.
            val visibleField = FullBleedChromeStateToStringFingerprint.instructionMatches.last()
                .getFieldAccessed().apply {
                    // The field is final, and is written from another class.
                    removeFlags(AccessFlags.FINAL)
                }

            // Hide the overlay of each newly created chrome state.
            CreateFullBleedChromeStateFingerprint.method.apply {
                findInstructionIndicesReversedOrThrow(Opcode.RETURN_OBJECT).forEach { index ->
                    val stateRegister = getInstruction<OneRegisterInstruction>(index).registerA
                    val freeRegister = findFreeRegister(index, stateRegister)
                    addInstructionsWithLabels(
                        index,
                        """
                            invoke-static { }, $EXTENSION_CLASS->hideOverlay()Z
                            move-result v$freeRegister
                            if-eqz v$freeRegister, :show_overlay
                            const/4 v$freeRegister, 0x0
                            iput-boolean v$freeRegister, v$stateRegister, $visibleField
                            :show_overlay
                            nop
                        """
                    )
                }
            }

            Fingerprint(
                definingClass = EXTENSION_CLASS,
                name = "isHideOverlayIncluded"
            ).method.returnEarly(true)

            interceptImmersiveMediaViewerEvents()
            customizeImmersiveMediaViewerIcons()
        }

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}

private const val ICON_ENUM_TYPE = "Lcom/reddit/fullbleedplayer/composables/IconType;"

private fun interceptImmersiveMediaViewerEvents() {
    val onEventMethod = CompositionViewModelOnEventFingerprint.method
    val mediaPageType = ToggleSaveCtorFingerprint.originalMethod.parameterTypes[0].toString()
    val getCurrentMediaPageMethod = FullBleedViewModelGetCurrentMediaPageFingerprint.method
    val isSavedMethod = OverflowMenuIsSavedFingerprint.instructionMatches[0].methodReference
    val viewModelClassDef = FullBleedViewModelGetCurrentMediaPageFingerprint.classDef
    val viewModelType = viewModelClassDef.type

    val repostEventType = OnClickRepostCtorFingerprint.originalClassDef.type
    val shareEventType = OnClickShareCtorFingerprint.originalClassDef.type
    val downloadEventType = DownloadPermCtorFingerprint.originalClassDef.type
    val saveEventType = ToggleSaveCtorFingerprint.originalClassDef.type

    val helperName = "patch_interceptEvent"
    viewModelClassDef.methods.add(
        ImmutableMethod(
            viewModelType,
            helperName,
            listOf(
                ImmutableMethodParameter("Ljava/lang/Object;", null, null),
                ImmutableMethodParameter("Ljava/lang/Object;", null, null)
            ),
            "Ljava/lang/Object;",
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
            null,
            null,
            MutableMethodImplementation(4) // v0, v1, p0 (vm), p1 (event)
        ).toMutable().apply {
            addInstructionsWithLabels(
                0,
                """
                    invoke-static { }, $EXTENSION_CLASS->isImmersiveModeEnabled()Z
                    move-result v0
                    if-eqz v0, :original

                    instance-of v0, p0, $viewModelType
                    if-eqz v0, :original
                    check-cast p0, $viewModelType

                    instance-of v0, p1, $repostEventType
                    if-eqz v0, :check_share
                    invoke-virtual { p0 }, $getCurrentMediaPageMethod
                    move-result-object v0
                    if-eqz v0, :original
                    new-instance v1, $downloadEventType
                    invoke-direct { v1, v0 }, $downloadEventType-><init>($mediaPageType)V
                    return-object v1

                    :check_share
                    instance-of v0, p1, $shareEventType
                    if-eqz v0, :sync_saved
                    invoke-virtual { p0 }, $getCurrentMediaPageMethod
                    move-result-object v0
                    if-eqz v0, :original
                    invoke-virtual { v0 }, $isSavedMethod
                    move-result v1
                    xor-int/lit8 v1, v1, 0x1
                    invoke-static { v1 }, $ICON_ENUM_TYPE->patch_setSaved(Z)V
                    new-instance v1, $saveEventType
                    invoke-direct { v1, v0 }, $saveEventType-><init>($mediaPageType)V
                    return-object v1

                    :sync_saved
                    invoke-virtual { p0 }, $getCurrentMediaPageMethod
                    move-result-object v0
                    if-eqz v0, :original
                    invoke-virtual { v0 }, $isSavedMethod
                    move-result v1
                    invoke-static { v1 }, $ICON_ENUM_TYPE->patch_setSaved(Z)V

                    :original
                    return-object p1
                """
            )
        }
    )

    onEventMethod.addInstructions(
        0,
        """
            invoke-static { p0, p1 }, $viewModelType->$helperName(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
            move-result-object p1
        """
    )
}

private fun customizeImmersiveMediaViewerIcons() {
    val iconTypeClass = IconTypeDownloadToIconFingerprint.originalMethod.returnType
    val iconEnumClassDef = mutableClassDefBy(ICON_ENUM_TYPE)

    fun getIconFields(fp: Fingerprint): List<FieldReference> =
        fp.originalMethod.implementation!!.instructions
            .filter { it.opcode == Opcode.SGET_OBJECT }
            .map { (it as ReferenceInstruction).reference as FieldReference }
            .filter { it.type == iconTypeClass }

    // Unsave only references the filled icon (uqe.*); outlined icons live on the other class (h0f.*).
    val saveFilled = getIconFields(IconTypeUnsaveToIconFingerprint).single()
    val filledIconClass = saveFilled.definingClass
    val saveOutlined = getIconFields(IconTypeSaveToIconFingerprint).first { it.definingClass != filledIconClass }
    val downloadOutlined = getIconFields(IconTypeDownloadToIconFingerprint).first { it.definingClass != filledIconClass }

    // Resolve Compose MutableState factory (g.j(Object)) and whether its return type is an interface or class.
    val createStateMethod = CompositionViewModelInitStateFingerprint.instructionMatches[1].methodReference
    val stateType = createStateMethod.returnType
    val stateInvokeOp = if (AccessFlags.INTERFACE.isSet(mutableClassDefBy(stateType).accessFlags)) {
        "invoke-interface"
    } else {
        "invoke-virtual"
    }

    // Store Compose MutableState<Boolean> on IconType so reading it inside t3c triggers recomposition.
    iconEnumClassDef.fields.add(
        ImmutableField(
            ICON_ENUM_TYPE,
            "patch_savedState",
            stateType,
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
            null,
            null,
            null
        ).toMutable()
    )

    iconEnumClassDef.methods.add(
        ImmutableMethod(
            ICON_ENUM_TYPE,
            "patch_getSavedState",
            emptyList(),
            stateType,
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
            null,
            null,
            MutableMethodImplementation(1)
        ).toMutable().apply {
            addInstructionsWithLabels(
                0,
                """
                    sget-object v0, $ICON_ENUM_TYPE->patch_savedState:$stateType
                    if-nez v0, :done
                    sget-object v0, Ljava/lang/Boolean;->FALSE:Ljava/lang/Boolean;
                    invoke-static { v0 }, $createStateMethod
                    move-result-object v0
                    sput-object v0, $ICON_ENUM_TYPE->patch_savedState:$stateType
                    :done
                    return-object v0
                """
            )
        }
    )

    iconEnumClassDef.methods.add(
        ImmutableMethod(
            ICON_ENUM_TYPE,
            "patch_setSaved",
            listOf(ImmutableMethodParameter("Z", null, null)),
            "V",
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
            null,
            null,
            MutableMethodImplementation(3)
        ).toMutable().apply {
            addInstructions(
                0,
                """
                    invoke-static { }, $ICON_ENUM_TYPE->patch_getSavedState()$stateType
                    move-result-object v0
                    invoke-static { p0 }, Ljava/lang/Boolean;->valueOf(Z)Ljava/lang/Boolean;
                    move-result-object v1
                    $stateInvokeOp { v0, v1 }, $stateType->setValue(Ljava/lang/Object;)V
                    return-void
                """
            )
        }
    )

    iconEnumClassDef.methods.add(
        ImmutableMethod(
            ICON_ENUM_TYPE,
            "patch_getDownloadIcon",
            listOf(ImmutableMethodParameter(iconTypeClass, null, null)),
            iconTypeClass,
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
            null,
            null,
            MutableMethodImplementation(2)
        ).toMutable().apply {
            addInstructionsWithLabels(
                0,
                """
                    invoke-static { }, $EXTENSION_CLASS->isImmersiveModeEnabled()Z
                    move-result v0
                    if-eqz v0, :original
                    sget-object v0, $downloadOutlined
                    return-object v0
                    :original
                    return-object p0
                """
            )
        }
    )

    iconEnumClassDef.methods.add(
        ImmutableMethod(
            ICON_ENUM_TYPE,
            "patch_getSaveIcon",
            listOf(ImmutableMethodParameter(iconTypeClass, null, null)),
            iconTypeClass,
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
            null,
            null,
            MutableMethodImplementation(2)
        ).toMutable().apply {
            addInstructionsWithLabels(
                0,
                """
                    invoke-static { }, $EXTENSION_CLASS->isImmersiveModeEnabled()Z
                    move-result v0
                    if-eqz v0, :original
                    invoke-static { }, $ICON_ENUM_TYPE->patch_getSavedState()$stateType
                    move-result-object v0
                    $stateInvokeOp { v0 }, $stateType->getValue()Ljava/lang/Object;
                    move-result-object v0
                    check-cast v0, Ljava/lang/Boolean;
                    invoke-virtual { v0 }, Ljava/lang/Boolean;->booleanValue()Z
                    move-result v0
                    if-eqz v0, :unsaved
                    sget-object v0, $saveFilled
                    return-object v0
                    :unsaved
                    sget-object v0, $saveOutlined
                    return-object v0
                    :original
                    return-object p0
                """
            )
        }
    )

    // 1. Swap Repost -> Download and Share -> Save/Unsave in t3c.invoke.
    MediaViewerActionIconsFingerprint.method.apply {
        val instructions = implementation!!.instructions

        fun findNextIconCall(startIndex: Int): Int {
            for (i in startIndex until instructions.size) {
                val insn = instructions[i]
                if (insn.opcode == Opcode.INVOKE_STATIC || insn.opcode == Opcode.INVOKE_STATIC_RANGE) {
                    val ref = (insn as ReferenceInstruction).reference as MethodReference
                    if (ref.returnType == "V" && ref.parameterTypes.size == 8) {
                        return i
                    }
                }
            }
            error("Could not find icon draw call after index $startIndex")
        }

        val repostAnchor = instructions.indexOfFirst {
            it is WideLiteralInstruction && it.wideLiteral == -1936429019L
        }
        val shareAnchor = instructions.indexOfFirst {
            it is ReferenceInstruction && (it.reference as? StringReference)?.string == "share_icon"
        }

        val repostCallIndex = findNextIconCall(repostAnchor)
        val shareCallIndex = findNextIconCall(shareAnchor)

        // Modify bottom-to-top so indices stay valid regardless of bytecode order.
        listOf(
            repostCallIndex to "patch_getDownloadIcon",
            shareCallIndex to "patch_getSaveIcon"
        ).sortedByDescending { it.first }.forEach { (callIndex, helper) ->
            val iconReg = getInstruction(callIndex).registersUsed[0]
            addInstructionsAtControlFlowLabel(
                callIndex,
                """
                    invoke-static/range { v$iconReg .. v$iconReg }, $ICON_ENUM_TYPE->$helper($iconTypeClass)$iconTypeClass
                    move-result-object v$iconReg
                """
            )
        }
    }

    // 2. Hide the 0 / 1 counters under Repost and Share in agn0.c.
    MediaViewerActionButtonFingerprint.method.addInstructionsWithLabels(
        0,
        """
            invoke-static/range { p3 .. p3 }, $EXTENSION_CLASS->shouldHideCounter(Ljava/lang/String;)Z
            move-result v0
            if-eqz v0, :keep_counter
            const/4 v0, 0x0
            move-object/from16 p5, v0
            move-object/from16 p6, v0
            :keep_counter
            nop
        """
    )
}