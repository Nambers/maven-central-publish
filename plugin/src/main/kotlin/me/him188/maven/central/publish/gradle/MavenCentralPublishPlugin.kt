@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package me.him188.maven.central.publish.gradle

import groovy.util.Node
import groovy.util.NodeList
import io.github.karlatemp.publicationsign.PublicationSignPlugin
import me.him188.maven.central.publish.gradle.tasks.CheckMavenCentralPublication
import me.him188.maven.central.publish.gradle.tasks.CheckPublicationCredentials
import me.him188.maven.central.publish.gradle.tasks.PreviewPublication
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.XmlProvider
import org.gradle.api.logging.LogLevel
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.TaskContainer
import org.gradle.jvm.tasks.Jar
import java.io.File


class MavenCentralPublishPlugin : Plugin<Project> {
    companion object {
        const val PLUGIN_ID: String = "me.him188.maven-central-publish"

        inline fun Project.log(message: () -> String) {
            if (logger.isInfoEnabled) {
                logger.info("[MavenCentralPublish] " + message())
            }
        }

        inline fun Project.log(level: LogLevel, message: () -> String) {
            if (logger.isInfoEnabled) {
                logger.log(level, "[MavenCentralPublish] " + message())
            }
        }

        const val SECURITY_FILE_NAME = "KEEP_THIS_DIR_EMPTY.txt"

        fun checkSecurityFile(workingDir: File) {
            val securityFile = workingDir.resolve(SECURITY_FILE_NAME)
            if (workingDir.exists()) {
                if (securityFile.exists()) {
                    // ok
                } else {
                    when (workingDir.listFiles()?.isEmpty()) {
                        null -> error("Working dir '${workingDir}' is not a directory. Please change `mavenCentralPublish.workingDir`")
                        false -> error("Working dir '${workingDir}' is not empty. Please change `mavenCentralPublish.workingDir`")
                        true -> {}
                    }
                }
            } else {
                // ok
            }
            workingDir.mkdirs()
            if (!workingDir.isDirectory) {
                error("Failed to create directory '${workingDir.absolutePath}'. Please change `mavenCentralPublish.workingDir`")
            }
            securityFile.writeText(
                """
                        This file is created by the Gradle plugin '$PLUGIN_ID', and this directory is reserved for publication. 
                        
                        Everytime signing artifacts, all files in this directory will be removed without notice.
                        Do not put any files into this directory, or use this directory for any other purposes, otherwise they will be deleted as stated above!
                        
                        If you are not using this directory for publication (i.e. when you changed the `workingDir`), you can delete this file.
                        Without the existence of this security file, the plugin won't remove anything and will give you an error on startup.
                        """
                    .trimIndent()
            )
        }

    }

    override fun apply(target: Project) {
        target.plugins.apply("maven-publish")
        target.plugins.apply(PublicationSignPlugin::class.java)

        target.extensions.create(
            MavenCentralPublishExtension::class.java,
            "mavenCentralPublish",
            MavenCentralPublishExtension::class.java,
            target
        )

        val checkPublicationCredentials = target.tasks.register(
            CheckPublicationCredentials.TASK_NAME,
            CheckPublicationCredentials::class.java
        ) { task ->
            task.group = "publishing"
            task.description = "Check publication credentials."
        }

        target.tasks.register(
            CheckMavenCentralPublication.TASK_NAME,
            CheckMavenCentralPublication::class.java
        ) { task ->
            task.group = "publishing"
            task.description = "Check whether information required to maven central publication is provided.."
            task.dependsOn(checkPublicationCredentials)
        }

        target.tasks.register(
            PreviewPublication.TASK_NAME,
            PreviewPublication::class.java
        ) { task ->
            task.group = "publishing"
            task.dependsOn(checkPublicationCredentials)
        }

        target.run {
            afterEvaluate {
                val ext = target.mcExt
                val credentials = ext.credentials ?: kotlin.run {
                    log(LogLevel.WARN) { "No credentials were set. Publication will not be configured." }
                    return@afterEvaluate
                }

                log { "credentials: length=${credentials.toString().length}" }

                log { "workingDir=${ext.workingDir.absolutePath}" }

                log { "Writing public key len=${credentials.gpgPublicKey.length} to \$workingDir/keys/key.pub." }
                log { "Writing private key len=${credentials.gpgPrivateKey.length} to \$workingDir/keys/key.pri." }

                val workingDir = ext.workingDir
                val keysDir = workingDir.resolve("keys")
                checkSecurityFile(workingDir)

                keysDir.run {
                    deleteRecursively() // clear caches
                    mkdirs()
                    resolve("key.pub").apply { createNewFile() }.writeText(credentials.gpgPublicKey)
                    resolve("key.pri").apply { createNewFile() }.writeText(credentials.gpgPrivateKey)
                }

                extensions.configure(io.github.karlatemp.publicationsign.PublicationSignExtension::class.java) { sign ->
                    sign.setupWorkflow { workflow ->
                        workflow.workingDir = keysDir
                        workflow.fastSetup(
                            keysDir.resolve("key.pub").absolutePath,
                            keysDir.resolve("key.pri").absolutePath,
                        )
                    }
                }

                if (ext.projectUrl.isEmpty() || ext.connection.isEmpty()) {
                    logger.warn("[MavenCentralPublish] projectUrl is not set. No publication is being configured. Please invoke `mavenCentralPublish()` according to https://github.com/Him188/maven-central-publish.")
                    return@afterEvaluate
                }

                registerJarTasks(project)
                registerPublication("mavenCentral", project, ext)
            }
        }
    }

