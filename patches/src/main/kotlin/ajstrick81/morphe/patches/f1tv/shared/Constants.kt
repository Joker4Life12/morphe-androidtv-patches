package ajstrick81.morphe.patches.f1tv.shared

import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    val COMPATIBILITY = Compatibility(
        name = "F1 TV Android TV",
        packageName = "com.formulaone.production",
        appIconColor = 0xE10600, // F1 red
        // AppTarget must equal the APK versionName verbatim (Morphe rule).
        targets = listOf(AppTarget("3.0.49.4-SP166.4.1-release-R54.2-tv")),
    )
}
