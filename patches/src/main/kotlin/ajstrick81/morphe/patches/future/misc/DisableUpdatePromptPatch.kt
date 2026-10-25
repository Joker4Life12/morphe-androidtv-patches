package ajstrick81.morphe.patches.future.misc

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import ajstrick81.morphe.patches.future.shared.Constants
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

@Suppress("unused")
val disableUpdatePromptPatch = bytecodePatch(
    name = "Disable update prompt",
    description = "Stops Fawesome Movies & TV (Android TV) from showing the \"New version " +
        "Available\" dialog on every launch. SplashActivity.checkCurrentAppVersion() compares " +
        "the server's latest version code (Firebase remote-config key " +
        "\"versioncode_androidtv_googletv\") against a baked-in constant and, when the server " +
        "value is higher, calls Utilities.updateAlert(). This patch makes that branch skip the " +
        "dialog and go straight into the app via the activity's own callRootdata() — the exact " +
        "thing the dialog's Cancel button already does — so startup is unchanged and the nag is " +
        "gone. The remote-config reads that run before the branch (which populate GlobalObject) " +
        "are preserved. Nothing is added; the client just stops being told to update.",
) {
    compatibleWith(Constants.COMPATIBILITY)

    execute {
        // Anchor on the single update-dialog call site inside checkCurrentAppVersion()
        // and inject the app's own "continue into the app" sequence immediately before
        // it, then return — so Utilities.updateAlert() is never reached. callRootdata()
        // is a private instance method of SplashActivity (p0 == this), matching the
        // Cancel handler's access$200 -> callRootdata() flow exactly.
        //
        // methodOrNull so a future re-anchor miss degrades gracefully (startup keeps the
        // stock nag) instead of aborting the whole patch run.
        CheckCurrentAppVersionFingerprint.methodOrNull?.apply {
            val updateAlertIndex = implementation!!.instructions.toList().indexOfFirst {
                it.opcode == Opcode.INVOKE_STATIC &&
                    (it as? ReferenceInstruction)?.reference?.toString()
                        ?.let { ref ->
                            ref.startsWith("Lcom/future/util/Utilities;->updateAlert(")
                        } == true
            }
            check(updateAlertIndex >= 0) {
                "disableUpdatePromptPatch: no Utilities.updateAlert call in checkCurrentAppVersion()"
            }

            addInstructions(
                updateAlertIndex,
                """
                    invoke-direct {p0}, Lcom/future/moviesByFawesomeAndroidTV/SplashActivity;->callRootdata()V
                    return-void
                """,
            )
        }
    }
}
