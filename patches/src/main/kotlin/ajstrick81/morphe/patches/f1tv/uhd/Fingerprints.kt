package ajstrick81.morphe.patches.f1tv.uhd

import app.morphe.patcher.Fingerprint

// ─────────────────────────────────────────────────────────────────────────────
// Fingerprints for F1 TV Android TV (com.formulaone.production) 3.0.49.4
//
// 4K/UHD is gated entirely CLIENT-SIDE: the app probes local capabilities and
// reports the result to F1's backend (via the x-f1-device-info header and the
// Tiledmedia ClearVR SDK capability protobuf), and the backend then decides
// which manifest to serve — SDR is hard-capped at 1620p; the 2160p tier only
// lives in the HDR (HLG) manifest. Every target below is verified in baksmali
// of the shipped dex set for this exact version (signatures matched 1:1 with
// the reference project Alexvbp/f1tv-4k-patch, which targets F1 TV 3.0.49.x).
//
// Class/method names in the com.tiledmedia.* (ClearVR SDK) and
// com.avs.f1.* (F1 TV app) packages are NOT R8-obfuscated in this build, so we
// match on the real names. Each fingerprint pins the enclosing class by suffix
// (endsWith) so a package move across SDK bumps degrades gracefully; the patch
// itself uses methodOrNull / null-safe class lookups so any single drift skips
// that one hook instead of aborting the whole patch (repo rule, learned on
// Disney 26.16 / Prime v16).
// ─────────────────────────────────────────────────────────────────────────────

// com/avs/f1/repository/DiagnosticsPreferenceManagerImpl->isVideoQualityEnabled()Z
// Reads SharedPreferences("key.diagnostics.videoQualityEnable"). Forcing true
// exposes the in-app quality selector so the user can pick 2160p. (The other
// diagnostics overlays draw on-screen debug text and are intentionally left off.)
internal object IsVideoQualityEnabledFingerprint : Fingerprint(
    returnType = "Z",
    custom = { method, classDef ->
        method.name == "isVideoQualityEnabled" &&
            classDef.type.endsWith("/DiagnosticsPreferenceManagerImpl;")
    },
)

// com/tiledmedia/clearvrparameters/DeviceParameters->doesDisplaySupport(Context, int)Z
// Reads Display.getHdrCapabilities().getSupportedHdrTypes(). The core only serves
// an HDR tier whose type is in BOTH the EGL-supported AND the display-supported
// sets; an HDR10-only panel (type 2) makes it reject F1's HLG (type 3) upstream
// and fall back to the SDR 1620p tile. Forcing true lets the 2160p HLG tiles be
// served; the device's video pipeline then converts HLG to whatever the panel
// accepts (same path YouTube HLG uses on an HDR10 TV).
internal object DoesDisplaySupportFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf("Landroid/content/Context;", "I"),
    custom = { method, classDef ->
        method.name == "doesDisplaySupport" &&
            classDef.type.endsWith("/DeviceParameters;")
    },
)

// com/tiledmedia/clearvrdecoder/util/Quirks->deviceNeedsNoPostProcessWorkaround()Z
// On NVIDIA devices this sets "no-post-process"=1 on the decoder MediaFormat,
// which can make ClearVR select a lower-quality tile tier. Forcing false removes
// that quirk (no-op on non-NVIDIA devices — the stock body only returns true for
// manufacturer == "NVIDIA").
internal object DeviceNeedsNoPostProcessFingerprint : Fingerprint(
    returnType = "Z",
    custom = { method, classDef ->
        method.name == "deviceNeedsNoPostProcessWorkaround" &&
            classDef.type.endsWith("/Quirks;")
    },
)

// com/tiledmedia/clearvrview/TrueTVDisplaySizeHelper->getDefaultDisplaySize(Activity)Landroid/graphics/Point;
// Tiledmedia caps streaming resolution at ~1.5x the display size it detects; a
// 1080p UI surface pins tiles at 2880x1620 ("can only select up to 2880x1620").
// Forcing a 3840x2160 panel lifts the cap so full 2160p is allowed.
internal object GetDefaultDisplaySizeFingerprint : Fingerprint(
    returnType = "Landroid/graphics/Point;",
    parameters = listOf("Landroid/app/Activity;"),
    custom = { method, classDef ->
        method.name == "getDefaultDisplaySize" &&
            classDef.type.endsWith("/TrueTVDisplaySizeHelper;")
    },
)

// com/tiledmedia/clearvrview/EGLRenderTarget->getIsBt2020HlgExtensionSupported()Z
// THE key advertise gate. ClearVR reports EGL HDR support to F1's backend here;
// it is forced true so the 2160p (HDR) tier is offered. Most Android TV GPUs
// (incl. NVIDIA Shield and Amlogic/MediaTek boxes) don't expose the EGL BT2020
// HLG colorspace, so unpatched the backend withholds EVERY 2160p tier. The
// honest answer is preserved under new helper methods (see UnlockUhdPatch) so
// the render path can still route each device correctly.
internal object ForcedHlgAdvertiseFingerprint : Fingerprint(
    returnType = "Z",
    custom = { method, classDef ->
        method.name == "getIsBt2020HlgExtensionSupported" &&
            classDef.type.endsWith("/EGLRenderTarget;")
    },
)
