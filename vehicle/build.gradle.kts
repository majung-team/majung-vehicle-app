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

android {
    namespace = "app.majung.vehicle"
    compileSdk = 34
    useLibrary("android.car")
    defaultConfig {
        applicationId = "app.majung.vehicle"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0-dev"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { buildConfig = true }
    buildTypes {
        debug {
            buildConfigField("String", "PICKUP_API", "\"${publicApi ?: "http://10.0.2.2:8080"}\"")
            buildConfigField("String", "PICKUP_WEB_ORIGIN", "\"${publicApi ?: "http://127.0.0.1:8080"}\"")
        }
        release {
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
    implementation("com.google.zxing:core:3.5.3")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("junit:junit:4.13.2")
}
