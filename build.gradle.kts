import groovy.xml.XmlParser
import org.gradle.api.JavaVersion.VERSION_21
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.gradle.nativeplatform.platform.internal.DefaultNativePlatform
import org.jetbrains.intellij.platform.gradle.Constants.Configurations
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jsoup.Jsoup
import java.io.Writer
import kotlin.concurrent.thread

// The same as `--stacktrace` param
gradle.startParameter.showStacktrace = ShowStacktrace.ALWAYS

val isCI = System.getenv("CI") != null
val isTeamcity = System.getenv("TEAMCITY_VERSION") != null

val channel = prop("publishChannel")
val platformVersion = prop("platformVersion").toInt()
val baseIDE = prop("baseIDE")
val ideToRun = prop("ideToRun").ifEmpty { baseIDE }
val ideaVersion = prop("ideaVersion")
val baseVersion = versionForIde(baseIDE)

val tomlPlugin: String by project
val graziePlugin: String by project
val psiViewerPlugin: String by project
val copyrightPlugin = "com.intellij.copyright"
val javaPlugin = "com.intellij.java"
val javaIdePlugin = "com.intellij.java.ide"
val javaScriptPlugin = "JavaScript"
val mlCompletionPlugin = "com.intellij.completion.ml.ranking"

val compileNativeCodeTaskName = "compileNativeCode"

val grammarKitFakePsiDeps = "grammar-kit-fake-psi-deps"

plugins {
    idea
    kotlin("jvm") version "2.2.20"
    id("org.jetbrains.intellij.platform.module") version "2.8.0"
    id("org.jetbrains.grammarkit") version "2022.3.2.2"
    id("net.saliman.properties") version "1.5.2"
    id("org.gradle.test-retry") version "1.6.2"
}

idea {
    module {
        // https://github.com/gradle/kotlin-dsl/issues/537/
        excludeDirs = excludeDirs + file("testData") + file("deps") + file("bin") +
            file("$grammarKitFakePsiDeps/src/main/kotlin")
    }
}

