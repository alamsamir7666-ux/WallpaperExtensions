plugins {
    id("cloudimage.provider")
}

dependencies {
    // The default-dispatcher parse hop only: 250-KB listing pages belong
    // off the caller's (main) thread, and the suspend machinery needs the
    // coroutines core at compile time. d8 dexes against the runtime
    // classpath, so these classes stay out of the payload and resolve from
    // the host classloader at load time — the app ships the same coroutines
    // version (1.9.0) this compiles against.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
}
