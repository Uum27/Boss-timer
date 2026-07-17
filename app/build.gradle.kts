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

            // 加固
            // 启用代码压缩和混淆
            // 启用代码压缩和混淆
            isMinifyEnabled = true
            isShrinkResources = true

            // 混淆规则文件
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

            // 启用 ZipAlign 优化
            isZipAlignEnabled = true

            // 签名配置
//            signingConfig = signingConfigs.getByName("release")
            //

// origin            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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

    // 混淆工具
//    implementation("com.github.shadowsocks:plugin:0.1.0")
    implementation("androidx.preference:preference:1.2.0")
    // 加密库
    implementation ("com.github.bumptech.glide:glide:4.12.0")
    annotationProcessor ("com.github.bumptech.glide:compiler:4.12.0")
}