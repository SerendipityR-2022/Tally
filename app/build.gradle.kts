import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// Secrets stay in an ignored, device-local file; Debug builds need no release key.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.isFile) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}
val requiredSigningProperties = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
val missingSigningProperties = requiredSigningProperties.filter {
    keystoreProperties.getProperty(it).isNullOrBlank()
}
val hasReleaseSigning = keystorePropertiesFile.isFile && missingSigningProperties.isEmpty()
// GitHub builds the APK without credentials; distribution still requires release signing.
val unsignedCiBuild = providers.gradleProperty("tallyUnsignedCiBuild").map { it == "true" }.orElse(false)

// Never silently distribute an unsigned Release when the local key is missing.
val validateReleaseSigningInputs = tasks.register("validateReleaseSigningInputs") {
    group = "verification"
    description = "Checks local release signing credentials without printing secrets."
    doLast {
        check(keystorePropertiesFile.isFile) {
            "Release signing requires keystore.properties; see docs/release-signing.md."
        }
        check(missingSigningProperties.isEmpty()) {
            "keystore.properties is missing: ${missingSigningProperties.joinToString()}"
        }
        check(rootProject.file(keystoreProperties.getProperty("storeFile")).isFile) {
            "Release keystore not found; restore the original signing key before building."
        }
    }
}
tasks.configureEach {
    if (name == "preReleaseBuild" && !unsignedCiBuild.get()) dependsOn(validateReleaseSigningInputs)
}

android {
    namespace = "com.example.budgetapp"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.budgetapp"
        minSdk = 26
        targetSdk = 36
        versionCode = 120
        versionName = "3.8.7"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                // storeFile is relative to the project root, not the app module.
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storeType = keystoreProperties.getProperty("storeType", "PKCS12")
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isDebuggable = false
            if (hasReleaseSigning && !unsignedCiBuild.get()) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // Shizuku 核心依赖
    val shizukuVersion = "13.1.5"
    implementation("dev.rikka.shizuku:api:$shizukuVersion")
    implementation("dev.rikka.shizuku:provider:$shizukuVersion")

    implementation("androidx.work:work-runtime:2.8.1")

    // WorkManager (用于定期静默保活自愈检测)
    implementation("androidx.work:work-runtime:2.9.0")

    // --- 默认生成的依赖 (不要删，如果报错就保留你原来的) ---
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)

    // --- 下面是手动添加的库 (直接用字符串，不要用 libs.xxx) ---

    // 1. Navigation (导航)
    implementation("androidx.navigation:navigation-fragment:2.7.7")
    implementation("androidx.navigation:navigation-ui:2.7.7")

    // 2. Room Database (数据库)
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    annotationProcessor("androidx.room:room-compiler:$roomVersion") // Java 项目使用 annotationProcessor

    // 3. ViewModel & LiveData
    implementation("androidx.lifecycle:lifecycle-viewmodel:2.7.0")
    implementation("androidx.lifecycle:lifecycle-livedata:2.7.0")

    // 4. MPAndroidChart (图表库)
    implementation("com.github.PhilJay:MPAndroidChart:v3.1.0")
    implementation("com.google.code.gson:gson:2.10.1")

    implementation("androidx.cardview:cardview:1.0.0")

    implementation("com.google.android.material:material:1.12.0")

    implementation("androidx.documentfile:documentfile:1.0.1")

    implementation("org.apache.poi:poi-ooxml:5.2.3")

    implementation("androidx.biometric:biometric:1.1.0")

    implementation("cn.6tail:lunar:1.3.15")

    implementation("com.google.android.flexbox:flexbox:3.0.0")

    implementation("com.google.mlkit:text-recognition-chinese:16.0.0")

    implementation("com.caverock:androidsvg-aar:1.4")

}
