plugins {
    id("cloudimage.provider")
}

dependencies {
    // Deliberately empty, and it must stay that way. A plugin payload's
    // runtime ABI is exactly provider:api + kotlin-stdlib +
    // kotlinx.serialization — the packages the app's R8 config keeps
    // unrenamed for its DexClassLoader (see the app repo's
    // proguard-rules.pro). Anything else compiles, passes JVM tests (where
    // the full classpath is present) and then dies on the release APK with
    // NoClassDefFoundError the moment R8 renames it: 1.0.0 shipped exactly
    // that bug, dexing a kotlinx-coroutines reference that no release build
    // could resolve. Tests may use testImplementation freely — test classes
    // never enter the payload.
}
