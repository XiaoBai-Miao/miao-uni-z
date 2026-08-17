plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.miao"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.example.miao"
        minSdk = 28
        targetSdk = 37
        versionCode = 12
        versionName = "1.9.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("platform") {
            storeFile = file("platform.jks")
            storePassword = "android"
            keyAlias = "platform"
            keyPassword = "android"

            // 启用全版本签名方案
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
            enableV4Signing = true
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("platform")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            signingConfig = signingConfigs.getByName("platform")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
