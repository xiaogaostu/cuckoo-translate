plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.floatingtranslate.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.floatingtranslate.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 9
        versionName = "1.6.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // v1.3.0：已去掉 Compose。MainActivity 只用 android.* 系统 Views，
    // FloatingBallService 只用 NotificationCompat + LocalBroadcastManager。
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")
}
