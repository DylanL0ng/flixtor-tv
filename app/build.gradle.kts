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
        versionCode = providers.environmentVariable("TV_VERSION_CODE").orElse("2").get().toInt()
        versionName = providers.environmentVariable("TV_VERSION_NAME").orElse("0.2.0").get()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "COMMIT_SHA", "\"${providers.environmentVariable("TV_COMMIT_SHA").orElse("local").get()}\"")
        buildConfigField("String", "REPORT_URL", "\"${providers.environmentVariable("TV_REPORT_URL").orElse("").get()}\"")
    }
    buildFeatures { compose = true; buildConfig = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    signingConfigs {
        create("personal") {
            val path = providers.environmentVariable("TV_KEYSTORE").orNull
            if (path != null) {
                storeFile = file(path)
                storePassword = providers.environmentVariable("TV_STORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("TV_KEY_ALIAS").orNull
                keyPassword = providers.environmentVariable("TV_KEY_PASSWORD").orNull
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (providers.environmentVariable("TV_KEYSTORE").isPresent) signingConfig = signingConfigs.getByName("personal")
        }
    }
    testOptions { unitTests.isReturnDefaultValues = true }
    sourceSets.getByName("androidTest").assets.srcDir(layout.buildDirectory.dir("update-test-assets"))
}
dependencies {
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
