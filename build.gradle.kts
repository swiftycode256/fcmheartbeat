// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
// This project lives in ~/Documents, which iCloud Drive syncs. iCloud creates "Foo 2.class"
// conflict copies inside build outputs, and D8 then fails with "Type ... is defined multiple
// times". iCloud skips anything whose name ends in ".nosync", so keep build outputs there.
allprojects {
    layout.buildDirectory = layout.projectDirectory.dir("build.nosync")
}