allprojects {
    apply {
        plugin("idea")
        plugin("kotlin")
        plugin("org.jetbrains.grammarkit")
        plugin("org.jetbrains.intellij.platform.module")
        plugin("org.gradle.test-retry")
    }

    repositories {
        mavenCentral()
        intellijPlatform {
            defaultRepositories()
        }
    }

    idea {
        module {
            generatedSourceDirs.add(file("src/gen"))
        }
    }

    intellijPlatform {
        sandboxContainer.set(layout.buildDirectory.dir("$ideToRun-sandbox-$platformVersion"))
        pluginConfiguration {
            ideaVersion {
                sinceBuild.set(prop("sinceBuild"))
                untilBuild.set(prop("untilBuild"))
            }
        }

        pluginVerification {
            ides {
                recommended()
            }
        }
    }

    configure<JavaPluginExtension> {
        sourceCompatibility = VERSION_21
        targetCompatibility = VERSION_21
    }

    tasks {
        withType<KotlinCompile> {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_21)
                languageVersion.set(KotlinVersion.DEFAULT)
                // see https://plugins.jetbrains.com/docs/intellij/using-kotlin.html#kotlin-standard-library
                apiVersion.set(KotlinVersion.KOTLIN_1_9)
                freeCompilerArgs.set(listOf("-Xjvm-default=all"))
            }
        }

        // All these tasks don't make sense for non-root subprojects
        // Root project (i.e. `:plugin`) enables them itself if needed
        prepareSandbox { enabled = false }
        intellijPlatform {
            buildSearchableOptions = false
        }

        test {
            systemProperty("java.awt.headless", "true")
            testLogging {
                showStandardStreams = prop("showStandardStreams").toBoolean()
                afterSuite(
                    KotlinClosure2<TestDescriptor, TestResult, Unit>({ desc, result ->
                        if (desc.parent == null) { // will match the outermost suite
                            val output = "Results: ${result.resultType} (${result.testCount} tests, ${result.successfulTestCount} passed, ${result.failedTestCount} failed, ${result.skippedTestCount} skipped)"
                            println(output)
                        }
                    })
                )
            }
            if (isCI) {
                retry {
                    maxRetries.set(3)
                    maxFailures.set(5)
                }
            }
        }

        // It makes sense to copy native binaries only for root ("intellij-rust") and "plugin" projects because:
        // - `intellij-rust` is supposed to provide all necessary functionality related to procedural macro expander.
        //   So the binaries are required for the corresponding tests.
        // - `plugin` is root project to build plugin artifact and exactly its sandbox is included into the plugin artifact
        if (project.name in listOf("intellij-rust", "plugin")) {
            task<Exec>(compileNativeCodeTaskName) {
                workingDir = rootDir.resolve("native-helper")
                executable = "cargo"
                // Hack to use unstable `--out-dir` option work for stable toolchain
                // https://doc.rust-lang.org/cargo/commands/cargo-build.html#output-options
                environment("RUSTC_BOOTSTRAP", "1")

                val hostPlatform = DefaultNativePlatform.host()
                val archName = when (val archName = hostPlatform.architecture.name) {
                    "arm-v8", "aarch64" -> "arm64"
                    else -> archName
                }
                val outDir = "${rootDir}/bin/${hostPlatform.operatingSystem.toFamilyName()}/$archName"
                args("build", "--release", "-Z", "unstable-options", "--out-dir", outDir)

                // It may be useful to disable compilation of native code.
                // For example, CI builds native code for each platform in separate tasks and puts it into `bin` dir manually
                // so there is no need to do it again.
                enabled = prop("compileNativeCode").toBoolean()
            }
        }
    }

    sourceSets {
        main {
            java.srcDirs("src/gen")
            resources.srcDirs("src/$platformVersion/main/resources")
        }
        test {
            resources.srcDirs("src/$platformVersion/test/resources")
        }
    }
    kotlin {
        sourceSets {
            main {
                kotlin.srcDirs("src/$platformVersion/main/kotlin")
            }
            test {
                kotlin.srcDirs("src/$platformVersion/test/kotlin")
            }
        }
    }

    val testOutput = configurations.create("testOutput")

    dependencies {
        intellijPlatform {
            create(baseIDE, baseVersion) { useCache = true }

            pluginVerifier()

            // used in MacroExpansionManager.kt and ResolveCommonThreadPool.kt
            testFramework(TestFrameworkType.Platform, configurationName = Configurations.INTELLIJ_PLATFORM_DEPENDENCIES)

            bundledModule("intellij.platform.coverage")
            bundledModule("intellij.platform.coverage.agent")
            bundledModule("intellij.platform.vcs.impl")

            testBundledModule("intellij.platform.navbar")
            testBundledModule("intellij.platform.navbar.backend")
        }

        compileOnly(kotlin("stdlib-jdk8"))
        implementation("junit:junit:4.13.2") // used in kotlin/org/rust/openapiext/Testmark.kt
        // https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-faq.html#missing-opentest4j-dependency-in-test-framework
        testImplementation("org.opentest4j:opentest4j:1.3.0")
        testOutput(sourceSets.getByName("test").output.classesDirs)
    }

    afterEvaluate {
        tasks.withType<AbstractTestTask> {
            testLogging {
                if (hasProp("showTestStatus") && prop("showTestStatus").toBoolean()) {
                    events = setOf(TestLogEvent.STARTED, TestLogEvent.PASSED, TestLogEvent.SKIPPED, TestLogEvent.FAILED)
                }
                exceptionFormat = TestExceptionFormat.FULL
            }
        }

        tasks.withType<Test>().configureEach {
            jvmArgs = listOf("-Xmx5g", "-XX:-OmitStackTraceInFastThrow")

            // We need to prevent the platform-specific shared JNA library to loading from the system library paths,
            // because otherwise it can lead to compatibility issues.
            // Also note that IDEA does the same thing at startup, and not only for tests.
            systemProperty("jna.nosys", "true")

            // The factory should be set up automatically in `IdeaForkJoinWorkerThreadFactory.setupForkJoinCommonPool`,
            // but when tests are launched by Gradle this may not happen because Gradle can use the pool earlier.
            // Setting this factory is critical for `ReadMostlyRWLock` performance, so ensure it is properly set
            systemProperty(
                "java.util.concurrent.ForkJoinPool.common.threadFactory",
                "com.intellij.concurrency.IdeaForkJoinWorkerThreadFactory"
            )
            if (isTeamcity) {
                // Make teamcity builds green if only muted tests fail
                // https://youtrack.jetbrains.com/issue/TW-16784
                ignoreFailures = true
            }
            if (hasProp("excludeTests")) {
                exclude(prop("excludeTests"))
            }
        }
    }
}


val Project.dependencyCachePath
    get(): String {
        val cachePath = file("${rootProject.projectDir}/deps")
        // If cache path doesn't exist, we need to create it manually
        // because otherwise gradle-intellij-plugin will ignore it
        if (!cachePath.exists()) {
            cachePath.mkdirs()
        }
        return cachePath.absolutePath
    }

project(":$grammarKitFakePsiDeps")

