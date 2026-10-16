package ajstrick81.morphe.patches.f1tv.uhd

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import ajstrick81.morphe.patches.f1tv.shared.Constants
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod

private const val EGL = "Lcom/tiledmedia/clearvrview/EGLRenderTarget;"
private const val RTC = "Lcom/tiledmedia/clearvrview/RenderTargetConfig;"

@Suppress("unused")
val unlockUhdPatch = bytecodePatch(
    name = "Unlock UHD (4K)",
    description = "Unlocks the 2160p/4K streaming tier for F1 TV on all Android TV hardware by " +
        "removing the client-side device-capability gates and advertising HDR support to the " +
        "F1 backend, while routing each device to the render path its GPU can actually drive. " +
        "Requires a paid F1 TV Premium subscription — this changes no entitlement, only which " +
        "video tier the app's own capability checks allow.",
) {
    compatibleWith(Constants.COMPATIBILITY)

    execute {

        // ─────────────────────────────────────────────────────────────────────
        // LAYER 1 — the device allowlist (the headline restriction)
        //
        // DeviceSupportImpl runs four private validators, each returning
        // Pair<Boolean, String>. Any one failing drops the device off the UHD
        // path. validateIsUhdSupportedDevice is the brand/product whitelist from
        // configPROD.json ("only a few official Google devices"); the other three
        // are a secure-HEVC-decoder probe, a low-RAM check and an API-level floor.
        // Each is stubbed to return Pair(true, null) so every UHD-capability gate
        // passes. (p1 — the DeviceCapabilities arg — is reused as a scratch
        // register for the null; safe since we return immediately.)
        // ─────────────────────────────────────────────────────────────────────
        val truePairStub =
            """
                new-instance v0, Lkotlin/Pair;
                const/4 v1, 0x1
                invoke-static {v1}, Ljava/lang/Boolean;->valueOf(Z)Ljava/lang/Boolean;
                move-result-object v1
                const/4 p1, 0x0
                invoke-direct {v0, v1, p1}, Lkotlin/Pair;-><init>(Ljava/lang/Object;Ljava/lang/Object;)V
                return-object v0
            """
        val validators = setOf(
            "validateIsUhdSupportedDevice",
            "validateTmSdkSupport",
            "validateLowRamDeviceSupport",
            "validateApiLevelSupport",
        )
        mutableClassDefByOrNull { it.type.endsWith("/DeviceSupportImpl;") }
            ?.methods
            ?.filter { it.name in validators && it.returnType == "Lkotlin/Pair;" }
            ?.forEach { it.addInstructions(0, truePairStub) }

        // ─────────────────────────────────────────────────────────────────────
        // LAYER 2 — HDR advertisement (the real gate that unlocks the 2160p tier)
        //
        // F1's 2160p tier only exists inside the HDR (HLG) manifest; SDR is
        // hard-capped server-side at 1620p. The app advertises EGL HDR support to
        // the backend solely through EGLRenderTarget.getIsBt2020HlgExtensionSupported(),
        // so that is forced true. But that forced answer must NOT leak into the
        // render path (it would push devices without a real HLG colorspace — Shield,
        // Amlogic, MediaTek — onto a GL HDR path they can't drive → TM4014 / dark
        // picture / flicker). So we preserve the HONEST probes under new helper
        // methods and let the render path (Layer 3) consult those instead:
        //   uhdRealHlgSupported()      — real EGL BT2020 HLG colorspace probe
        //   uhdRealPqSupported()       — real EGL BT2020 PQ colorspace probe
        //                                (no API-level gate — the stock PQ getter
        //                                 gates on API 33+, wrongly excluding the
        //                                 Shield on Android 11)
        //   uhdSurfaceHlgRenderable()  — can this GL surface show HLG? natively,
        //                                or via the PQ reroute in RenderTargetConfig
        // _getIsExtensionSupported(String) is a private static already on the class,
        // so the helpers call it directly (same class).
        // ─────────────────────────────────────────────────────────────────────
        // The advertise force, the honest-probe helpers it hides behind, and the
        // render-path routing are INTERDEPENDENT. Forcing HDR advertisement true
        // without the matching render routing would push a device onto a GL HDR
        // path its GPU can't drive (TM4014 / dark / flicker); adding the forced
        // method without the helpers would leave RenderTargetConfig / TMViewModel
        // calling methods that don't exist. So resolve EVERY target first and
        // apply the whole set only if ALL are present — otherwise skip the group
        // and fall back to stock behaviour (device keeps the Layer-1 allowlist
        // bypass but is not forced onto an unsuitable 4K path). Hardening added
        // per the Codex review of this patch.
        run {
            val egl = mutableClassDefByOrNull { it.type.endsWith("/EGLRenderTarget;") } ?: return@run
            val forcedAdvertise = egl.methods.firstOrNull {
                it.name == "getIsBt2020HlgExtensionSupported" && it.returnType == "Z"
            } ?: return@run
            // The helpers call this existing private static probe on the same class.
            if (egl.methods.none { it.name == "_getIsExtensionSupported" }) return@run

            val doesDisplay = DoesDisplaySupportFingerprint.methodOrNull ?: return@run

            val rtc = mutableClassDefByOrNull { it.type.endsWith("/RenderTargetConfig;") } ?: return@run
            val requireHlg = rtc.methods.firstOrNull { it.name == "requireHLG" && it.returnType == "Z" }
                ?: return@run
            val require2020 = rtc.methods.firstOrNull { it.name == "require2020PQ" && it.returnType == "Z" }
                ?: return@run
            if (rtc.fields.none { it.name == "_requireHLG" } ||
                rtc.fields.none { it.name == "_require2020PQ" }
            ) {
                return@run
            }

            // Locate the single TMViewModel surface-capability call site up front.
            val tm = mutableClassDefByOrNull { it.type.endsWith("/TMViewModel;") } ?: return@run
            var tmMethod: MutableMethod? = null
            var tmIndex = -1
            for (candidate in tm.methods) {
                val insns = candidate.implementation?.instructions?.toList() ?: continue
                val idx = insns.indexOfFirst { insn ->
                    val ref = (insn as? ReferenceInstruction)?.reference as? MethodReference
                    ref != null &&
                        ref.definingClass == EGL &&
                        ref.name == "getIsBt2020HlgExtensionSupported"
                }
                if (idx >= 0) {
                    tmMethod = candidate
                    tmIndex = idx
                    break
                }
            }
            val tmHit = tmMethod ?: return@run

            // ── All targets present — apply the interdependent set together. ──

            // Preserve the HONEST HLG / PQ probes under new names; the forced
            // advertise method below destroys the original honest answer. PQ is
            // intentionally probed without the stock API-33 gate (which wrongly
            // excludes the Shield on Android 11).
            egl.addStaticBooleanMethod(
                "uhdRealHlgSupported",
                """
                    const-string v0, "EGL_EXT_gl_colorspace_bt2020_hlg"
                    invoke-static {v0}, $EGL->_getIsExtensionSupported(Ljava/lang/String;)Z
                    move-result v0
                    return v0
                """,
            )
            egl.addStaticBooleanMethod(
                "uhdRealPqSupported",
                """
                    const-string v0, "EGL_EXT_gl_colorspace_bt2020_pq"
                    invoke-static {v0}, $EGL->_getIsExtensionSupported(Ljava/lang/String;)Z
                    move-result v0
                    return v0
                """,
            )
            egl.addStaticBooleanMethod(
                "uhdSurfaceHlgRenderable",
                """
                    invoke-static {}, $EGL->uhdRealHlgSupported()Z
                    move-result v0
                    if-eqz v0, :pq
                    const/4 v0, 0x1
                    return v0
                    :pq
                    invoke-static {}, $EGL->uhdRealPqSupported()Z
                    move-result v0
                    return v0
                """,
            )

            // Advertise HDR to the backend so the 2160p (HLG) tier is offered, and
            // stop an HDR10-only / SDR panel from making the core reject the HLG
            // tier upstream (the device pipeline then down-converts HLG).
            forcedAdvertise.addInstructions(
                0,
                """
                    const/4 v0, 0x1
                    return v0
                """,
            )
            doesDisplay.addInstructions(
                0,
                """
                    const/4 v0, 0x1
                    return v0
                """,
            )

            // Render routing — RenderTargetConfig.requireHLG() =
            //   _requireHLG && uhdRealHlgSupported()  (only ask for an HLG EGL
            //   surface when the GPU really has the colorspace).
            requireHlg.addInstructions(
                0,
                """
                    iget-boolean v0, p0, $RTC->_requireHLG:Z
                    if-nez v0, :real
                    const/4 v0, 0x0
                    return v0
                    :real
                    invoke-static {}, $EGL->uhdRealHlgSupported()Z
                    move-result v0
                    return v0
                """,
            )
            // RenderTargetConfig.require2020PQ() =
            //   _require2020PQ || (_requireHLG && !uhdRealHlgSupported())
            //   → reroute HLG through PQ when the GPU lacks the HLG colorspace
            //     (fixes the Shield's dark/SDR fallback).
            require2020.addInstructions(
                0,
                """
                    iget-boolean v0, p0, $RTC->_require2020PQ:Z
                    if-eqz v0, :chkhlg
                    const/4 v0, 0x1
                    return v0
                    :chkhlg
                    iget-boolean v0, p0, $RTC->_requireHLG:Z
                    if-nez v0, :realhlg
                    const/4 v0, 0x0
                    return v0
                    :realhlg
                    invoke-static {}, $EGL->uhdRealHlgSupported()Z
                    move-result v0
                    if-nez v0, :nope
                    const/4 v0, 0x1
                    return v0
                    :nope
                    const/4 v0, 0x0
                    return v0
                """,
            )

            // Report the HONEST surface capability (not the forced advertise) so
            // the core routes GPUs with neither EGL colorspace to its non-GL
            // (direct decoder->view) path, where the TV's own pipeline handles HLG.
            tmHit.replaceInstruction(
                tmIndex,
                "invoke-static {}, $EGL->uhdSurfaceHlgRenderable()Z",
            )
        }

        // DecoderCapability.getAsCoreProtobuf(): devices without a ClearVR quirk
        // profile report 0 secure tile slots/rows/cols, so the backend serves a
        // lower tier (2880x1620). Override the three field reads to non-zero
        // (16 slots, 5 rows, 5 cols — Chromecast/Oculus-class profile) so the full
        // 3840x2160 tile tier is served. Each iget is replaced in place with a
        // const into the SAME destination register.
        mutableClassDefByOrNull { it.type.endsWith("/DecoderCapability;") }?.let { cap ->
            cap.methods.firstOrNull { it.name == "getAsCoreProtobuf" }?.let { m ->
                val tileConst = mapOf(
                    "maxNumberOfSecureHEVCSamples" to "0x10",
                    "maxTileRows" to "0x5",
                    "maxTileColumns" to "0x5",
                )
                val done = HashSet<String>()
                m.implementation?.instructions?.toList()?.forEachIndexed { index, insn ->
                    if (insn.opcode == Opcode.IGET) {
                        val field = (insn as? ReferenceInstruction)?.reference as? FieldReference
                        val value = field?.name?.let { tileConst[it] }
                        if (value != null && done.add(field.name)) {
                            val dest = (insn as TwoRegisterInstruction).registerA
                            m.replaceInstruction(index, "const/16 v$dest, $value")
                        }
                    }
                }
            }
        }

        // DecoderCapability max-resolution hint (adaptive playback): ClearVR
        // configures the decoder at the first tile size with no max, relying on a
        // mid-stream reallocation when the stream ramps to 3840x2176. Tegra copes;
        // MediaTek must re-acquire a 4K secure decoder mid-stream and fails
        // (acquireVdecResource ... not enough -> TM4014 / flicker). Tell MediaCodec
        // up front how large the stream can get (KEY_MAX_WIDTH/HEIGHT) so resources
        // are reserved once. Inserted right after the "height" setInteger in
        // getMediaFormat; p1 is dead there (reassigned) so it is reused as scratch.
        mutableClassDefByOrNull { it.type.endsWith("/VideoDecoderBridgeShared;") }?.let { vdb ->
            vdb.methods.firstOrNull { it.name == "getMediaFormat" }?.let { m ->
                val insns = m.implementation?.instructions?.toList() ?: return@let
                // Find the setInteger(...) invoke that immediately follows the
                // const-string "height".
                var heightStringIndex = -1
                var insertAfter = -1
                insns.forEachIndexed { index, insn ->
                    if (heightStringIndex == -1 &&
                        insn.opcode == Opcode.CONST_STRING &&
                        ((insn as? ReferenceInstruction)?.reference?.toString() == "height")
                    ) {
                        heightStringIndex = index
                    } else if (heightStringIndex != -1 && insertAfter == -1 &&
                        (insn as? ReferenceInstruction)?.let {
                            val ref = it.reference as? MethodReference
                            ref?.name == "setInteger" &&
                                ref.definingClass == "Landroid/media/MediaFormat;"
                        } == true
                    ) {
                        insertAfter = index
                    }
                }
                // insertAfter+1 is the original continuation (first instruction
                // after the "height" setter — in this APK `const/4 p1, 0x3`).
                if (insertAfter != -1 && insertAfter + 1 < insns.size) {
                    val continuation = insns[insertAfter + 1]
                    val cfg = "Lcom/tiledmedia/clearvrdecoder/video/VideoDecoderBridgeShared\$Configuration;"
                    val capType = "Lcom/tiledmedia/clearvrdecoder/util/DecoderCapability;"
                    val resType = "Lcom/tiledmedia/clearvrdecoder/util/Resolution;"
                    // Branch the guards to the ORIGINAL continuation via an external
                    // label (bound to the real instruction object), not an in-block
                    // label — an in-block trailing label binds back to the block's
                    // first instruction here and loops (Codex P1). The external label
                    // tracks the continuation after insertion shifts it down.
                    m.addInstructionsWithLabels(
                        insertAfter + 1,
                        """
                            iget-object v1, p0, $cfg->decoderCapabilities:$capType
                            if-eqz v1, :uhd_maxres_skip
                            invoke-virtual {v1}, $capType->getMaxSupportedAndValidResolution()$resType
                            move-result-object v1
                            if-eqz v1, :uhd_maxres_skip
                            invoke-virtual {v1}, $resType->getWidth()I
                            move-result v2
                            if-lez v2, :uhd_maxres_skip
                            const-string p1, "max-width"
                            invoke-virtual {v0, p1, v2}, Landroid/media/MediaFormat;->setInteger(Ljava/lang/String;I)V
                            invoke-virtual {v1}, $resType->getHeight()I
                            move-result v2
                            const-string p1, "max-height"
                            invoke-virtual {v0, p1, v2}, Landroid/media/MediaFormat;->setInteger(Ljava/lang/String;I)V
                        """,
                        ExternalLabel("uhd_maxres_skip", continuation),
                    )
                }
            }
        }

        // Disable the NVIDIA no-post-process workaround (can select a lower tile tier).
        DeviceNeedsNoPostProcessFingerprint.methodOrNull?.addInstructions(
            0,
            """
                const/4 v0, 0x0
                return v0
            """,
        )

        // Lift the ~1.5x display-size resolution cap: report a 3840x2160 panel
        // (0xf00 x 0x870) and refresh the cached trueDisplaySize.
        GetDefaultDisplaySizeFingerprint.methodOrNull?.addInstructions(
            0,
            """
                new-instance v0, Landroid/graphics/Point;
                const/16 v1, 0xf00
                const/16 v2, 0x870
                invoke-direct {v0, v1, v2}, Landroid/graphics/Point;-><init>(II)V
                sput-object v0, Lcom/tiledmedia/clearvrview/TrueTVDisplaySizeHelper;->trueDisplaySize:Landroid/graphics/Point;
                return-object v0
            """,
        )

        // Expose the in-app quality selector so the user can pick 2160p.
        IsVideoQualityEnabledFingerprint.methodOrNull?.addInstructions(
            0,
            """
                const/4 v0, 0x1
                return v0
            """,
        )
    }
}

// Adds a `public static <name>()Z` method carrying [smali] to this class.
// Register count fixed at 1 (v0) — all helpers compute a boolean in v0.
private fun MutableClass.addStaticBooleanMethod(name: String, smali: String) {
    val method = MutableMethod(
        ImmutableMethod(
            type,
            name,
            emptyList(),
            "Z",
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
            null,
            null,
            MutableMethodImplementation(1),
        ),
    )
    method.addInstructions(0, smali)
    methods.add(method)
}
