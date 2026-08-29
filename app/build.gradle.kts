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
        eachDependency {
            if (requested.group.startsWith("androidx.compose") && requested.name != "compose-bom") {
                val version = when {
                    requested.group == "androidx.compose.material3" -> "1.3.2"
                    requested.group == "androidx.compose.material" && requested.name.startsWith("material-icons-core") -> "1.6.0"
                    else -> "1.9.0"
                }
                useVersion(version)
                because("Keep every Compose artefact on one AGP 8.13.2-compatible family")
            }
        }
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

tasks.register("verifyComposeDependencyFamily") {
    group = "verification"
    description = "Fails if resolved Compose artefacts drift into incompatible families."
    doLast {
        val unexpected = configurations.getByName("debugRuntimeClasspath").incoming.resolutionResult.allComponents
            .mapNotNull { component ->
                val id = component.moduleVersion ?: return@mapNotNull null
                when {
                    id.group == "androidx.compose" && id.name == "compose-bom" && id.version != "2026.08.00" ->
                        "${id.group}:${id.name}:${id.version}"
                    id.group == "androidx.compose.material" && id.name.startsWith("material-icons-core") && id.version != "1.6.0" ->
                        "${id.group}:${id.name}:${id.version}"
                    id.group == "androidx.compose.material3" && id.version != "1.3.2" ->
                        "${id.group}:${id.name}:${id.version}"
                    id.group.startsWith("androidx.compose") &&
                        id.group != "androidx.compose.material3" &&
                        !(id.group == "androidx.compose.material" && id.name.startsWith("material-icons-core")) &&
                        !(id.group == "androidx.compose" && id.name == "compose-bom") &&
                        id.version != "1.9.0" ->
                        "${id.group}:${id.name}:${id.version}"
                    else -> null
                }
            }
            .toSet()
        check(unexpected.isEmpty()) {
            "Mixed Compose dependency family detected: ${unexpected.sorted().joinToString()}."
        }
    }
}

tasks.named("check") {
    dependsOn("verifyComposeDependencyFamily")
}
