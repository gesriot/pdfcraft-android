plugins {
    id("com.android.application") version "8.10.1" apply false
    id("org.jetbrains.kotlin.android") version "2.4.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
}

val buildRoot = providers.environmentVariable("PDFCRAFT_ANDROID_BUILD_DIR")
    .getOrElse(layout.projectDirectory.dir(".build").asFile.absolutePath)
layout.buildDirectory.set(file(buildRoot))
subprojects {
    layout.buildDirectory.set(rootProject.layout.buildDirectory.dir(name))
}
