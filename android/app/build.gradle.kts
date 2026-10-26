plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "dev.personal.flixtortv"
    compileSdk = 34
    defaultConfig {
        applicationId = "dev.personal.flixtortv"
        minSdk = 29
        targetSdk = 34
        versionCode = (providers.environmentVariable("TV_VERSION_CODE").orNull?.trim()?.takeIf { it.isNotEmpty() } ?: "1000").toInt()
        versionName = providers.environmentVariable("TV_VERSION_NAME").orNull?.trim()?.takeIf { it.isNotEmpty() } ?: "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "COMMIT_SHA", "\"${providers.environmentVariable("TV_COMMIT_SHA").orElse("local").get()}\"")
        buildConfigField("String", "REPORT_URL", "\"${providers.environmentVariable("TV_REPORT_URL").orElse("").get()}\"")
        // Supplies the IntroDB key from the build environment rather than the viewer. Blank
        // disables contributing; reading skip segments never needs a key.
        buildConfigField("String", "INTRODB_API_KEY", "\"${providers.environmentVariable("TV_INTRODB_API_KEY").orElse("").get()}\"")
    }
    buildFeatures { compose = true; buildConfig = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    // An empty value (a .env file with the entry left blank) must count as absent:
    // file("") throws, and an unsigned release build is the intended fallback.
    val keystorePath = providers.environmentVariable("TV_KEYSTORE").orNull?.trim()?.takeIf { it.isNotEmpty() }
    signingConfigs {
        create("personal") {
            if (keystorePath != null) {
                storeFile = file(keystorePath)
                storePassword = providers.environmentVariable("TV_STORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("TV_KEY_ALIAS").orNull
                keyPassword = providers.environmentVariable("TV_KEY_PASSWORD").orNull
            }
        }
    }
    buildTypes {
        debug { applicationIdSuffix = ".solidqa" }
        release {
            isMinifyEnabled = false
            if (keystorePath != null) signingConfig = signingConfigs.getByName("personal")
        }
    }
    testOptions { unitTests.isReturnDefaultValues = true }
    testBuildType = if (project.hasProperty("solidUpgradeQA")) "release" else "debug"
    sourceSets.getByName("androidTest").assets.srcDir(layout.buildDirectory.dir("update-test-assets"))
}
dependencies {
    implementation("androidx.webkit:webkit:1.11.0")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.tv:tv-material:1.0.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.4.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.17.2")
    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("com.google.zxing:core:3.5.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test:runner:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}

val webRoot = rootProject.projectDir.parentFile
val buildSolidTv by tasks.registering(Exec::class) {
    workingDir(webRoot)
    commandLine("npm", "run", "build")
    inputs.dir(webRoot.resolve("src"))
    inputs.dir(webRoot.resolve("public"))
    inputs.files(webRoot.resolve("package-lock.json"), webRoot.resolve("vite.config.ts"), webRoot.resolve("index.html"))
    outputs.dir(webRoot.resolve("dist"))
}
val bundleSolidTv by tasks.registering(Sync::class) {
    dependsOn(buildSolidTv)
    from(webRoot.resolve("dist"))
    into(layout.buildDirectory.dir("generated/solidtv/www"))
}
android.sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/solidtv"))
android.sourceSets.getByName("debug").assets.srcDir("src/androidTest/assets")
android.sourceSets.getByName("debug").assets.srcDir(webRoot.resolve("test/fixtures"))
tasks.named("preBuild") { dependsOn(bundleSolidTv) }
