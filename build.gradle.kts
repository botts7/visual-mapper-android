// Top-level build file for Visual Mapper Companion App
plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.21" apply false
    // KSP for Room annotation processing (Phase 1 refactor)
    id("com.google.devtools.ksp") version "1.9.21-1.0.16" apply false
}
