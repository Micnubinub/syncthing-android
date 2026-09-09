fun detectGoBinary(): String {
    val isWindows = System.getProperty("os.name").lowercase().contains("windows")
    val exe = if (isWindows) "go.exe" else "go"

    val candidates = buildList {
        // Respect GOROOT first
        System.getenv("GOROOT")?.let { add("$it/bin/$exe") }

        if (isWindows) {
            System.getenv("ProgramFiles")?.let { add("$it\\Go\\bin\\go.exe") }
            System.getenv("USERPROFILE")?.let { add("$it\\go\\bin\\go.exe") }
            add("C:\\Go\\bin\\go.exe")
        } else {
            val home = System.getenv("HOME").orEmpty()
            addAll(
                listOf(
                    "/usr/local/go/bin/go",
                    "/opt/homebrew/bin/go",
                    "/usr/local/bin/go",
                    "/usr/lib/go/bin/go",
                    "/usr/lib/golang/bin/go",
                    "$home/go/bin/go",
                    "$home/.go/bin/go",
                    "/snap/bin/go",
                )
            )
        }
    }

    return candidates.firstOrNull { File(it).isFile } ?: exe // fall back to PATH
}

abstract class BuildNativeTask @Inject constructor(
    private val execOps: ExecOperations,
) : DefaultTask() {

    @get:InputFiles
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val inputFiles: ConfigurableFileCollection

    @get:Internal
    abstract val workingDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Input
    abstract val ndkVersion: Property<String>

    @get:Input
    abstract val goBinary: Property<String>

    @get:Input
    abstract val debugBuild: Property<Boolean>

    @TaskAction
    fun run() {
        val workDir = workingDir.get().asFile
        val go = goBinary.get()
        requireGoAvailable(go)

        val srcDir = workDir.resolve("src")
        if (!srcDir.exists()) {
            logger.lifecycle("syncthing/src not found - running updateSyncthing.go to fetch it")
            val updateScript = workDir.resolve("../scripts/updateSyncthing.go")
            if (!updateScript.isFile) {
                throw GradleException(
                    "updateSyncthing.go not found at ${updateScript.absolutePath}"
                )
            }
            execOps.exec {
                workingDir(workDir)
                commandLine(go, "run", updateScript.absolutePath)
            }.assertNormalExitValue()
        } else {
            logger.lifecycle("syncthing/src found - skipping source fetch")
        }

        val extraEnv = buildMap {
            put("NDK_VERSION", ndkVersion.get())
            put("SYNCTHING_MODULE_DIR", workDir.absolutePath)
            put("SYNCTHING_DEBUG_BUILD", if (debugBuild.get()) "1" else "0")

            System.getProperty("http.proxyHost")?.let { host ->
                System.getProperty("http.proxyPort")?.let { port ->
                    put("HTTP_PROXY", "http://$host:$port")
                }
            }
            System.getProperty("https.proxyHost")?.let { host ->
                System.getProperty("https.proxyPort")?.let { port ->
                    put("HTTPS_PROXY", "http://$host:$port")
                }
            }
            System.getProperty("http.nonProxyHosts")?.let { put("NO_PROXY", it) }
            System.getenv("GOTMPDIR")?.let { put("TMP", it) }
        }

        val installScript = workDir.resolve("../scripts/installSyncthing.go")
        execOps.exec {
            environment(extraEnv) // merges with inherited env
            workingDir(workDir)
            commandLine(go, "run", installScript.absolutePath)
        }.assertNormalExitValue()

        execOps.exec {
            environment(extraEnv)
            workingDir(workDir)
            commandLine(go, "run", "../scripts/buildSyncthing.go")
        }.assertNormalExitValue()
    }

    private fun requireGoAvailable(binary: String) {
        val resolved = File(binary).isFile ||
                System.getenv("PATH").orEmpty()
                    .split(File.pathSeparator)
                    .any { File(it, binary).isFile }
        if (!resolved) {
            throw GradleException(
                "Go toolchain '$binary' was not found. Install Go " +
                        "(https://go.dev/dl/) or set GOROOT so it can be located."
            )
        }
    }
}

tasks.register<BuildNativeTask>("buildNative") {
    group = "build"
    description = "Builds native Syncthing binaries"
    inputFiles.from(
        layout.projectDirectory.dir("src"),
        layout.projectDirectory.file("../scripts/installSyncthing.go"),
        layout.projectDirectory.file("../scripts/updateSyncthing.go"),
        layout.projectDirectory.file("../scripts/buildSyncthing.go"),
    )
    workingDir.set(layout.projectDirectory)
    outputDir.set(layout.projectDirectory.dir("../app/src/main/jniLibs"))
    ndkVersion.set(libs.versions.ndk.version)
    goBinary.set(providers.provider { detectGoBinary() })
    debugBuild.set(
        providers.gradleProperty("syncthing.debugBuild")
            .map(String::toBoolean)
            .orElse(false)
    )
}

abstract class ReleaseTask @Inject constructor(
    private val execOps: ExecOperations,
) : DefaultTask() {

    @get:Internal
    abstract val workingDir: DirectoryProperty

    @get:Input
    abstract val goBinary: Property<String>

    init {
        // A release always runs; never up-to-date
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun run() {
        execOps.exec {
            workingDir(workingDir)
            commandLine(goBinary.get(), "run", "../scripts/release.go")
        }.assertNormalExitValue()
    }
}

tasks.register<ReleaseTask>("release") {
    group = "release"
    description = "Create and push a release tag"
    workingDir.set(layout.projectDirectory)
    goBinary.set(providers.provider { detectGoBinary() })
}