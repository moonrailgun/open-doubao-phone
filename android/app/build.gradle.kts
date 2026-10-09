plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.openodoubao.assistant"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.openodoubao.assistant"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true; buildConfig = true }
    sourceSets.getByName("main") {
        java.srcDir("../third_party/sherpa-onnx/recognition")
        assets.srcDir("../.voice/assistant-assets")
        jniLibs.srcDir("../.voice/assistant-jni")
    }
    androidResources { noCompress += listOf("onnx", "bin") }
    sourceSets.getByName("androidTest").assets.srcDir("../.voice/test-assets")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

tasks.named("preBuild") {
    doFirst {
        for (path in listOf("assistant-assets/voice/asr/model.int8.onnx", "assistant-assets/voice/silero_vad.onnx",
            "assistant-jni/arm64-v8a/libsherpa-onnx-jni.so", "assistant-jni/arm64-v8a/libonnxruntime.so")) {
            check(file("../.voice/$path").isFile) { "Missing $path; run python3 tools/prepare_voice.py from android/" }
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.05.01"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
