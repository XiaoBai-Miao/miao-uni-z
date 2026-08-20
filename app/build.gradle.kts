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
        versionCode = 21
        versionName = "1.9.11"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // platform.jks 为系统签名私钥，已被 .gitignore 排除、不入库。
    // CI 等无密钥环境：hasPlatformKey=false，回退到默认 debug 签名，保证可编译打包。
    val hasPlatformKey = file("platform.jks").exists()

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
            // 无 platform.jks（如 CI）时回退到默认 debug 签名，保证可编译打包
            signingConfig = if (hasPlatformKey) signingConfigs.getByName("platform") else signingConfigs.getByName("debug")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            signingConfig = if (hasPlatformKey) signingConfigs.getByName("platform") else null
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
