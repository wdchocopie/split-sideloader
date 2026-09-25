import java.security.MessageDigest
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.paparazzi)
}

val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

android {
    namespace = "com.sideload.splitinstaller"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sideload.splitinstaller"
        minSdk = 26
        targetSdk = 34
        versionCode = 6
        versionName = "1.5.0"
        resourceConfigurations += listOf("en", "vi")
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            if (keystoreProps.isNotEmpty()) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
    lint {
        // This app reaches for hidden fields on purpose; lint has no way to know that.
        abortOnError = false
        checkReleaseBuilds = false
    }
}

/**
 * Writes the manifest an OTA channel serves, for the APK that was just built.
 *
 * Publish the two files side by side and point the app's update channel at the JSON:
 *   ./gradlew :app:assembleRelease :app:otaManifest
 */
tasks.register("otaManifest") {
    description = "Writes ota.json next to the release APK."
    group = "publishing"
    // The digest has to be of the APK this run produced, not of whatever was there before.
    dependsOn("assembleRelease")
    val apk = layout.buildDirectory.file("outputs/apk/release/app-release.apk")
    val out = layout.buildDirectory.file("outputs/apk/release/ota.json")
    val versionCode = android.defaultConfig.versionCode
    val versionName = android.defaultConfig.versionName
    val baseUrl = (project.findProperty("otaBaseUrl") as String?).orEmpty()
    inputs.file(apk)
    inputs.property("otaBaseUrl", baseUrl)
    outputs.file(out)
    doLast {
        val file = apk.get().asFile
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n <= 0) break
                digest.update(buffer, 0, n)
            }
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        val name = "SplitSideloader-$versionName.apk"
        val url = baseUrl.trimEnd('/') + "/" + name
        val json = """
            {
              "versionCode": $versionCode,
              "versionName": "$versionName",
              "url": "$url",
              "fileName": "$name",
              "sha256": "$sha",
              "size": ${file.length()},
              "minSdk": ${android.defaultConfig.minSdk},
              "notes": ""
            }
        """.trimIndent()
        val target = out.get().asFile
        target.writeText(json)
        logger.lifecycle("ota.json -> " + target.absolutePath)
        if (baseUrl.isEmpty()) {
            logger.lifecycle("url is relative: rerun with -PotaBaseUrl=https://host/path to fill it in")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.work)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    debugImplementation(libs.androidx.ui.tooling)
    testImplementation(libs.junit)
    // A real org.json on the test classpath, so the update parsers can be tested off-device.
    testImplementation(libs.json.jvm)
}
