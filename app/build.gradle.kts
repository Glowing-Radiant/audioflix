import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.ksp)
}

// The single source of the app version. scripts/release.sh bumps it; the release workflow checks
// that the pushed tag matches it.
val appVersion = "0.1.0"

// GitHub repository whose releases the in-app updater checks.
val updateRepository = "Glowing-Radiant/audioflix"

/** 1.2.3 -> 10203, so the version code always grows with the version name. */
fun versionCodeOf(version: String): Int {
  val (major, minor, patch) = version.split(".").map(String::toInt)
  require(minor < 100 && patch < 100) { "minor and patch must stay below 100" }
  return major * 10000 + minor * 100 + patch
}

// Release signing comes from environment variables in CI, or keystore.properties locally.
// Without either, release builds are left unsigned.
val keystoreProperties = Properties().apply {
  val file = rootProject.file("keystore.properties")
  if (file.exists()) file.inputStream().use(::load)
}
fun signingValue(env: String, key: String): String? = System.getenv(env) ?: keystoreProperties.getProperty(key)

android {
  namespace = "app.audioflix"
  compileSdk = 37
  defaultConfig {
    applicationId = "app.audioflix"
    minSdk = 26
    targetSdk = 37
    versionCode = versionCodeOf(appVersion)
    versionName = appVersion
    buildConfigField("String", "UPDATE_REPOSITORY", "\"$updateRepository\"")
  }

  signingConfigs {
    // A shared debug key, so AudioFlix Dev builds from CI and from any machine update each other.
    // It only ever signs app.audioflix.debug; releases use the key from the secrets below.
    getByName("debug") {
      storeFile = file("debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
    val storeFile = signingValue("RELEASE_KEYSTORE_FILE", "storeFile")
    if (storeFile != null) {
      create("release") {
        this.storeFile = rootProject.file(storeFile)
        storePassword = signingValue("RELEASE_KEYSTORE_PASSWORD", "storePassword")
        keyAlias = signingValue("RELEASE_KEY_ALIAS", "keyAlias")
        keyPassword = signingValue("RELEASE_KEY_PASSWORD", "keyPassword")
      }
    }
  }

  buildTypes {
    debug {
      // Lets a development build sit next to the released app; they are signed with different keys.
      applicationIdSuffix = ".debug"
      buildConfigField("Boolean", "CHECK_UPDATES_ON_START", "false")
    }
    release {
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.findByName("release")
      buildConfigField("Boolean", "CHECK_UPDATES_ON_START", "true")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures {
    compose = true
    aidl = false
    buildConfig = true
    shaders = false
  }

  packaging {
    resources {
      excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
  }
}

kotlin {
  jvmToolchain(17)
}

ksp {
  arg("room.generateKotlin", "true")
}

dependencies {
  implementation(platform(libs.androidx.compose.bom))

  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.lifecycle.service)
  implementation(libs.androidx.activity.compose)

  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.material.icons)
  debugImplementation(libs.androidx.compose.ui.tooling)

  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.lifecycle.viewmodel.navigation3)

  // Library database
  implementation(libs.androidx.room.runtime)
  implementation(libs.androidx.room.ktx)
  ksp(libs.androidx.room.compiler)

  // Downloads
  implementation(libs.androidx.work.runtime.ktx)
  implementation(libs.okhttp)
  implementation(libs.jsoup)
  implementation(libs.kotlinx.serialization.json)

  // Playback
  implementation(libs.androidx.media3.exoplayer)
  implementation(libs.androidx.media3.session)
  implementation(libs.kotlinx.coroutines.guava)

  testImplementation(libs.junit)
}
