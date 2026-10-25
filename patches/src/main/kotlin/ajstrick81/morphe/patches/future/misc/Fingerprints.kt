package ajstrick81.morphe.patches.future.misc

import app.morphe.patcher.Fingerprint

// ─────────────────────────────────────────────────────────────────────────────
// Fawesome Movies & TV — com.future.moviesByFawesomeAndroidTV — update-prompt seam.
//
// SplashActivity.checkCurrentAppVersion() reads the Firebase remote-config key
// "versioncode_androidtv_googletv" (the server's "latest" version code) into a
// local int and compares it against a baked-in constant (0xbd = 189 on 19.7):
//
//     v1 = remoteConfig.getLong("versioncode_androidtv_googletv")   // latest code
//     ...
//     if (v1 <= 0)        -> callRootdata()                         // :cond_2 (normal)
//     else if (v1 <= 189) -> callRootdata()                         // :cond_1 (normal)
//     else                -> Utilities.updateAlert(msg, this, Update, Cancel)
//
// When the backend bumps that key above the installed build's baked-in constant,
// the "New version Available" AlertDialog (Utilities.updateAlert) is shown on EVERY
// cold start — exactly the nag reported in issue #257. Note all three branches
// ultimately call the private SplashActivity.callRootdata() (the Cancel button's
// own handler, access$200, just calls callRootdata() too): the dialog is a pure
// interstitial, not a hard gate. The class/method names are NOT R8-obfuscated in
// this build (com/future/* is preserved), so we anchor on the stable method name.
// ─────────────────────────────────────────────────────────────────────────────

// Lcom/future/moviesByFawesomeAndroidTV/SplashActivity;->checkCurrentAppVersion()V
object CheckCurrentAppVersionFingerprint : Fingerprint(
    returnType = "V",
    parameters = emptyList(),
    custom = { method, classDef ->
        method.name == "checkCurrentAppVersion" &&
            classDef.type.endsWith("/SplashActivity;")
    },
)
