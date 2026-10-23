package ajstrick81.morphe.patches.netflix.shared

import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

// Netflix Android TV (com.netflix.ninja). Target verified from a re-pulled
// base.apk (androguard, 2026-08-07): versionName "13.0.1 build 25028",
// versionCode 25028, ABI armeabi-v7a, requiredSplitTypes base__abi,
// extractNativeLibs="false". Application subclass Lcom/netflix/ninja/
// NetflixApplication; and its onCreate()V (public, .registers 11) confirmed
// against real bytecode — index-0 injection of loadLibrary is register-safe.
//
// NOTE: the PORTABILITY-ASSESSMENT.md §3 architecture (Hermes/appboot/milo,
// MSL, appboot signature) was measured on 13.0.0-25009; Netflix has since
// bumped to 25028. The Application anchor is unchanged; re-verify the native
// findings if/when libnetflix.so from this build is analyzed.
object Constants {
    val COMPATIBILITY = Compatibility(
        name = "Netflix Android TV",
        packageName = "com.netflix.ninja",
        appIconColor = 0xE50914,
        // Must equal the APK's ACTUAL versionName so Morphe Manager recognizes
        // the download as Recommended (not "Unsupported — proceed anyway"). The
        // armeabi-v7a build on APKMirror reports versionName "13.0.1 build 25028"
        // (versionCode 25028); a normalized "13.0.1-25028-armv7a" string does NOT
        // match and trips the version-mismatch warning.
        //
        // TEST-CANDIDATE ONLY — DO NOT MERGE as-is. "13.2.1 build 27207"
        // (versionCode 27207) was added on branch test/netflix-13.2.1-27207 so
        // the CLI/Manager accept the newer APK for a controlled test build.
        // Static prerequisites match (both DEX fingerprints resolve uniquely,
        // ARMv7 split present), but startup/CertCheck/native-signature and
        // runtime ad/JS suppression are UNVERIFIED on this build. Keep this
        // entry out of main until cold-launch + login + ad behavior are
        // confirmed on-device (Onn coffey). See Netflix-13.2.1-Patch-Comparison.
        targets = listOf(
            AppTarget("13.0.1 build 25028"),
            AppTarget("13.2.1 build 27207"),
        )
    )
}
