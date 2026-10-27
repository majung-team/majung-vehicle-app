import java.net.URI

plugins {
    id("com.android.application")
}

val publicApi = providers.gradleProperty("pickupApi").orNull?.trimEnd('/')
if (publicApi != null) {
    val uri = URI(publicApi)
    require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null &&
        uri.rawQuery == null && uri.rawFragment == null && uri.path.isNullOrEmpty() && uri.port in listOf(-1, 443)) {
        "pickupApi must be an HTTPS origin without a path, credentials or query"
    }
}

val buildRevision = providers.environmentVariable("MAJUNG_BUILD_SHA").orNull ?: "local"
require(buildRevision == "local" || Regex("[0-9a-f]{40}").matches(buildRevision)) { "Invalid build revision" }

val signingStore = providers.environmentVariable("MAJUNG_SIGNING_STORE_FILE").orNull
val signingPassword = providers.environmentVariable("MAJUNG_SIGNING_STORE_PASSWORD").orNull
val signingAlias = providers.environmentVariable("MAJUNG_SIGNING_KEY_ALIAS").orNull
val signingKeyPassword = providers.environmentVariable("MAJUNG_SIGNING_KEY_PASSWORD").orNull
val hasReleaseSigning = listOf(signingStore, signingPassword, signingAlias, signingKeyPassword).all { !it.isNullOrBlank() }

android {
    namespace = "app.majung.vehicle"
    compileSdk = 34
    useLibrary("android.car")
    defaultConfig {
        applicationId = "app.majung.vehicle"
        minSdk = 28
        targetSdk = 34
        versionCode = 5
        versionName = "0.2.3-preview"
        buildConfigField("String", "SOURCE_REVISION", "\"$buildRevision\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { buildConfig = true }
    if (hasReleaseSigning) {
        signingConfigs.create("majungRelease") {
            storeFile = file(requireNotNull(signingStore))
            storePassword = signingPassword
            keyAlias = signingAlias
            keyPassword = signingKeyPassword
        }
    }
    buildTypes {
        debug {
            buildConfigField("String", "PICKUP_API", "\"${publicApi ?: "http://10.0.2.2:8080"}\"")
            buildConfigField("String", "PICKUP_WEB_ORIGIN", "\"${publicApi ?: "http://127.0.0.1:8080"}\"")
        }
        release {
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("majungRelease")
            // A release needs an explicitly selected HTTPS origin and signing configuration.
            buildConfigField("String", "PICKUP_API", "\"${publicApi ?: ""}\"")
            buildConfigField("String", "PICKUP_WEB_ORIGIN", "\"${publicApi ?: ""}\"")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")
    implementation("com.google.zxing:core:3.5.3")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("junit:junit:4.13.2")
}

tasks.matching { it.name == "packageRelease" }.configureEach {
    doFirst {
        require(publicApi != null) { "Release APK needs -PpickupApi=https://majung.cloud" }
        require(buildRevision != "local") { "Release APK needs a source revision; use tools/build-release.py" }
        require(hasReleaseSigning && file(requireNotNull(signingStore)).isFile) {
            "Release APK needs the four MAJUNG_SIGNING_* environment variables; keep signing files outside this repository"
        }
    }
}
