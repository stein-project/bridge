// Copyright (c) 2026 Breno Xavier. All rights reserved.
// Proprietary software: see the LICENSE file at the root of this repository.
//
// The Stein Bridge build.
//
//   ./gradlew build           compiles the Bridge (build/libs/steinbridge.jar), patches the player's game jar
//                             (build/patched), checks coverage, formatting and the options list, and runs the tests
//   ./gradlew runClient       plays the patched game (isolated game folder in build/gamedir)
//   ./gradlew fetchMinecraft  downloads Minecraft 1.8.9 from Mojang into build/mc (then pass -Pstein.mcDir=build/mc)
//   ./gradlew spotlessApply   formats the Java code (google-java-format, AOSP style)
//
// The game comes from the local Minecraft installation (-Pstein.mcDir=..., default: the launcher's folder). Nothing
// derived from it is redistributed: the patched jar stays in build/, and the installer patches the player's own copy.
import java.io.OutputStream
import javax.inject.Inject
import java.security.MessageDigest
import java.util.Properties

plugins {
    java
    alias(libs.plugins.spotless)
}

/** File operations for task actions: Task.project is not available while a task runs. */
interface FileOps {
    @get:Inject val fs: FileSystemOperations
}
val fileOps = objects.newInstance<FileOps>().fs

