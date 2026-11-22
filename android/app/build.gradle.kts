import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// A release build is signed with the key that keystore.properties names (storeFile, storePassword,
// keyAlias, keyPassword). The file is not in git. Debug builds do not need it.
val keystoreFile = rootProject.file("keystore.properties")
val keystore = Properties().apply { if (keystoreFile.exists()) keystoreFile.inputStream().use(::load) }
fun signing(key: String): String =
    keystore.getProperty(key) ?: throw GradleException("android/keystore.properties has no $key")

android {
    namespace = "com.espotek.roborockathome"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.espotek.roborockathome"
        minSdk = 26
        // Google Play has refused anything that targets less than Android 16 since 31 August 2026.
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        create("release") {
            if (keystoreFile.exists()) {
                storeFile = file(signing("storeFile"))
                storePassword = signing("storePassword")
                keyAlias = signing("keyAlias")
                keyPassword = signing("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // The screenshot fixtures are built from core's recordings of the demo server and its floor plans
    // (/fixtures/*.json, /maps/*.map), read where core keeps them.
    sourceSets.getByName("test").resources.srcDir(rootProject.file("core/src/test/resources"))

    // The unit tests draw screens to PNG files on the JVM (Robolectric's native graphics, captured by
    // Roborazzi) into build/shots. See app/src/test/.../shots/Shots.kt.
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { test ->
                val shots = layout.buildDirectory.dir("shots").get().asFile
                test.systemProperty("rah.shots.dir", shots.absolutePath)
                test.outputs.dir(shots)
                // Every run draws every picture afresh, so one renamed or dropped never lingers.
                test.doFirst { shots.deleteRecursively() }
                test.systemProperty("robolectric.graphicsMode", "NATIVE")
                test.systemProperty("robolectric.pixelCopyRenderMode", "hardware")
                test.systemProperty("roborazzi.test.record", "true")
                test.maxHeapSize = "1536m"
                // -q hides the usual test logging; this keeps a line per test and per picture.
                test.testLogging {
                    quiet {
                        events("passed", "failed", "skipped", "standard_out")
                        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
                    }
                }
            }
        }
    }
}

// The screenshot helper hands tests Compose's ComposeUiTest, which is still marked experimental.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    if (name.endsWith("UnitTestKotlin")) compilerOptions.optIn.add("androidx.compose.ui.test.ExperimentalTestApi")
}

// No key, no release: better to stop here than to hand out an APK signed with something else.
gradle.taskGraph.whenReady {
    val releasing = allTasks.any { it.project == project && it.name.contains("Release") }
    if (releasing && !keystoreFile.exists()) {
        throw GradleException("A release build needs android/keystore.properties. See android/README.md.")
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    // ProcessLifecycleOwner: the live stream is open only while the app is visible.
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.kotlinx.coroutines.android)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)
    // The empty activity the screenshot tests draw into.
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
}
