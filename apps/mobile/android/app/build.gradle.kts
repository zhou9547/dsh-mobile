plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releaseStoreFile = providers.environmentVariable("DSH_ANDROID_KEYSTORE_FILE")
val releaseStorePassword = providers.environmentVariable("DSH_ANDROID_KEYSTORE_PASSWORD")
val releaseKeyAlias = providers.environmentVariable("DSH_ANDROID_KEY_ALIAS")
val releaseKeyPassword = providers.environmentVariable("DSH_ANDROID_KEY_PASSWORD")
val releaseSigningConfigured = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { it.isPresent && it.get().isNotBlank() }

android {
    namespace = "io.github.sayach.dshmobile"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.sayach.dshmobile"
        minSdk = 29
        targetSdk = 36
        versionCode = 83
        versionName = "0.6.7"
        manifestPlaceholders["launcherAppLabel"] = "@string/app_name"
    }

    signingConfigs {
        create("release") {
            if (releaseSigningConfigured) {
                storeFile = file(releaseStoreFile.get())
                storePassword = releaseStorePassword.get()
                keyAlias = releaseKeyAlias.get()
                keyPassword = releaseKeyPassword.get()
            }
        }
    }

    buildTypes {
        create("iconTest") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".icontest"
            versionNameSuffix = "-icon-test"
            matchingFallbacks += listOf("debug")
        }
        release {
            isMinifyEnabled = true
            if (releaseSigningConfigured) signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
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
        buildConfig = true
    }

    lint {
        checkTestSources = false
    }

    sourceSets.getByName("test").resources.srcDir("../../contract")
    testOptions.unitTests.isIncludeAndroidResources = true
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.webkit:webkit:1.17.1")
    implementation("com.google.zxing:core:3.5.4")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16")
    testImplementation("org.json:json:20260814")
}

// Robolectric/native graphics change process-wide Android/security runtime state. Keep the
// existing real-JSSE loopback tests in their own JVM, with no UI sandbox executed before them.
tasks.withType<Test>().configureEach {
    if (name == "testDebugUnitTest" || name == "testReleaseUnitTest") {
        exclude("**/CustomLauncherIconTest.class")
    }
}
tasks.register<Test>("testIconUi") {
    val unitTests = tasks.named<Test>("testDebugUnitTest").get()
    dependsOn(unitTests)
    testClassesDirs = unitTests.testClassesDirs
    classpath = unitTests.classpath
    systemProperties(unitTests.systemProperties)
    include("**/CustomLauncherIconTest.class")
    maxHeapSize = "1g"
}
