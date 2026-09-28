plugins {
    id("cloudimage.provider")
}

dependencies {
    // Suspend machinery for the browser-paced fetch gate (Semaphore, Mutex,
    // delay) and the default-dispatcher parse hop. Compile-time only as far
    // as the package is concerned: d8 dexes against the runtime classpath,
    // so these classes stay out of the payload and resolve from the host
    // classloader at load time — the app ships the same coroutines version
    // (1.9.0) this compiles against.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
}
