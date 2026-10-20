plugins { alias(libs.plugins.android.application) }

android {
    namespace = "com.kododake.aabrowser.tv"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.kododake.aabrowser.tv"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
    buildTypes {
        // Compact sideload build, signed with the same local development key as debug.
        create("compact") {
            initWith(getByName("release"))
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}
// AGP enables Kotlin automatically; this Java-only module does not use its standard library.
configurations.matching {
    it.name.endsWith("RuntimeClasspath") || it.name.endsWith("CompileClasspath")
}.configureEach { exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib") }

// No runtime dependencies: UI, networking, storage and media use Android platform APIs.
dependencies { testImplementation(libs.junit) }

tasks.register<Copy>("packageTvLite") {
    dependsOn("assembleCompact")
    from(layout.buildDirectory.file("outputs/apk/compact/tv-compact.apk"))
    into(rootProject.layout.projectDirectory.dir("dist"))
    rename { "AA-Browser-TV-Lite-1.0.apk" }
}
