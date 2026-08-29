plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.commandcode.chat"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.commandcode.chat"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

configurations.configureEach {
    resolutionStrategy {
        force(
            "androidx.compose.animation:animation-core:1.9.0",
            "androidx.compose.animation:animation-core-android:1.9.0",
            "androidx.compose.animation:animation:1.9.0",
            "androidx.compose.animation:animation-android:1.9.0",
            "androidx.compose.foundation:foundation:1.9.0",
            "androidx.compose.foundation:foundation-android:1.9.0",
            "androidx.compose.foundation:foundation-layout:1.9.0",
            "androidx.compose.foundation:foundation-layout-android:1.9.0",
            "androidx.compose.runtime:runtime:1.9.0",
            "androidx.compose.runtime:runtime-android:1.9.0",
            "androidx.compose.runtime:runtime-saveable:1.9.0",
            "androidx.compose.runtime:runtime-saveable-android:1.9.0",
            "androidx.compose.ui:ui:1.9.0",
            "androidx.compose.ui:ui-android:1.9.0",
            "androidx.compose.ui:ui-graphics:1.9.0",
            "androidx.compose.ui:ui-graphics-android:1.9.0",
            "androidx.compose.ui:ui-text:1.9.0",
            "androidx.compose.ui:ui-text-android:1.9.0",
            "androidx.compose.ui:ui-tooling:1.9.0",
            "androidx.compose.ui:ui-tooling-android:1.9.0",
            "androidx.compose.ui:ui-tooling-data:1.9.0",
            "androidx.compose.ui:ui-tooling-data-android:1.9.0",
            "androidx.compose.ui:ui-tooling-preview:1.9.0",
            "androidx.compose.material:material-ripple:1.9.0",
            "androidx.compose.material:material-ripple-android:1.9.0",
            "androidx.compose.material:material:1.9.0",
            "androidx.compose.material:material-android:1.9.0",
        )
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.08.00"))
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.2")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui:1.9.0")
    implementation("androidx.compose.ui:ui-graphics:1.9.0")
    implementation("androidx.compose.ui:ui-tooling-preview:1.9.0")
    implementation("androidx.compose.material3:material3:1.3.2")
    debugImplementation("androidx.compose.ui:ui-tooling:1.9.0")
    testImplementation("junit:junit:4.13.2")
}
