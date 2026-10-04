plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ro.upb.orarreader"
    compileSdk = 36

    defaultConfig {
        applicationId = "ro.upb.orarreader"
        minSdk = 26
        targetSdk = 36
        versionCode = 191
        versionName = "1.9.1"
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
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("com.google.android.material:material:1.12.0")

    // UPB distributes the first-year timetable as legacy Excel .xls files.
    implementation("net.sourceforge.jexcelapi:jxl:2.6.12")
}
