// Special module with run, build, and publish tasks
import org.jetbrains.intellij.platform.gradle.tasks.*

val channel = prop("publishChannel")
val platformVersion = prop("platformVersion").toInt()
val baseIDE = prop("baseIDE")
val ideToRun = prop("ideToRun").ifEmpty { baseIDE }

val tomlPlugin: String by project
val graziePlugin: String by project
val psiViewerPlugin: String by project
val copyrightPlugin = "com.intellij.copyright"
val javaPlugin = "com.intellij.java"
val javaScriptPlugin = "JavaScript"
val mlCompletionPlugin = "com.intellij.completion.ml.ranking"

val compileNativeCodeTaskName = "compileNativeCode"

val grammarKitFakePsiDeps = "grammar-kit-fake-psi-deps"

val basePluginArchiveName = "intellij-rust"

plugins {
    id("org.jetbrains.intellij.platform")
}

apply {
    plugin("org.jetbrains.intellij.platform")
}

val pluginProjects: List<Project>
    get() = rootProject.allprojects.filter { it.name != grammarKitFakePsiDeps }

version = System.getenv("BUILD_NUMBER") ?: "${platformVersion}.${prop("buildNumber")}"

intellijPlatform {
    pluginConfiguration {
        name.set("intellij-rust")
        description.set(provider { file("description.html").readText() })
    }
}

dependencies {
    intellijPlatform {
        val pluginList = mutableListOf(
            tomlPlugin,
            graziePlugin,
            psiViewerPlugin,
        )
        val bundledPluginList = mutableListOf(
            javaScriptPlugin,
            mlCompletionPlugin
        )
        if (ideToRun in setOf("IU", "IC")) {
            bundledPluginList += listOf(
                copyrightPlugin,
                javaPlugin,
            )
        }
        plugins(pluginList)
        bundledPlugins(bundledPluginList)

        pluginComposedModule(implementation(project(":idea")))
        pluginComposedModule(implementation(project(":copyright")))
        pluginComposedModule(implementation(project(":coverage")))
        pluginComposedModule(implementation(project(":duplicates")))
        pluginComposedModule(implementation(project(":grazie")))
        pluginComposedModule(implementation(project(":js")))
        pluginComposedModule(implementation(project(":ml-completion")))
    }

    implementation(project(":"))
}

// Add plugin sources to the plugin ZIP.
// gradle-intellij-plugin will use it as a plugin sources if the plugin is used as a dependency
val createSourceJar = task<Jar>("createSourceJar") {
    dependsOn(":generateLexer")
    dependsOn(":generateParser")

    for (prj in pluginProjects) {
        from(prj.kotlin.sourceSets.main.get().kotlin) {
            include("**/*.java")
            include("**/*.kt")
        }
    }

    destinationDirectory.set(layout.buildDirectory.dir("libs"))
    archiveBaseName.set(basePluginArchiveName)
    archiveClassifier.set("src")
}

tasks {
    buildPlugin {
        dependsOn(createSourceJar)
        from(createSourceJar) { into("lib/src") }
        // Set proper name for final plugin zip.
        // Otherwise, base name is the same as gradle module name
        archiveBaseName.set(basePluginArchiveName)
    }
    runIde { enabled = true }
    prepareSandbox {
        enabled = true
    }
    verifyPlugin {
    }
    buildSearchableOptions {
        enabled = prop("enableBuildSearchableOptions").toBoolean()
    }
    withType<PrepareSandboxTask> {
        dependsOn(named(compileNativeCodeTaskName))

        // Copy native binaries
        from("${rootDir}/bin") {
            into("${intellijPlatform.projectName.get()}/bin")
            include("**")
        }
    }

    withType<RunIdeTask> {
        // Default args for IDEA installation
        jvmArgs("-Xmx768m", "-XX:+UseG1GC", "-XX:SoftRefLRUPolicyMSPerMB=50")
        // Disable plugin auto reloading. See `com.intellij.ide.plugins.DynamicPluginVfsListener`
        jvmArgs("-Didea.auto.reload.plugins=false")
        // Don't show "Tip of the Day" at startup
        jvmArgs("-Dide.show.tips.on.startup.default.value=false")
        // uncomment if `unexpected exception ProcessCanceledException` prevents you from debugging a running IDE
        // jvmArgs("-Didea.ProcessCanceledException=disabled")

        // Uncomment to enable FUS testing mode
        // jvmArgs("-Dfus.internal.test.mode=true")

        // Uncomment to enable localization testing mode
        // jvmArgs("-Didea.l10n=true")
    }

    withType<PublishPluginTask> {
        token.set(prop("publishToken"))
        channels.set(listOf(channel))
    }
}

// Generates event scheme for Rust plugin FUS events to `plugin/build/eventScheme.json`
task<RunIdeTask>("buildEventsScheme") {
    dependsOn(tasks.prepareSandbox)
    args("buildEventsScheme", "--outputFile=${layout.buildDirectory.get().asFile.resolve("eventScheme.json").absolutePath}", "--pluginId=org.rust.lang")
    // BACKCOMPAT: 2024.2. Update value to 242 and this comment
    // `IDEA_BUILD_NUMBER` variable is used by `buildEventsScheme` task to write `buildNumber` to output json.
    // It will be used by TeamCity automation to set minimal IDE version for new events
    environment("IDEA_BUILD_NUMBER", "242")
}

fun hasProp(name: String): Boolean = extra.has(name)

fun prop(name: String): String =
    extra.properties[name] as? String
        ?: error("Property `$name` is not defined in gradle.properties")
