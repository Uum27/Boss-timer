plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.boss"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.example.boss"
        minSdk = 24
        targetSdk = 36
        versionCode = 2
        versionName = "1.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // 正式版：启用混淆、压缩、对齐
            isMinifyEnabled = true
            isShrinkResources = true
            isZipAlignEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 签名配置（如果你有 release 签名，取消下面注释并配置 signingConfigs）
            // signingConfig = signingConfigs.getByName("release")
        }

        // 👇 新增测试版（debug）配置
        debug {
            // 包名加后缀 .test，与正式版区分，可同时安装
            applicationIdSuffix = ".test"
            // 调试版不混淆，加快构建
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
    implementation("org.greenrobot:eventbus:3.3.1")
    implementation("androidx.preference:preference:1.2.0")
    implementation("com.github.bumptech.glide:glide:4.12.0")
    annotationProcessor("com.github.bumptech.glide:compiler:4.12.0")
}