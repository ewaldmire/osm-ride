plugins {
    id("com.android.application") version "8.5.2" apply false
    // 2.2.10, not 2.0.20: MapLibre's android-sdk 13.4.1 jar carries Kotlin 2.2.0 metadata,
    // which a 2.0.x compiler can't read ("Internal compiler error" at compileReleaseKotlin).
    id("org.jetbrains.kotlin.android") version "2.2.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.10" apply false
}
