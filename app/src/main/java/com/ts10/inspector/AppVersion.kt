package com.ts10.inspector

/** Version incorporada por Gradle desde el unico valor definido en app/build.gradle.kts. */
object AppVersion {
    val code: Int
        get() = BuildConfig.VERSION_CODE

    val name: String
        get() = BuildConfig.VERSION_NAME

}