    private fun Project.publishPlatformArtifactsInRootModule(platformPublication: MavenPublication) {
        lateinit var platformXml: XmlProvider
        platformPublication.pom.withXml { platformXml = it }

        extensions.findByType(PublishingExtension::class.java)
            ?.publications?.getByName("kotlinMultiplatform")
            ?.let { it as MavenPublication }?.run {

                // replace pom
                pom.withXml { xmlProvider ->
                    val root = xmlProvider.asNode()
                    // Remove the original content and add the content from the platform POM:
                    root.children().toList().forEach { root.remove(it as Node) }
                    platformXml.asNode().children().forEach { root.append(it as Node) }

                    // Adjust the self artifact ID, as it should match the root module's coordinates:
                    ((root.get("artifactId") as NodeList).get(0) as Node).setValue(artifactId)

                    // Set packaging to POM to indicate that there's no artifact:
                    root.appendNode("packaging", "pom")

                    // Remove the original platform dependencies and add a single dependency on the platform module:
                    val dependencies = (root.get("dependencies") as NodeList).get(0) as Node
                    dependencies.children().toList().forEach { dependencies.remove(it as Node) }
                    val singleDependency = dependencies.appendNode("dependency")
                    singleDependency.appendNode("groupId", platformPublication.groupId)
                    singleDependency.appendNode("artifactId", platformPublication.artifactId)
                    singleDependency.appendNode("version", platformPublication.version)
                    singleDependency.appendNode("scope", "compile")
                }
            }

        tasks.matching { it.name == "generatePomFileForKotlinMultiplatformPublication" }.configureEach { task ->
            task.dependsOn("generatePomFileFor${platformPublication.name.capitalize()}Publication")
        }
    }

    fun registerJarTasks(
        project: Project,
    ) = project.run {
        tasks.getOrRegister("sourcesJar", Jar::class.java) {
            @Suppress("DEPRECATION")
            archiveClassifier.set("sources")
            val sourceSets = (project.extensions.getByName("sourceSets") as SourceSetContainer).matching {
                it.name.endsWith("main", ignoreCase = true)
            }
            for (sourceSet in sourceSets) {
                from(sourceSet.allSource)
            }
        }

        tasks.getOrRegister("javadocJar", Jar::class.java) {
            @Suppress("DEPRECATION")
            archiveClassifier.set("javadoc")
        }

        tasks.getOrRegister("samplessourcesJar", Jar::class.java) {
            @Suppress("DEPRECATION")
            archiveClassifier.set("samplessources")
            val sourceSets = (project.extensions.getByName("sourceSets") as SourceSetContainer).matching {
                it.name.endsWith("test", ignoreCase = true)
            }
            for (sourceSet in sourceSets) {
                from(sourceSet.allSource)
            }
        }
    }

