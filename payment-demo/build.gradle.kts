plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "cn.serendipityr.tally.paymentdemo"
    compileSdk = 36
    defaultConfig {
        applicationId = "cn.serendipityr.tally.paymentdemo"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
