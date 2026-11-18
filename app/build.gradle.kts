plugins {
    id("com.android.application")
}

android {
    namespace = "com.rewind.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.rewind.app"
        minSdk = 30 // Game sound capture (AudioPlaybackCapture) arrived in Android 10; 11 to match the other Thor apps.
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        // Committed debug key so a build from any machine installs as an update
        // over a build from any other. Without it AGP invents a fresh throwaway
        // key per environment and Android refuses the install.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
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
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
