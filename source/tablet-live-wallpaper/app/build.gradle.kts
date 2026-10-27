import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

val appVersionProperties = Properties().apply {
    rootProject.file("version.properties").inputStream().use(::load)
}
val appVersionName = requireNotNull(appVersionProperties.getProperty("versionName")) {
    "version.properties must define versionName"
}
val appVersionCode = requireNotNull(appVersionProperties.getProperty("versionCode")) {
    "version.properties must define versionCode"
}.toInt()

android {
    namespace = "com.codex.tabletlivewallpaper"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.codex.tabletlivewallpaper"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    sourceSets {
        getByName("main") {
            manifest.srcFile("../AndroidManifest.xml")
            java.srcDirs("../src")
            res.srcDirs("../res")
            assets.srcDirs("../assets")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
        checkDependencies = true
        htmlReport = true
        xmlReport = true
        sarifReport = true
    }
}

dependencies {
    testImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
