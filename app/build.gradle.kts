import java.util.Properties
plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
val ksProps = Properties().apply { val f = rootProject.file("keystore/keystore.properties"); if (f.exists()) f.inputStream().use { load(it) } }
android {
    namespace = "tw.voicememo"
    compileSdk = 35
    defaultConfig { applicationId = "tw.voicememo"; minSdk = 26; targetSdk = 35; versionCode = 5; versionName = "1.3.0"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") } }
    signingConfigs {
        if (ksProps.isNotEmpty()) create("release") {
            storeFile = rootProject.file("keystore/" + ksProps["storeFile"]); storePassword = ksProps["storePassword"] as String
            keyAlias = ksProps["keyAlias"] as String; keyPassword = ksProps["keyPassword"] as String
        }
    }
    buildTypes { release { isMinifyEnabled = false; if (ksProps.isNotEmpty()) signingConfig = signingConfigs.getByName("release") } }
    androidResources { noCompress += listOf("onnx") }
    packaging { jniLibs { useLegacyPackaging = true } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = true; checkReleaseBuilds = true; disable += setOf("GradleDependency", "OldTargetApi", "SetTextI18n", "HardcodedText", "ViewConstructor", "ClickableViewAccessibility", "ObsoleteSdkInt", "AndroidGradlePluginVersion", "NewerVersionAvailable") }
    testOptions { unitTests.isIncludeAndroidResources = true; unitTests.all { it.systemProperty("previewDir", rootProject.file("previews").absolutePath) } }
}
dependencies {
    implementation("androidx.core:core-ktx:1.13.1"); implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation(files("libs/sherpa-onnx-1.13.8.aar"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
