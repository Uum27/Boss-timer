plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.boss"
    compileSdk {
        version = release(36)
    }
    lint {
        checkReleaseBuilds = false
    }

    defaultConfig {
        applicationId = "com.example.boss"
        minSdk = 24
        targetSdk = 36
        versionCode = 7
        versionName = "1.7"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isZipAlignEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 签名配置（如果你有正式签名，取消注释并配置）
            // signingConfig = signingConfigs.getByName("release")
        }

        debug {
            // 包名加后缀，与正式版区分（已存在）
            applicationIdSuffix = ".test"

            // 👇 新增：版本号加后缀，方便在“设置-应用”里识别
            versionNameSuffix = "-test"

            // 👇 新增：应用显示名称改成“Boss计时器（测试版）”
            // 这会覆盖 src/main/res/values/strings.xml 里的 app_name
            resValue("string", "app_name", "Boss计时器（共享测试版)")

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