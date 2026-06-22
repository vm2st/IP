plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.vm2st.ip"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.vm2st.ip"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // Совместимость для Java компилятора
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    // Совместимость для Kotlin компилятора
    kotlinOptions {
        jvmTarget = "21"
    }
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.22")
}