project(":") {
    sourceSets {
        main {
            if (channel == "nightly" || channel == "dev") {
                resources.srcDirs("src/main/resources-nightly")
                resources.srcDirs("src/$platformVersion/main/resources-nightly")
            } else {
                resources.srcDirs("src/main/resources-stable")
                resources.srcDirs("src/$platformVersion/main/resources-stable")
            }
        }
    }

    dependencies {
        intellijPlatform {
            plugins(listOf(tomlPlugin))
        }

        implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-toml:2.20.0") {
            exclude(module = "jackson-core")
            exclude(module = "jackson-databind")
            exclude(module = "jackson-annotations")
        }
        api("io.github.z4kn4fein:semver:3.0.0") {
            excludeKotlinDeps()
        }
        implementation("org.eclipse.jgit:org.eclipse.jgit:7.3.0.202506031305-r") {
            exclude("org.slf4j")
        }
        testImplementation("com.squareup.okhttp3:mockwebserver:5.1.0") {
            excludeKotlinDeps()
        }
    }

    tasks {
        generateLexer {
            sourceFile.set(file("src/main/grammars/RustLexer.flex"))
            targetOutputDir.set(file("src/gen/org/rust/lang/core/lexer"))
            purgeOldFiles.set(true)
        }
        generateParser {
            sourceFile.set(file("src/main/grammars/RustParser.bnf"))
            targetRootOutputDir.set(file("src/gen"))
            pathToParser.set("org/rust/lang/core/parser/RustParser.java")
            pathToPsiRoot.set("org/rust/lang/core/psi")
            purgeOldFiles.set(true)
            classpath(project(":$grammarKitFakePsiDeps").sourceSets.main.get().runtimeClasspath)
        }
        withType<KotlinCompile> {
            dependsOn(generateLexer, generateParser)
        }

        // In tests `resources` directory is used instead of `sandbox`
        processTestResources {
            dependsOn(named(compileNativeCodeTaskName))
            from("${rootDir}/bin") {
                into("bin")
                include("**")
            }
        }
    }

    task("resolveDependencies") {
        doLast {
            rootProject.allprojects
                .map { it.configurations }
                .flatMap { it.filter { c -> c.isCanBeResolved } }
                .forEach { it.resolve() }
        }
    }
}

project(":idea") {
    dependencies {
        intellijPlatform {
            bundledPlugins(listOf(
                javaPlugin,
                // this plugin registers `com.intellij.ide.projectView.impl.ProjectViewPane` for IDEA that we use in tests
                javaIdePlugin
            ))
        }

        implementation(project(":"))
        testImplementation(project(":", "testOutput"))
    }
}

project(":copyright") {
    dependencies {
        intellijPlatform {
            bundledPlugins(listOf(copyrightPlugin))
        }

        implementation(project(":"))
        testImplementation(project(":", "testOutput"))
    }

    tasks {
        composedJar {
            archiveBaseName.set("copyright-rust")
        }
    }
}

project(":duplicates") {
    dependencies {
        implementation(project(":"))
        testImplementation(project(":", "testOutput"))
    }
}

project(":coverage") {
    dependencies {
        implementation(project(":"))
        testImplementation(project(":", "testOutput"))
    }
}

project(":grazie") {
    dependencies {
        intellijPlatform {
            plugins(listOf(graziePlugin))
        }

        implementation(project(":"))
        testImplementation(project(":", "testOutput"))
    }

    tasks {
        composedJar {
            archiveBaseName.set("grazie-rust")
        }
    }
}

project(":js") {
    dependencies {
        intellijPlatform {
            bundledPlugins(listOf(javaScriptPlugin))
        }

        implementation(project(":"))
        testImplementation(project(":", "testOutput"))
    }
}

project(":ml-completion") {
    dependencies {
        intellijPlatform {
            bundledPlugins(listOf(mlCompletionPlugin))
        }

        implementation("org.jetbrains.intellij.deps.completion:completion-ranking-rust:0.4.1")
        implementation(project(":"))
        testImplementation(project(":", "testOutput"))
    }
}

task("updateCargoOptions") {
    doLast {
        val file = File("src/main/kotlin/org/rust/cargo/util/CargoOptions.kt")
        file.bufferedWriter().use {
            it.writeln("""
                /*
                 * Use of this source code is governed by the MIT license that can be
                 * found in the LICENSE file.
                 */

                package org.rust.cargo.util

                data class CargoOption(val name: String, val description: String) {
                    val longName: String get() = "--${'$'}name"
                }

            """.trimIndent())
            it.writeCargoOptions("https://doc.rust-lang.org/cargo/commands")
        }
    }
}

tasks.wrapper {
    distributionType = Wrapper.DistributionType.ALL
}