    fun registerPublication(
        name: String,
        project: Project,
        ext: MavenCentralPublishExtension,
    ): Unit = project.run {

        fun getJarTask(classifier: String) =
            tasks.singleOrNull { it is Jar && it.name == "${classifier}Jar" }
                ?: tasks.firstOrNull { it is Jar && it.archiveClassifier.get() == classifier }
                ?: error("Could not find $classifier Jar task.")


        val credentials = ext.credentials ?: return
        extensions.findByType(PublishingExtension::class.java)?.apply {
            val deploymentServerUrl = ext.deploymentServerUrl
            if (deploymentServerUrl != null) {
                repositories.maven { repo ->
                    repo.setUrl(deploymentServerUrl)
                    repo.credentials { c ->
                        c.username = credentials.sonatypeUsername
                        c.password = credentials.sonatypePassword
                    }
                }
            } else {
                logger.warn("[MavenCentralPublish] `deploymentServerUrl` was set to `null`, so no server is being automatically set. ")
            }

            if (!project.isMpp) {
                publications.register(name, MavenPublication::class.java) { publication ->
                    publication.run {
                        if (ext.addProjectComponents) from(components.getByName("java"))
                        if (ext.addSources) artifact(getJarTask("sources"))
                        if (ext.addJavadoc) artifact(getJarTask("javadoc"))

                        this.groupId = ext.groupId
                        this.artifactId = ext.artifactId
                        this.version = ext.version
                        setupPom(publication, ext)
                        ext.publicationConfigurators.forEach {
                            it.execute(this)
                        }
                    }

                }
            } else {
                publications.filterIsInstance<MavenPublication>().forEach { publication ->
                    // kotlin configures `sources` for us.
                    if (publication.name != "kotlinMultiplatform") {
                        if (ext.addJavadoc) publication.artifact(getJarTask("javadoc"))
                    }

                    publication.groupId = ext.groupId
                    publication.version = ext.version

                    setupPom(publication, ext)

                    when (val type = publication.name) {
                        "kotlinMultiplatform" -> {
                            publication.artifactId = ext.artifactId
                        }
                        "metadata", "jvm", "native", "js" -> {
                            publication.artifactId = "${ext.artifactId}-$type"
                            if (publication.name.contains("js", ignoreCase = true)) {
                                if (ext.addSources) publication.artifact(getJarTask("samplessources"))
                            }
                        }
                        else -> {
                            publication.artifactId =
                                "${ext.artifactId}${publication.artifactId.substringAfter(project.name)}"
                        }
                    }
                    ext.publicationConfigurators.forEach {
                        it.execute(publication)
                    }
                }
                if (ext.publishPlatformArtifactsInRootModule != null) {
                    val targetName = ext.publishPlatformArtifactsInRootModule
                    val publication =
                        publications.filterIsInstance<MavenPublication>()
                            .find {
                                it.artifactId.equals(
                                    "${ext.artifactId}-$targetName",
                                    ignoreCase = true
                                )
                            } // Kotlin enforce targets to be lowercase
                            ?: error(
                                "Could not find publication with artifactId '${ext.artifactId}-$targetName' for root module. " +
                                        "This means the target name '$targetName' you specified to `publishPlatformArtifactsInRootModule` is invalid." +
                                        "Your publishable targets: ${
                                            publications.filterIsInstance<MavenPublication>()
                                                .map { it.artifactId.substringAfter(ext.artifactId + "-") }
                                                .filter { it.isNotBlank() }
                                                .joinToString()
                                        }"
                            )
                    publishPlatformArtifactsInRootModule(publication)
                }
            }
        }
    }

    private fun setupPom(
        mavenPublication: MavenPublication,
        ext: MavenCentralPublishExtension
    ) {
        mavenPublication.pom { pom ->
            pom.withXml {
                it.asNode()
            }
            pom.name.set(ext.projectName)
            pom.description.set(ext.projectDescription)
            pom.url.set(ext.projectUrl)
            pom.scm { scm ->
                scm.url.set(ext.projectUrl)
                scm.connection.set(ext.connection)
            }
            ext.pomConfigurators.forEach {
                it.execute(pom)
            }
        }
    }
}

internal val Project.isMpp get() = project.plugins.findPlugin("org.jetbrains.kotlin.multiplatform") != null

private fun <T : Task> TaskContainer.getOrRegister(name: String, type: Class<T>, configurationAction: T.() -> Unit): T {
    return findByName(name)?.let { type.cast(it) } ?: register(name, type, configurationAction).get()
}

internal val Project.mcExt: MavenCentralPublishExtension get() = extensions.getByType(MavenCentralPublishExtension::class.java)
