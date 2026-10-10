import java.util.Properties

plugins { `java-library` }

// Compile-only stubs for Android 17 system APIs missing from the public SDK; the device framework supplies the real classes.
val sdkDir = Properties().apply { rootProject.file("local.properties").takeIf { it.isFile }?.reader()?.use(::load) }
    .getProperty("sdk.dir") ?: System.getenv("ANDROID_HOME")

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies { compileOnly(files("$sdkDir/platforms/android-36/android.jar")) }
