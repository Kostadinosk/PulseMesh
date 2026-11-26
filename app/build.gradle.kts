import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.ksp)
}

android {
  namespace = "com.kostadinosk.pulsemesh"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.KostadinosK.pulsemesh"
    minSdk = 24
    targetSdk = 36
    versionCode = 26
    versionName = "2.1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  flavorDimensions += "tier"
  productFlavors {
    create("lite") {
      dimension = "tier"
      buildConfigField("boolean", "IS_FULL_FLAVOR", "false")
      buildConfigField("String", "FLAVOR_TIER", "\"lite\"")
    }
    create("full") {
      dimension = "tier"
      buildConfigField("boolean", "IS_FULL_FLAVOR", "true")
      buildConfigField("String", "FLAVOR_TIER", "\"full\"")
    }
  }

  val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
  }
  val releaseKeystorePath = System.getenv("KEYSTORE_PATH") ?: localProps.getProperty("KEYSTORE_PATH")
  val releaseStorePassword = System.getenv("STORE_PASSWORD") ?: localProps.getProperty("STORE_PASSWORD")
  val releaseKeyAlias = System.getenv("KEY_ALIAS") ?: localProps.getProperty("KEY_ALIAS") ?: "upload"
  val releaseKeyPassword = System.getenv("KEY_PASSWORD") ?: localProps.getProperty("KEY_PASSWORD")
  val releaseKeystoreFile = releaseKeystorePath?.let { file(it) }
  val hasReleaseSigning = releaseKeystoreFile != null &&
    releaseKeystoreFile.exists() &&
    !releaseStorePassword.isNullOrBlank() &&
    !releaseKeyPassword.isNullOrBlank()
  val debugKeystoreFile = file("$rootDir/debug.keystore")

  signingConfigs {
    if (hasReleaseSigning) {
      create("release") {
        storeFile = releaseKeystoreFile
        storePassword = releaseStorePassword
        keyAlias = releaseKeyAlias
        keyPassword = releaseKeyPassword
      }
    }
    if (debugKeystoreFile.exists()) {
      create("debugConfig") {
        storeFile = debugKeystoreFile
        storePassword = "android"
        keyAlias = "androiddebugkey"
        keyPassword = "android"
      }
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = true
      isShrinkResources = true
      isDebuggable = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      if (hasReleaseSigning) {
        signingConfig = signingConfigs.getByName("release")
      }
    }
    debug {
      if (debugKeystoreFile.exists()) {
        signingConfig = signingConfigs.getByName("debugConfig")
      }
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions {
    unitTests {
      isIncludeAndroidResources = true
      all {
        it.maxHeapSize = "2048m"
        it.testLogging {
          events("started", "passed", "failed", "skipped")
        }
        val offlineDir = file("/home/appuser/.m2/repository/org/robolectric/android-all-instrumented/16-robolectric-13921718-i7")
        if (offlineDir.exists()) {
          it.systemProperty("robolectric.offline", "true")
          it.systemProperty("robolectric.dependency.dir", offlineDir.absolutePath)
        } else {
          it.systemProperty("robolectric.offline", "false")
        }
      }
    }
  }
  dependenciesInfo {
    includeInApk = false
    includeInBundle = false
  }
}

dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.androidx.room.runtime)
  implementation(libs.androidx.room.ktx)
  ksp(libs.androidx.room.compiler)
  implementation(libs.androidx.sqlite)
  implementation(libs.androidx.biometric)
  implementation(libs.zxing.core)
  "fullImplementation"(libs.sqlcipher.android)
  "fullImplementation"(libs.stream.webrtc.android)
  "fullImplementation"(libs.maplibre.android)
  "fullImplementation"(libs.androidx.camera.core)
  "fullImplementation"(libs.androidx.camera.camera2)
  "fullImplementation"(libs.androidx.camera.lifecycle)
  "fullImplementation"(libs.androidx.camera.view)
  testImplementation(platform(libs.androidx.compose.bom))
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(platform(libs.androidx.compose.bom))
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
}
