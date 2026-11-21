plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
}

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.KostadinosK.pulsemesh"
    minSdk = 24
    targetSdk = 36
    versionCode = 11
    versionName = "1.3.2"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  val releaseKeystoreFile = file(System.getenv("KEYSTORE_PATH") ?: "$rootDir/my-upload-key.jks")
  val debugKeystoreFile = file("$rootDir/debug.keystore")

  signingConfigs {
    if (releaseKeystoreFile.exists()) {
      create("release") {
        storeFile = releaseKeystoreFile
        storePassword = System.getenv("STORE_PASSWORD") ?: "pulsemesh2026"
        keyAlias = "upload"
        keyPassword = System.getenv("KEY_PASSWORD") ?: "pulsemesh2026"
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
      if (releaseKeystoreFile.exists()) {
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
