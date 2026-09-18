plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
  namespace = "in.aasmaan.puppetmaster"
  compileSdk = 35
  defaultConfig { applicationId = "in.aasmaan.puppetmaster"; minSdk = 26; targetSdk = 35 }
  compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
  kotlinOptions { jvmTarget = "17" }
}
dependencies {
  implementation("androidx.core:core-ktx:1.15.0")
  implementation("androidx.appcompat:appcompat:1.7.0")
  implementation("androidx.security:security-crypto:1.1.0-alpha06")
}
