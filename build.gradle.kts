plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    // AGP 9 ships built-in Kotlin — never apply org.jetbrains.kotlin.android
    alias(libs.plugins.kotlin.compose) apply false
}
