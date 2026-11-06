plugins {
    id("com.android.application")
}

// Public Vibes API entry points are build-time configuration, not secrets.
// Override with -PVIBES_RENDER_URL=... / VIBES_RENDER_URL=... when building.
fun buildConfigString(value: String): String = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
val vibesRenderUrl = providers.gradleProperty("VIBES_RENDER_URL")
    .orElse(providers.environmentVariable("VIBES_RENDER_URL"))
    .getOrElse("https://vibes-quantum-api.onrender.com")
val vibesCloudflareUrl = providers.gradleProperty("VIBES_CLOUDFLARE_URL")
    .orElse(providers.environmentVariable("VIBES_CLOUDFLARE_URL"))
    .getOrElse("https://api.vibesquantum.tech")
val vibesExtraGatewayUrls = providers.gradleProperty("VIBES_EXTRA_GATEWAY_URLS")
    .orElse(providers.environmentVariable("VIBES_EXTRA_GATEWAY_URLS"))
    .getOrElse("https://vibesquantum.duckdns.org,https://vibesquantum.tech,https://vibesquantum.com,https://api.vibesquantum.com")

android {
    namespace = "com.vibes.only"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.vibes.only"
        minSdk = 26
        targetSdk = 37
        versionCode = 26
        versionName = "19.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "VIBES_RENDER_URL", buildConfigString(vibesRenderUrl))
        buildConfigField("String", "VIBES_CLOUDFLARE_URL", buildConfigString(vibesCloudflareUrl))
        buildConfigField("String", "VIBES_EXTRA_GATEWAY_URLS", buildConfigString(vibesExtraGatewayUrls))
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
        }
        release {
            // Keep release builds conservative until a real signing/obfuscation
            // profile is supplied. R8 can be enabled later with a tested ruleset.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/AL2.0",
                "/META-INF/LGPL2.1"
            )
        }
    }

    lint {
        abortOnError = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("androidx.core:core-splashscreen:1.2.0")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    testImplementation("junit:junit:4.13.2")
}


android {
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.credentials:credentials:1.6.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.6.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
}