fun Writer.writeCargoOptions(baseUrl: String) {

    data class CargoOption(
        val name: String,
        val description: String
    )

    data class CargoCommand(
        val name: String,
        val description: String,
        val options: List<CargoOption>
    )

    fun fetchCommand(commandUrl: String): CargoCommand {
        val document = Jsoup.connect("$baseUrl/$commandUrl").get()

        val fullCommandDesc = document.select("div[class=sectionbody] > p").text()
        val parts = fullCommandDesc.split(" - ", limit = 2)
        check(parts.size == 2) { "Invalid page format: $baseUrl/$commandUrl$" }
        val commandName = parts.first().removePrefix("cargo-")
        val commandDesc = parts.last()

        val options = document
            .select("dt > strong:matches(^--)")
            .map { option ->
                val optionName = option.text().removePrefix("--")
                val nextSiblings = generateSequence(option.parent()) { it.nextElementSibling() }
                val descElement = nextSiblings.first { it.tagName() == "dd" }
                val fullOptionDesc = descElement.select("p").text()
                val optionDesc = fullOptionDesc.substringBefore(". ").removeSuffix(".")
                CargoOption(optionName, optionDesc)
            }

        return CargoCommand(commandName, commandDesc, options)
    }

    fun fetchCommands(): List<CargoCommand> {
        val document = Jsoup.connect("$baseUrl/cargo.html").get()
        val urls = document.select("dt > a[href]").map { it.attr("href") }
        return urls.map { fetchCommand(it) }
    }

    fun writeEnumVariant(command: CargoCommand, isLast: Boolean) {
        val variantName = command.name.uppercase().replace('-', '_')
        val renderedOptions = command.options.joinToString(
            separator = ",\n            ",
            prefix = "\n            ",
            postfix = "\n        "
        ) { "CargoOption(\"${it.name}\", \"\"\"${it.description}\"\"\")" }

        writeln("""
        |    $variantName(
        |        description = "${command.description}",
        |        options = ${if (command.options.isEmpty()) "emptyList()" else "listOf($renderedOptions)"}
        |    )${if (isLast) ";" else ","}
        """.trimMargin())
        writeln()
    }

    val commands = fetchCommands()
    writeln("enum class CargoCommands(val description: String, val options: List<CargoOption>) {")
    for ((index, command) in commands.withIndex()) {
        writeEnumVariant(command, isLast = index == commands.size - 1)
    }
    writeln("    val presentableName: String get() = name.lowercase().replace('_', '-')")
    writeln("}")
}

fun Writer.writeln(str: String = "") {
    write(str)
    write("\n")
}

fun hasProp(name: String): Boolean = extra.has(name)

fun prop(name: String): String =
    extra.properties[name] as? String
        ?: error("Property `$name` is not defined in gradle.properties")

fun versionForIde(ideName: String): String = when (ideName) {
    "IU", "IC" -> ideaVersion
    else -> error("Unexpected IDE name: `$baseIDE`")
}

inline operator fun <T : Task> T.invoke(a: T.() -> Unit): T = apply(a)

fun String.execute(wd: String? = null, ignoreExitCode: Boolean = false, print: Boolean = true): String =
    split(" ").execute(wd, ignoreExitCode, print)

fun List<String>.execute(wd: String? = null, ignoreExitCode: Boolean = false, print: Boolean = true): String {
    val process = ProcessBuilder(this)
        .also { pb -> wd?.let { pb.directory(File(it)) } }
        .start()
    var result = ""
    val errReader = thread { process.errorStream.bufferedReader().forEachLine { println(it) } }
    val outReader = thread {
        process.inputStream.bufferedReader().forEachLine { line ->
            if (print) {
                println(line)
            }
            result += line
        }
    }
    process.waitFor()
    outReader.join()
    errReader.join()
    if (process.exitValue() != 0 && !ignoreExitCode) error("Non-zero exit status for `$this`")
    return result
}

fun File.isPluginJar(): Boolean {
    if (!isFile) return false
    if (extension != "jar") return false
    return zipTree(this).files.any { it.isManifestFile() }
}

fun File.isManifestFile(): Boolean {
    if (extension != "xml") return false
    val rootNode = try {
        val parser = XmlParser()
        parser.parse(this)
    } catch (e: Exception) {
        logger.error("Failed to parse $path", e)
        return false
    }
    return rootNode.name() == "idea-plugin"
}

fun <T : ModuleDependency> T.excludeKotlinDeps() {
    exclude(module = "kotlin-reflect")
    exclude(module = "kotlin-runtime")
    exclude(module = "kotlin-stdlib")
    exclude(module = "kotlin-stdlib-common")
    exclude(module = "kotlin-stdlib-jdk8")
    exclude(module = "kotlinx-serialization-core")
}
