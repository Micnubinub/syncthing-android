plugins {
    alias(libs.plugins.aboutLibraries) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.jetbrains.kotlin.serialization) apply false
}

tasks.register<Exec>("installSyncthing") {
    group = "build"
    description = "Clones the latest syncthing release sources and updates version info"
    workingDir = layout.projectDirectory.asFile
    commandLine(
        "go",
        "run",
        layout.projectDirectory.file("scripts/installSyncthing.go").asFile.absolutePath
    )
    // The script fetches the latest syncthing release and rewrites version info,
    // so it must run on every invocation. `inputs.file` still declares the script
    // for visibility, but we deliberately bypass up-to-date checks.
    inputs.file(layout.projectDirectory.file("scripts/installSyncthing.go"))
    outputs.upToDateWhen { false }
}

// No plugin is applied to the root project (they are all `apply false`), so AGP's
// default `clean` task does not exist here. Register one that removes the build dir
// plus the locally generated native/syncthing artifacts stored outside build dirs.
tasks.register<Delete>("clean") {
    group = "build"
    description = "Delete build outputs and locally generated native/syncthing artifacts"
    delete(
        layout.buildDirectory,
        layout.projectDirectory.dir("app/src/main/jniLibs"),
        layout.projectDirectory.dir("gobuild"),
        layout.projectDirectory.dir("go"),
    )
}