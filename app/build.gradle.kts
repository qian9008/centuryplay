plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.chaquo.python")
}

android {
    namespace = "com.airplay.streamer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.airplay.streamer"
        minSdk = 29  // Android 10+ required for AudioPlaybackCapture
        targetSdk = 35
        versionCode = 3
        versionName = "1.0"

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
        }
        release {
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

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }
}

chaquopy {
    defaultConfig {
        version = "3.13"

        pip {
            // Install all packages without automatic dependency resolution
            // (we list every dependency explicitly to control versions)
            options("--no-deps")

            // Native dependencies (pre-built by Chaquopy)
            install("cryptography==42.0.8")
            install("cffi")  // _cffi_backend.so needs libffi.so (provided in jniLibs/)
            install("pycparser")  // cffi dependency
            install("aiohttp")
            install("yarl")
            install("multidict")
            install("frozenlist")

            // Pure Python dependencies
            install("srptools")
            install("six")
            install("chacha20poly1305-reuseable")
            install("zeroconf")
            install("tabulate")
            install("tinytag")
            install("ifaddr")
            install("protobuf")
            install("requests")
            install("aiosignal")
            install("attrs")
            install("async-timeout")
            install("charset-normalizer")
            install("idna")
            install("certifi")
            install("urllib3")
            install("aiohappyeyeballs")
            install("propcache")
            // miniaudio + pydantic are provided as pure-Python mocks in app/src/main/python/

            // pyatv (cryptography 42 is API-compatible despite >=44 requirement)
            install("pyatv==0.17.0")
        }
    }
}

dependencies {
    // AndroidX Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")

    // jmDNS for AirPlay discovery (mDNS/Bonjour)
    implementation("org.jmdns:jmdns:3.5.9")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // RecyclerView for speaker list
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}