group = "dev.xavier.stein"
version = Regex("""String VERSION = "([^"]+)"""")
    .find(file("src/main/java/dev/xavier/stein/bridge/SteinBridge.java").readText())!!.groupValues[1]

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

repositories {
    mavenCentral()
    exclusiveContent {
        // MCP 1.8.9 (names only, for compiling): the Forge maven is the one place that still serves it.
        forRepository {
            maven("https://maven.minecraftforge.net") {
                // The MCP zips are published without a pom.
                metadataSources { artifact() }
            }
        }
        filter { includeGroup("de.oceanlabs.mcp") }
    }
}

// ---- the game ----

val mcVersion = "1.8.9"
val os = org.gradle.internal.os.OperatingSystem.current()
val nativesClassifier = when {
    os.isWindows -> "natives-windows"
    os.isMacOsX -> if (System.getProperty("os.arch") == "aarch64") "natives-macos-arm64" else "natives-macos"
    else -> "natives-linux"
}

/** The Minecraft installation the game comes from: -Pstein.mcDir, MCDIR, or the launcher's usual folder. */
val mcDir: File = providers.gradleProperty("stein.mcDir").orElse(providers.environmentVariable("MCDIR")).map { file(it) }
    .getOrElse(
        when {
            os.isWindows -> File(System.getenv("APPDATA") ?: "", ".minecraft")
            os.isMacOsX -> File(System.getProperty("user.home"), "Library/Application Support/minecraft")
            else -> File(System.getProperty("user.home"), ".minecraft")
        }
    )
val gameJar = mcDir.resolve("versions/$mcVersion/$mcVersion.jar")
val gameJson = mcDir.resolve("versions/$mcVersion/$mcVersion.json")

/**
 * The game's libraries (Gson, Guava, Netty...) from its version manifest, without LWJGL 2: the files as the launcher
 * stores them. A library the Bridge patched (build/patched/<name>-steinbridge.jar; with dist, the copy patched with the
 * obfuscated build's rules, build/dist-work/patched/<name>-dist.jar) replaces the original.
 */
fun gameLibraries(patched: Boolean, dist: Boolean = false): List<File> {
    if (!gameJson.isFile) return emptyList()
    val names = Regex(""""name": *"([^"]+)"""").findAll(gameJson.readText()).map { it.groupValues[1] }.toSortedSet()
    // "name" is also an OS name inside the library rules ("osx"): only Maven coordinates count.
    return names.filter { it.count { c -> c == ':' } == 2 && !it.startsWith("org.lwjgl") }.mapNotNull { coord ->
        val (g, a, v) = coord.split(":")
        val replaced = if (dist) layout.buildDirectory.file("dist-work/patched/$a-dist.jar").get().asFile
            else layout.buildDirectory.file("patched/$a-steinbridge.jar").get().asFile
        val original = mcDir.resolve("libraries/${g.replace('.', '/')}/$a/$v/$a-$v.jar")
        when {
            patched && replaced.isFile -> replaced
            original.isFile -> original
            else -> null
        }
    }
}

// ---- dependencies ----

val lwjglModules = listOf("lwjgl", "lwjgl-glfw", "lwjgl-opengl", "lwjgl-openal", "lwjgl-stb", "lwjgl-vulkan")
val allNatives = listOf("natives-windows", "natives-linux", "natives-macos", "natives-macos-arm64")

val lwjgl: Configuration = configurations.create("lwjgl") { isTransitive = false }
val lwjglNatives: Configuration = configurations.create("lwjglNatives") { isTransitive = false }
val asm: Configuration = configurations.create("asm")
val shaderc: Configuration = configurations.create("shaderc") { isTransitive = false }
val mcp: Configuration = configurations.create("mcp") { isTransitive = false }
val specialSource: Configuration = configurations.create("specialSource") { isTransitive = false }
val sdkTools: Configuration = configurations.create("sdkTools")

dependencies {
    val v = libs.versions.lwjgl.get()
    for (m in lwjglModules) {
        lwjgl("org.lwjgl:$m:$v")
        // LWJGL's Vulkan only has natives on macOS (MoltenVK); elsewhere the driver ships the library.
        for (n in if (m == "lwjgl-vulkan") listOf("natives-macos", "natives-macos-arm64") else allNatives) {
            lwjglNatives("org.lwjgl:$m:$v:$n")
        }
    }
    asm(libs.asm)
    asm(libs.asm.commons)
    asm(libs.asm.tree)
    asm(libs.asm.analysis)
    // The shader compiler (shaderc: GLSL -> SPIR-V; SPIRV-Cross: SPIR-V -> OpenGL GLSL), for the build only. The
    // natives of every system are declared so the checksums of all of them are in verification-metadata.xml.
    for (m in listOf("lwjgl", "lwjgl-shaderc", "lwjgl-spvc")) {
        shaderc("org.lwjgl:$m:$v")
        for (n in allNatives) shaderc("org.lwjgl:$m:$v:$n")
    }
    mcp(variantOf(libs.mcp.srg) { classifier("srg"); artifactType("zip") })
    mcp(variantOf(libs.mcp.stable) { artifactType("zip") })
    specialSource(variantOf(libs.special.source) { classifier("shaded") })
    sdkTools("dev.xavier.stein:stein-sdk")
}

// ---- source sets ----

val toolsSet = sourceSets.create("tools") {
    java.setSrcDirs(listOf("tools"))
    resources.setSrcDirs(emptyList<File>())
    java.include("*.java")
    compileClasspath += asm + lwjgl + shaderc
    runtimeClasspath += asm
}
val patcherSet = sourceSets.create("patcher") {
    java.setSrcDirs(listOf("patcher"))
    resources.setSrcDirs(emptyList<File>())
    java.include("*.java")
    compileClasspath += asm
    runtimeClasspath += asm
}
// The game entry point: Java 8 on purpose, to explain a missing Java 25 instead of crashing with
// UnsupportedClassVersionError (dev.xavier.stein.bridge.launch.Main).
val launchSet = sourceSets.create("launch") {
    java.setSrcDirs(listOf("src/launch/java"))
    resources.setSrcDirs(emptyList<File>())
}
// The visual options: game screens and models, compiled with the game's MCP/SRG names like a mod.
val lensSet = sourceSets.create("lens") {
    java.setSrcDirs(listOf("src/lens/java"))
    resources.setSrcDirs(listOf("src/lens/resources"))
}
sourceSets.main {
    java.setSrcDirs(listOf("src/main/java"))
    resources.setSrcDirs(emptyList<File>())
}
sourceSets.test {
    java.setSrcDirs(listOf("test"))
    resources.setSrcDirs(emptyList<File>())
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-nowarn", "-proc:none"))
    // What javac writes without -g: file names and line numbers.
    options.debugOptions.debugLevel = "source,lines"
}
tasks.named<JavaCompile>("compileLaunchJava") { options.release = 8 }

// ---- MCP names ----

val work = layout.buildDirectory.dir("work")
val mcpDir = layout.buildDirectory.dir("tools")
val srgFile = mcpDir.map { it.file("joined-1.8.9.srg") }
val mcpCsv = mcpDir.map { it.dir("mcp-stable-22") }

val extractMcp = tasks.register<Sync>("extractMcp") {
    description = "Extracts the MCP 1.8.9 names (joined.srg, fields.csv, methods.csv) into build/tools."
    into(mcpDir)
    from(mcp.elements.map { files -> files.first { it.asFile.name.contains("srg") }.let { zipTree(it) } }) {
        include("joined.srg")
        rename { "joined-1.8.9.srg" }
    }
    from(mcp.elements.map { files -> files.first { it.asFile.name.contains("stable") }.let { zipTree(it) } }) {
        into("mcp-stable-22")
    }
    preserve { include("**") }
}

fun requireGame() = check(gameJar.isFile && gameJson.isFile) {
    "Minecraft $mcVersion not found in $mcDir: install it with the launcher, pass -Pstein.mcDir=..., " +
        "or run ./gradlew fetchMinecraft and pass -Pstein.mcDir=build/mc"
}

val namedGame = tasks.register<JavaExec>("namedGame") {
    description = "The game jar with MCP's readable names, only for compiling (build/work/mc-named.jar)."
    dependsOn(extractMcp)
    val out = work.map { it.file("mc-named.jar") }
    inputs.file(gameJar)
    outputs.file(out)
    classpath = sdkTools
    mainClass = "dev.xavier.stein.sdk.NamedBuild"
    argumentProviders.add {
        listOf("game", gameJar.path, srgFile.get().asFile.path, mcpCsv.get().file("fields.csv").asFile.path,
            mcpCsv.get().file("methods.csv").asFile.path, out.get().asFile.path)
    }
    doFirst { requireGame() }
}

// Every member public, protected methods too (--all: the Bridge calls the game, never overrides it), only so javac
// accepts what the game keeps closed (AccessRewrite binds those at run time).
val openGame = tasks.register<JavaExec>("openGame") {
    description = "The readable-name game jar with every member opened, only for compiling."
    val input = namedGame.map { it.outputs.files.singleFile }
    val out = work.map { it.file("mc-named-open.jar") }
    inputs.files(input)
    outputs.file(out)
    classpath = sdkTools
    mainClass = "dev.xavier.stein.sdk.OpenJar"
    argumentProviders.add { listOf(input.get().path, out.get().asFile.path, "--all") }
    standardOutput = OutputStream.nullOutputStream()
}

val mcSrg = tasks.register<JavaExec>("mcSrg") {
    description = "The game jar with MCP/SRG names (SpecialSource), for compiling the visual options."
    dependsOn(extractMcp)
    val out = work.map { it.file("mc-srg.jar") }
    inputs.file(gameJar)
    outputs.file(out)
    classpath = specialSource
    mainClass = "net.md_5.specialsource.SpecialSource"
    argumentProviders.add {
        listOf("--in-jar", gameJar.path, "--out-jar", out.get().asFile.path, "--srg-in", srgFile.get().asFile.path,
            "--quiet")
    }
    doFirst { requireGame() }
}

// ---- the Bridge: javac against the named game, NamedBuild tosrg, ModRemap, AccessRewrite ----

dependencies {
    compileOnly(files(openGame.map { it.outputs.files.singleFile }))
    compileOnly(files(lwjgl))
    compileOnly(files(provider { gameLibraries(patched = false) }))
    "lensCompileOnly"(files(mcSrg.map { it.outputs.files.singleFile }))
    "lensCompileOnly"(files(lwjgl))
    "lensCompileOnly"(files(provider { gameLibraries(patched = false) }))
    testImplementation(files(lwjgl))
    // The GPU tests open a window: LWJGL needs its natives at run time.
    testRuntimeOnly(files(lwjglNatives))
}

val toSrg = tasks.register<JavaExec>("toSrg") {
    description = "Takes the compiled Bridge from MCP's readable names to the SRG names."
    val named = tasks.compileJava.flatMap { it.destinationDirectory }
    val out = work.map { it.dir("srg") }
    inputs.dir(named)
    inputs.files(namedGame)
    outputs.dir(out)
    classpath = sdkTools
    mainClass = "dev.xavier.stein.sdk.NamedBuild"
    argumentProviders.add {
        listOf("tosrg", srgFile.get().asFile.path, mcpCsv.get().file("fields.csv").asFile.path,
            mcpCsv.get().file("methods.csv").asFile.path, namedGame.get().outputs.files.singleFile.path,
            named.get().asFile.path, out.get().asFile.path)
    }
    standardOutput = OutputStream.nullOutputStream()
    doFirst { out.get().asFile.deleteRecursively() }
}

dependencies { "lensCompileOnly"(files(toSrg.map { it.outputs.files.singleFile })) }

/** ModRemap: SRG names -> the game's obfuscated names, checking that every reference to the game resolves. */
fun remap(name: String, input: Provider<File>, out: Provider<RegularFile>, vararg deps: Any) =
    tasks.register<JavaExec>(name) {
        dependsOn(*deps, extractMcp)
        inputs.files(input)
        outputs.file(out)
        classpath = toolsSet.runtimeClasspath
        mainClass = "ModRemap"
        argumentProviders.add {
            listOf(srgFile.get().asFile.path, out.get().asFile.path, "--check", gameJar.path, input.get().path)
        }
    }

val remapBridge = remap("remapBridge", toSrg.map { it.outputs.files.singleFile }, work.map { it.file("obf.jar") }, toSrg)

// What the real game keeps closed becomes invokedynamic to core.GameAccess.
val accessRewrite = tasks.register<JavaExec>("accessRewrite") {
    description = "Binds the game members the real game keeps closed through core.GameAccess."
    val obf = work.map { it.dir("obf") }
    val out = work.map { it.dir("access") }
    inputs.files(remapBridge)
    outputs.dir(out)
    classpath = toolsSet.runtimeClasspath
    mainClass = "AccessRewrite"
    argumentProviders.add { listOf(gameJar.path, obf.get().asFile.path, out.get().asFile.path) }
    doFirst {
        fileOps.delete { delete(obf, out) }
        fileOps.copy {
            from(zipTree(remapBridge.get().outputs.files.singleFile))
            into(obf)
        }
    }
}

// Packet names for the Stein log (engine/PacketLog): without them the network summary shows "gv$b" instead of
// "S17PacketEntityLookMove".
val packetNames = tasks.register("packetNames") {
    description = "Writes the network packet names from MCP's joined.srg."
    dependsOn(extractMcp)
    val out = work.map { it.file("packets/stein-packets.txt") }
    inputs.file(srgFile)
    outputs.file(out)
    doLast {
        val lines = srgFile.get().asFile.readLines().filter { it.startsWith("CL: ") }.mapNotNull { line ->
            val f = line.split(" ")
            if (!f[2].startsWith("net/minecraft/network/")) return@mapNotNull null
            val n = f[2].substringAfterLast('/').substringAfterLast('$')
            if (n.all { it.isDigit() }) null else "${f[1]}\t$n"
        }
        out.get().asFile.writeText(lines.joinToString("") { "$it\n" })
    }
}

// The shaders: one source (src/main/shaders, Vulkan-style GLSL) -> SPIR-V -> OpenGL GLSL, into the Bridge jar.
val shaders = tasks.register<JavaExec>("shaders") {
    description = "Compiles the Stein GFX shaders (src/main/shaders) to SPIR-V and OpenGL GLSL."
    val out = work.map { it.dir("shaders/stein/shaders") }
    inputs.dir("src/main/shaders")
    outputs.dir(out)
    classpath = toolsSet.runtimeClasspath + lwjgl + shaderc
    mainClass = "ShaderBuild"
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    argumentProviders.add { listOf(file("src/main/shaders").path, out.get().asFile.path) }
}

// The visual options go through ModRemap and the Patcher (LWJGL 3, matrix stack) into the Bridge jar.
val lensResources = tasks.named<ProcessResources>("processLensResources")
val lensOutput = tasks.register<Sync>("lensOutput") {
    from(tasks.named<JavaCompile>("compileLensJava").flatMap { it.destinationDirectory }, lensResources)
    into(work.map { it.dir("lens/classes") })
}
val remapLens = remap("remapLens", lensOutput.map { it.destinationDir }, work.map { it.file("lens/obf.jar") },
    lensOutput)
val patchLens = tasks.register<JavaExec>("patchLens") {
    description = "Patches the visual options with the game rules (LWJGL 3, matrix stack)."
    val out = work.map { it.file("lens/lens.jar") }
    inputs.files(remapLens)
    inputs.files(fileTree("patcher") { include("*.tsv") }, "scan/name-mapping.txt")
    outputs.file(out)
    classpath = patcherSet.runtimeClasspath
    mainClass = "Patcher"
    systemProperty("patcher.lenient", "true")
    argumentProviders.add {
        listOf(remapLens.get().outputs.files.singleFile.path, out.get().asFile.path, file("scan/name-mapping.txt").path,
            file("patcher/redirects-mc.tsv").path, file("patcher/redirects-opt.tsv").path,
            file("patcher/redirects-modgl.tsv").path)
    }
    standardOutput = OutputStream.nullOutputStream()
}

/** Everything that goes into the Bridge jar, also as a folder (the coverage check and preflight read classes). */
val bridgeClasses = tasks.register<Sync>("bridgeClasses") {
    description = "Assembles the Bridge classes and resources in build/bridge/classes."
    into(layout.buildDirectory.dir("bridge/classes"))
    from(accessRewrite)
    from(tasks.named<JavaCompile>("compileLaunchJava").flatMap { it.destinationDirectory })
    from(packetNames)
    from(shaders) { into("stein/shaders") }
    from(patchLens.map { zipTree(it.outputs.files.singleFile) }) { exclude("META-INF/MANIFEST.MF") }
    duplicatesStrategy = DuplicatesStrategy.FAIL
}

// The java plugin's own jar would hold the classes as compiled (readable names): the Bridge jar is this one.
tasks.jar { enabled = false }
val bridgeJar = tasks.register<Jar>("bridgeJar") {
    group = "build"
    description = "Builds build/libs/steinbridge.jar."
    destinationDirectory = layout.buildDirectory.dir("libs")
    from(bridgeClasses)
    // The licence and the third-party notices travel with the jar (LWJGL 2 code and LWJGL 3 are in it or with it).
    from(files("LICENSE", "NOTICE")) { into("META-INF") }
    from(fileTree("licenses")) { into("META-INF/licenses") }
    archiveFileName = "steinbridge.jar"
    // Same bytes for the same sources: no timestamps, fixed order.
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

// ---- the patched game ----

val patchedDir = layout.buildDirectory.dir("patched")
val paulscode = mcDir.resolve("libraries/com/paulscode/librarylwjglopenal/20100824/librarylwjglopenal-20100824.jar")

/**
 * Derived copies of the jars that call LWJGL 2, with the call sites rewritten to LWJGL 3 and the engine's hooks: the
 * game jar and the paulscode sound library. The inputs are never modified.
 */
val patchGame = tasks.register<JavaExec>("patchGame") {
    group = "build"
    description = "Patches the player's game jar into build/patched/1.8.9-steinbridge.jar."
    val out = patchedDir.map { it.file("$mcVersion-steinbridge.jar") }
    inputs.file(gameJar)
    inputs.files(fileTree("patcher") { include("*.tsv") }, "scan/name-mapping.txt")
    outputs.file(out)
    classpath = patcherSet.runtimeClasspath
    mainClass = "Patcher"
    argumentProviders.add {
        listOf(gameJar.path, out.get().asFile.path, "scan/name-mapping.txt", "patcher/redirects-mc.tsv",
            "patcher/redirects-opt.tsv", "patcher/redirects-batch.tsv", "patcher/redirects-mods.tsv")
    }
    doFirst { requireGame() }
}
val patchSound = tasks.register<JavaExec>("patchSound") {
    description = "Patches the paulscode sound library (it calls LWJGL 2 directly)."
    val out = patchedDir.map { it.file("librarylwjglopenal-steinbridge.jar") }
    inputs.file(paulscode)
    inputs.file("patcher/redirects-al.tsv")
    outputs.file(out)
    onlyIf("the sound library is installed") { paulscode.isFile }
    classpath = patcherSet.runtimeClasspath
    mainClass = "Patcher"
    argumentProviders.add { listOf(paulscode.path, out.get().asFile.path, "patcher/redirects-al.tsv") }
}

// Every detoured call must exist, with the exact descriptor, in the Bridge classes or in LWJGL 3: a wrong descriptor
// would only show up in the game, as NoSuchMethodError.
val coverage = tasks.register<JavaExec>("coverage") {
    group = "verification"
    description = "Checks that every LWJGL/Stein reference of the patched game is served."
    inputs.files(patchGame, bridgeClasses)
    outputs.upToDateWhen { true }
    classpath = toolsSet.runtimeClasspath
    mainClass = "CoverageCheck"
    argumentProviders.add {
        listOf(patchGame.get().outputs.files.singleFile.path, file("scan/lwjgl3-index.txt").path,
            bridgeClasses.get().destinationDir.path)
    }
}

/** The classpath the game runs with: the Bridge, the patched game, its libraries and LWJGL 3. */
fun gameClasspath(): FileCollection = files(
    bridgeJar,
    patchGame,
    provider { gameLibraries(patched = true) },
    lwjgl,
    lwjglNatives,
).builtBy(patchSound)

// The static initializers of the optimizations look up game members; a wrong name or visibility would only show up
// inside the game, as ExceptionInInitializerError. Here each class is initialized against the patched jar. On a
// machine without OpenGL (a CI runner) the check is skipped.
val preflight = tasks.register<JavaExec>("preflight") {
    group = "verification"
    description = "Initializes every Bridge class against the patched game."
    inputs.files(gameClasspath())
    outputs.upToDateWhen { true }
    classpath = gameClasspath() + sourceSets.test.get().output
    mainClass = "Preflight"
    argumentProviders.add { listOf(bridgeClasses.get().destinationDir.path) }
}

val sanityTest = tasks.register<JavaExec>("sanityTest") {
    group = "verification"
    description = "Checks the LWJGL 2 compatibility math (matrices, vectors, GLU) against known values."
    classpath = sourceSets.test.get().runtimeClasspath + files(bridgeJar)
    mainClass = "SanityTest"
}

dependencies { testCompileOnly(files(bridgeJar)) }

// docs/OPTIONS.md: every -D option, from the Javadoc of the *Options classes (tools/OptionsDoc.java).
tasks.register<JavaExec>("options") {
    group = "documentation"
    description = "Writes docs/OPTIONS.md from the *Options classes."
    classpath = toolsSet.runtimeClasspath
    mainClass = "OptionsDoc"
    args(projectDir.path)
}
val optionsCheck = tasks.register<JavaExec>("optionsCheck") {
    group = "verification"
    description = "Checks that docs/OPTIONS.md lists every -D option (run ./gradlew options to update it)."
    inputs.files(fileTree("src") { include("**/*Options.java") }, "docs/OPTIONS.md")
    outputs.upToDateWhen { true }
    classpath = toolsSet.runtimeClasspath
    mainClass = "OptionsDoc"
    args(projectDir.path, "--check")
}


// ---- GPU tests and benches (need a graphics card and a window: not part of ./gradlew build) ----

/**
 * A standalone program of the test source set run against the Bridge jar and LWJGL 3 with its natives. Options (-P):
 *   stein.args       arguments of the program, e.g. "fence3"
 *   stein.jvmArgs    extra JVM flags, e.g. "-Dsteinbridge.gfx.recordFrame=false"
 */
fun gpuProgram(name: String, main: String, what: String, vararg props: Pair<String, String>) =
    tasks.register<JavaExec>(name) {
        group = "gpu"
        description = what
        javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
        classpath = sourceSets.test.get().output + files(bridgeJar) + lwjgl + lwjglNatives
        mainClass = main
        jvmArgs("--enable-native-access=ALL-UNNAMED")
        for ((k, v) in props) systemProperty(k, v)
        providers.gradleProperty("stein.jvmArgs").orNull?.let { jvmArgs(it.split(" ").filter(String::isNotBlank)) }
        providers.gradleProperty("stein.args").orNull?.let { args(it.split(" ").filter(String::isNotBlank)) }
    }

gpuProgram("gpuFrameTest", "dev.xavier.stein.gfx.gl.SteinGlFrameTest",
    "Stein GFX API tests and the recorded frame, in a hidden OpenGL 4.6 core window.")
gpuProgram("gpuTimingTest", "dev.xavier.stein.gfx.gl.SteinGlGpuTimingTest",
    "GPU pass timing (OpenGL timestamps) with fake passes, in a hidden OpenGL 4.6 core window.",
    "steinbridge.gfx.gpuPassTiming" to "true")
gpuProgram("gpuFenceBench", "dev.xavier.stein.gfx.gl.SteinGlFenceBench",
    "Benchmark of the gap a fence leaves before a pass (-Pstein.args=\"fence3\", see the class).")
gpuProgram("vkSelfTest", "dev.xavier.stein.gfx.vk.SteinVkSelfTest",
    "The Stein GFX API tests on the experimental Vulkan backend, offscreen.")

tasks.assemble { dependsOn(bridgeJar, patchGame, patchSound, gameRuntime) }
tasks.check { dependsOn(coverage, preflight, sanityTest, optionsCheck) }

// ---- running the game ----

/** The game's own non-LWJGL natives (jinput), for this system; LWJGL 3 extracts its own. */
val natives = tasks.register<Sync>("natives") {
    description = "Extracts the game's non-LWJGL natives into build/natives."
    into(layout.buildDirectory.dir("natives"))
    from(provider {
        mcDir.resolve("libraries").walkTopDown()
            .filter { it.name.endsWith(".jar") && it.name.contains(nativesClassifier) && !it.path.contains("lwjgl") }
            .map { zipTree(it) }.toList()
    }) {
        exclude("META-INF/**", "*.txt")
    }
    // Several game versions ship the same native: the last one wins, as an unzip over the folder would.
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
}

/**
 * build/run/game.properties: what it takes to start the patched game (the classpath, the natives and the assets
 * folder). The loader build starts the game with it, adding the Loader and the mods.
 */
val gameRuntime = tasks.register("gameRuntime") {
    group = "build"
    description = "Writes build/run/game.properties, the patched game's classpath, for the loader build."
    dependsOn(natives)
    val out = layout.buildDirectory.file("run/game.properties")
    inputs.files(gameClasspath())
    outputs.file(out)
    doLast {
        val p = Properties()
        p["mcDir"] = mcDir.path
        p["gameJar"] = gameJar.path
        p["assets"] = mcDir.resolve("assets").path
        p["natives"] = natives.get().destinationDir.path
        p["classpath"] = gameClasspath().files.joinToString(File.pathSeparator) { it.path }
        out.get().asFile.writer().use { p.store(it, "Stein Bridge: the patched game (written by ./gradlew gameRuntime)") }
    }
}

/** A stream that writes to two others: the console and the run log. */
class Tee(private val a: OutputStream, private val b: OutputStream) : OutputStream() {
    override fun write(c: Int) { a.write(c); b.write(c) }
    override fun write(buf: ByteArray, off: Int, len: Int) { a.write(buf, off, len); b.write(buf, off, len) }
    override fun flush() { a.flush(); b.flush() }
}

/**
 * Plays the patched game on Java 25 + LWJGL 3, in an isolated game folder (build/gamedir) so the user's real
 * installation is untouched; the assets are read from it. Options (-P):
 *   stein.gameDir, stein.runLog   where the game folder and the log go
 *   stein.jvmArgs                 extra JVM flags, e.g. "-Dsteinbridge.gfx=false"
 *   stein.user, stein.mem         player name (default SteinBridge) and maximum heap in GB (default 2)
 * The loader repository has its own runClient, with the Loader and the mods.
 */
tasks.register<JavaExec>("runClient") {
    group = "application"
    description = "Plays the patched game."
    dependsOn(natives)
    val gameDir = file(providers.gradleProperty("stein.gameDir").getOrElse("build/gamedir"))
    val runLog = file(providers.gradleProperty("stein.runLog").getOrElse("build/run.log"))
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
    classpath = gameClasspath()
    mainClass = "dev.xavier.stein.bridge.launch.Main"
    workingDir = gameDir
    // GC and memory as the installer delivers them to the player.
    val mem = providers.gradleProperty("stein.mem").getOrElse("2")
    jvmArgs("-Xmx${mem}G", "-Xms256M", "-XX:SoftMaxHeapSize=768M", "-XX:+UseZGC", "-XX:-ZProactive",
        "--enable-native-access=ALL-UNNAMED",
        "-Djava.library.path=${layout.buildDirectory.dir("natives").get().asFile.path}")
    providers.gradleProperty("stein.jvmArgs").orNull?.let { jvmArgs(it.split(" ").filter(String::isNotBlank)) }
    args("--username", providers.gradleProperty("stein.user").getOrElse("SteinBridge"), "--version", mcVersion,
        "--gameDir", gameDir.path, "--assetsDir", mcDir.resolve("assets").path, "--assetIndex", "1.8",
        "--uuid", "00000000000000000000000000000000", "--accessToken", "0", "--userProperties", "{}",
        "--userType", "legacy")
    doFirst {
        requireGame()
        gameDir.mkdirs()
        runLog.parentFile.mkdirs()
        val log = runLog.outputStream()
        standardOutput = Tee(System.out, log)
        errorOutput = Tee(System.err, log)
    }
}

// ---- the obfuscated build: what reaches players ----
//
// The Bridge is proprietary and reaches players only as a jar, through the installer: that jar is obfuscated.
//   ./gradlew dist   build/dist/steinbridge.jar   the Bridge renamed by ProGuard (rename only: no shrinking, no
//                                                 optimization) with the -Dsteinbridge.* names as codes
//                    build/dist/rules/            the patcher rules with the obfuscated names and no comments, for
//                                                 the installer (and, inside the Loader, for ModPatch)
//                    build/dist-mapping/          the ProGuard mapping (steinbridge.txt), the option-name codes
//                                                 (codes.txt) and the ProGuard configuration. NEVER distributed: it
//                                                 is what turns a player's stack trace back into names (retrace).
//                    build/dist-work/             the game patched with the obfuscated rules and its classpath
//                                                 (run/game.properties), for the Loader's runClient/verify -Pstein.dist
// The Loader's dist obfuscates the Loader on top of this mapping (-applymapping), so the two jars go together.

val proguard: Configuration = configurations.create("proguard")
dependencies { proguard(libs.proguard) }

val distDir = layout.buildDirectory.dir("dist")
val distMapping = layout.buildDirectory.dir("dist-mapping")
val distWork = layout.buildDirectory.dir("dist-work")

/**
 * The options of every ProGuard run of the Bridge and the Loader. Rename only: the MethodHandle.invokeExact calls depend
 * on the exact descriptors, and code only the game calls (through the patcher rules) would look dead to the shrinker.
 */
val proguardCommon = """
    -dontshrink
    -dontoptimize
    -dontnote
    # No a.class and A.class in the same package: the jar would still be valid for the JVM, but unpacked on a
    # case-insensitive file system (Windows, macOS) half of the classes would be lost.
    -dontusemixedcaseclassnames
    # invokeExact has a polymorphic signature: the descriptor of each call does not exist in the JDK.
    -dontwarn java.lang.invoke.MethodHandle
    -dontwarn java.lang.invoke.VarHandle
    # The game's classes (obfuscated names, one to three letters, no package) with fields, parameters or a superclass of
    # the org.lwjgl compatibility layer (Vector3f, Matrix4f): ProGuard warns that a library depends on a program class,
    # harmless here because the whole layer keeps its names.
    -dontwarn ?,??,???,?${'$'}*,??${'$'}*,???${'$'}*
    # NestHost/NestMembers: since Java 11 javac writes no bridge for private members between a class and its nested
    # classes; without the attributes, that access becomes IllegalAccessError. SourceFile and LineNumberTable keep the
    # line numbers of a stack trace (retrace).
    -keepattributes Signature,InnerClasses,EnclosingMethod,NestHost,NestMembers,*Annotation*,Exceptions,SourceFile,LineNumberTable
    -renamesourcefileattribute SourceFile
    -keepclassmembers enum * { public static **[] values(); public static ** valueOf(java.lang.String); }
    # ServiceLoader (HookSink, Host): the contents follow the renamed implementations; the file names are the
    # interfaces', which keep their names.
    -adaptresourcefilecontents META-INF/services/**
""".trimIndent()

/**
 * What keeps its name in the Bridge: what is called by a name written outside this jar (the game's launcher profile,
 * the jars of mods, the Loader's diagnostics). Everything else (the engine, Stein GFX, the options) becomes a, b, c...;
 * the patcher rules the installer applies are translated through the mapping (tools/MapRules.java).
 */
val bridgeKeeps = """
    # The LWJGL 2 compatibility layer: the game calls the LWJGL 2 API by name.
    -keep class org.lwjgl.** { *; }
    # The game's entry point, in the launcher profile the installer writes.
    -keep class dev.xavier.stein.bridge.launch.Main { public static void main(java.lang.String[]); }
    # Found through ServiceLoader by its name (the Loader and the visual options implement it).
    -keepnames interface dev.xavier.stein.bridge.hooks.HookSink
    # Called by name from the bytecode of mods: the batch hooks (ModelBatch) and the matrix stack (GLMatrix) were written
    # into published mods at build time.
    -keep class dev.xavier.stein.bridge.engine.ModelBatch { public static *; }
    -keep class dev.xavier.stein.bridge.engine.GLMatrix { public static *; }
    # Implemented by a lambda in the Loader (InventoryOps): ProGuard keeps the method name of an interface a lambda
    # implements, so the Loader's run (on top of this mapping) needs the same name here. A new case shows up in the
    # Loader's dist as "is not being kept as ..., but remapped": add the interface here.
    -keepclassmembernames interface dev.xavier.stein.bridge.hooks.GameHooks${'$'}ChestScreenFilter { <methods>; }
    # Stack frames recognised by class name in the GL statistics and audit (gfx/gl/SteinGlFrameStats, SteinGlAudit).
    -keepnames class dev.xavier.stein.gfx.SteinGlTrack*
    -keepnames class dev.xavier.stein.gfx.SteinLazyGl*
    -keepnames class dev.xavier.stein.gfx.gl.SteinGlAudit*
    -keepnames class dev.xavier.stein.gfx.gl.SteinCoreGl*
""".trimIndent()

/** A path for a ProGuard configuration file: forward slashes, quoted. */
fun proguardPath(f: File) = "'" + f.absolutePath.replace('\\', '/') + "'"

/** The libraries the Bridge is obfuscated against: the patched game, its libraries and LWJGL 3 (and the JDK). */
fun distLibraries(): FileCollection = files(patchGame, provider { gameLibraries(patched = true) }, lwjgl)

// The JDK as ProGuard reads it: not every JDK 25 ships jmods (JEP 493), so the classes come from the runtime image.
val jdkClasses = tasks.register<JavaExec>("jdkClasses") {
    description = "Writes the classes of the JDK 25 into build/dist-work/jdk-classes.jar, for ProGuard."
    val out = distWork.map { it.file("jdk-classes.jar") }
    inputs.property("jdk", javaLauncher.map { it.metadata.javaRuntimeVersion })
    outputs.file(out)
    classpath = toolsSet.runtimeClasspath
    mainClass = "JdkClasses"
    argumentProviders.add { listOf(out.get().asFile.path) }
}

val distProguard = tasks.register<JavaExec>("distProguard") {
    description = "Renames the Bridge jar with ProGuard (build/dist-work/proguard, mapping in build/dist-mapping)."
    dependsOn(patchSound)
    val input = bridgeJar.flatMap { it.archiveFile }
    val out = distWork.map { it.file("proguard/steinbridge.jar") }
    val mapping = distMapping.map { it.file("steinbridge.txt") }
    val config = distMapping.map { it.file("steinbridge.pro") }
    val keep = distMapping.map { it.file("steinbridge-keep.pro") }
    val libraries = distLibraries()
    inputs.file(input)
    inputs.files(jdkClasses, libraries)
    inputs.property("rules", proguardCommon + bridgeKeeps)
    outputs.files(out, mapping, config, keep)
    classpath = proguard
    mainClass = "proguard.ProGuard"
    argumentProviders.add { listOf("@" + config.get().asFile.path) }
    doFirst {
        out.get().asFile.delete()
        // The Bridge's options, also read by the Loader's run (-include): the same keeps over the same mapping.
        keep.get().asFile.apply { parentFile.mkdirs() }.writeText(proguardCommon + "\n" + bridgeKeeps + "\n")
        config.get().asFile.writeText((listOf(
            "-injars ${proguardPath(input.get().asFile)}",
            "-outjars ${proguardPath(out.get().asFile)}",
            "-libraryjars ${proguardPath(jdkClasses.get().outputs.files.singleFile)}",
        ) + libraries.files.map { "-libraryjars ${proguardPath(it)}" } + listOf(
            "-include ${proguardPath(keep.get().asFile)}",
            "-printmapping ${proguardPath(mapping.get().asFile)}",
        )).joinToString("\n", postfix = "\n"))
    }
}

// The -Dsteinbridge.* names become codes (tools/Codes.java); the table goes to build/dist-mapping/codes.txt.
val distJar = tasks.register<JavaExec>("distJar") {
    group = "distribution"
    description = "Builds build/dist/steinbridge.jar: the obfuscated Bridge, with the option names as codes."
    val input = distWork.map { it.file("proguard/steinbridge.jar") }
    val out = distDir.map { it.file("steinbridge.jar") }
    val codes = distMapping.map { it.file("codes.txt") }
    inputs.files(distProguard)
    outputs.files(out, codes)
    classpath = toolsSet.runtimeClasspath
    mainClass = "Codes"
    argumentProviders.add { listOf(codes.get().asFile.path, out.get().asFile.path) }
    doFirst { input.get().asFile.copyTo(out.get().asFile, overwrite = true) }
}

// The patcher rules with the obfuscated names and no comments (tools/MapRules.java): what the installer takes to the
// player's machine, and what the Loader's dist puts inside the Loader for ModPatch.
val distRules = tasks.register<JavaExec>("distRules") {
    group = "distribution"
    description = "Writes build/dist/rules: the patcher rules with the obfuscated names, without comments."
    val out = distDir.map { it.dir("rules") }
    val mapping = distMapping.map { it.file("steinbridge.txt") }
    val tables = fileTree("patcher") { include("*.tsv") }
    inputs.files(distProguard, tables, "scan/name-mapping.txt")
    outputs.dir(out)
    classpath = toolsSet.runtimeClasspath
    mainClass = "MapRules"
    argumentProviders.add {
        listOf(mapping.get().asFile.path, out.get().asFile.path) + tables.files.sortedBy { it.name }.map { it.path }
    }
    doFirst { fileOps.delete { delete(out) } }
    // The LWJGL 2 -> 3 renames: public, and they say nothing about the engine.
    doLast { file("scan/name-mapping.txt").copyTo(out.get().asFile.resolve("name-mapping.txt"), overwrite = true) }
}

/** The game patched with the obfuscated rules, as the installer patches it on the player's machine. */
fun distPatch(name: String, input: File, output: String, vararg rules: String) = tasks.register<JavaExec>(name) {
    description = "Patches ${input.name} with the obfuscated build's rules (build/dist-work/patched/$output)."
    val out = distWork.map { it.file("patched/$output") }
    inputs.file(input)
    inputs.files(distRules)
    outputs.file(out)
    onlyIf("${input.name} is installed") { input.isFile }
    classpath = patcherSet.runtimeClasspath
    mainClass = "Patcher"
    argumentProviders.add {
        listOf(input.path, out.get().asFile.path) + rules.map { distDir.get().file("rules/$it").asFile.path }
    }
    doFirst { requireGame() }
}
val distPatchGame = distPatch("distPatchGame", gameJar, "$mcVersion-dist.jar", "name-mapping.txt",
    "redirects-mc.tsv", "redirects-opt.tsv", "redirects-batch.tsv", "redirects-mods.tsv")
val distPatchSound = distPatch("distPatchSound", paulscode, "librarylwjglopenal-dist.jar", "redirects-al.tsv")

val distClasses = tasks.register<Sync>("distClasses") {
    description = "The obfuscated Bridge jar as a folder (for the coverage check)."
    from(distJar.map { zipTree(it.outputs.files.first { f -> f.name == "steinbridge.jar" }) })
    into(distWork.map { it.dir("classes") })
}

// Every detoured call of the game patched with the translated rules must exist, with the exact descriptor, in the
// obfuscated Bridge or in LWJGL 3: the same check as coverage, on what the player's machine will run.
val distCoverage = tasks.register<JavaExec>("distCoverage") {
    group = "verification"
    description = "Checks that every LWJGL/Stein reference of the game patched with the obfuscated rules is served."
    inputs.files(distPatchGame, distClasses)
    outputs.upToDateWhen { true }
    classpath = toolsSet.runtimeClasspath
    mainClass = "CoverageCheck"
    argumentProviders.add {
        listOf(distWork.get().file("patched/$mcVersion-dist.jar").asFile.path, file("scan/lwjgl3-index.txt").path,
            distClasses.get().destinationDir.path)
    }
}

// Every reference from our classes to our classes (and member) resolves in the obfuscated jar (tools/DistCheck.java).
val distCheck = tasks.register<JavaExec>("distCheck") {
    group = "verification"
    description = "Checks that every reference of the obfuscated Bridge to its own classes and members resolves."
    inputs.files(distJar)
    outputs.upToDateWhen { true }
    classpath = toolsSet.runtimeClasspath
    mainClass = "DistCheck"
    argumentProviders.add { listOf(distDir.get().file("steinbridge.jar").asFile.path) }
}

/** The classpath the obfuscated game runs with: the obfuscated Bridge, the game patched with its rules, LWJGL 3. */
fun distGameClasspath(): FileCollection = files(
    distDir.map { it.file("steinbridge.jar") },
    distWork.map { it.file("patched/$mcVersion-dist.jar") },
    provider { gameLibraries(patched = true, dist = true) },
    lwjgl,
    lwjglNatives,
)

// build/dist-work/run/game.properties: like gameRuntime, for the obfuscated game (the Loader's -Pstein.dist).
val distRuntime = tasks.register("distRuntime") {
    description = "Writes build/dist-work/run/game.properties, the obfuscated game's classpath, for the loader build."
    dependsOn(natives, distJar, distPatchGame, distPatchSound)
    val out = distWork.map { it.file("run/game.properties") }
    outputs.file(out)
    outputs.upToDateWhen { false }
    doLast {
        val p = Properties()
        p["mcDir"] = mcDir.path
        p["gameJar"] = gameJar.path
        p["assets"] = mcDir.resolve("assets").path
        p["natives"] = natives.get().destinationDir.path
        p["classpath"] = distGameClasspath().files.joinToString(File.pathSeparator) { it.path }
        out.get().asFile.apply { parentFile.mkdirs() }.writer().use {
            p.store(it, "Stein Bridge: the obfuscated game (written by ./gradlew dist)")
        }
    }
}

tasks.register("dist") {
    group = "distribution"
    description = "The obfuscated Bridge for the installer: build/dist (jar and rules), mapping in build/dist-mapping."
    dependsOn(distJar, distRules, distCheck, distCoverage, distRuntime)
    doLast {
        logger.lifecycle("ok: build/dist/steinbridge.jar and build/dist/rules (mapping in build/dist-mapping: never distribute it)")
    }
}

// ---- Minecraft from Mojang, for machines without the launcher (CI) ----

tasks.register("fetchMinecraft") {
    group = "build setup"
    description = "Downloads Minecraft 1.8.9 and its libraries from Mojang's CDN into build/mc (sha1-checked)."
    val dest = layout.buildDirectory.dir("mc").get().asFile
    doLast {
        fun sha1(f: File) = MessageDigest.getInstance("SHA-1").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
        fun get(url: String, out: File, sha: String?) {
            if (out.isFile && (sha == null || sha1(out) == sha)) return
            out.parentFile.mkdirs()
            val part = File(out.path + ".part")
            uri(url).toURL().openStream().use { input -> part.outputStream().use { input.copyTo(it) } }
            check(sha == null || sha1(part) == sha) { "sha1 does not match: $url" }
            part.renameTo(out.also { it.delete() })
            logger.lifecycle("  ok  ${out.relativeTo(dest)}")
        }
        @Suppress("UNCHECKED_CAST")
        fun json(f: File) = groovy.json.JsonSlurper().parse(f) as Map<String, Any?>
        val manifest = File(temporaryDir, "manifest.json")
        get("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json", manifest, null)
        @Suppress("UNCHECKED_CAST")
        val entry = (json(manifest)["versions"] as List<Map<String, Any?>>).first { it["id"] == mcVersion }
        val versionJson = dest.resolve("versions/$mcVersion/$mcVersion.json")
        get(entry["url"] as String, versionJson, entry["sha1"] as String)
        val version = json(versionJson)
        @Suppress("UNCHECKED_CAST")
        val client = (version["downloads"] as Map<String, Map<String, Any?>>)["client"]!!
        get(client["url"] as String, dest.resolve("versions/$mcVersion/$mcVersion.jar"), client["sha1"] as String)
        @Suppress("UNCHECKED_CAST")
        for (lib in version["libraries"] as List<Map<String, Any?>>) {
            val downloads = lib["downloads"] as Map<String, Any?>? ?: continue
            // The native's classifier comes from the library's "natives" map, as the launcher reads it: some
            // name the architecture ("natives-windows-${arch}").
            val osKey = when {
                os.isWindows -> "windows"
                os.isMacOsX -> "osx"
                else -> "linux"
            }
            val classifier = ((lib["natives"] as Map<String, Any?>?)?.get(osKey) as String?)?.replace("\${arch}", "64")
            val artifacts = listOfNotNull(downloads["artifact"]) +
                listOfNotNull(classifier?.let { (downloads["classifiers"] as Map<String, Any?>?)?.get(it) })
            for (a in artifacts.map { it as Map<String, Any?> }) {
                get(a["url"] as String, dest.resolve("libraries/${a["path"]}"), a["sha1"] as String)
            }
        }
        logger.lifecycle("ok — Minecraft $mcVersion in $dest (use -Pstein.mcDir=build/mc)")
    }
}

// ---- formatting ----

spotless {
    // LF everywhere, as committed (.editorconfig); Windows checkouts too.
    lineEndings = com.diffplug.spotless.LineEnding.UNIX
    java {
        target("src/**/*.java", "test/**/*.java", "tools/*.java", "patcher/*.java")
        googleJavaFormat(libs.versions.google.java.format.get()).aosp().reorderImports(true)
    }
}

// The checks are programs (sanityTest, preflight, coverage, in-game verification), not JUnit tests yet.
tasks.test { failOnNoDiscoveredTests = false }

// The scan tools (tools/scan-api.sh, check-compat.sh, gen-caps.sh) read LWJGL 3 and ASM from build/dev-libs.
tasks.register<Sync>("devLibs") {
    group = "build setup"
    description = "Copies LWJGL 3 and ASM into build/dev-libs, for the scan tools in tools/."
    from(lwjgl, asm)
    into(layout.buildDirectory.dir("dev-libs"))
}

// What the installer ships next to the Bridge (../installer): LWJGL 3 with the natives of every system, and ASM for
// the Patcher. Copied out of the Gradle cache so the installer build reads plain files.
tasks.register<Sync>("runtimeLibs") {
    group = "build"
    description = "Copies LWJGL 3 (all natives) and ASM into build/runtime-libs, for the installer."
    into(layout.buildDirectory.dir("runtime-libs"))
    from(files(lwjgl, lwjglNatives)) { into("lwjgl") }
    from(asm) { into("asm") }
}
