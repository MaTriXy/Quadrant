package com.gaelmarhic.quadrant

import com.android.build.gradle.*
import com.android.build.gradle.api.AndroidSourceSet
import com.android.build.gradle.api.BaseVariant
import com.gaelmarhic.quadrant.constants.GeneralConstants.PLUGIN_CONFIG
import com.gaelmarhic.quadrant.constants.GeneralConstants.TARGET_DIRECTORY
import com.gaelmarhic.quadrant.extensions.QuadrantConfigurationExtension
import com.gaelmarhic.quadrant.models.modules.RawModule
import com.gaelmarhic.quadrant.tasks.GenerateActivityClassNameConstants
import org.gradle.api.DomainObjectCollection
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.plugins.ExtensionContainer
import java.io.File
import kotlin.reflect.KClass

class QuadrantPlugin : Plugin<Project> {

    override fun apply(project: Project) = with(project) {
        plugins.all { plugin ->
            when (plugin) {
                is AppPlugin -> {
                    applyPlugin(AppExtension::class) { it.applicationVariants }
                }
                is LibraryPlugin -> {
                    applyPlugin(LibraryExtension::class) { it.libraryVariants }
                }
            }
        }
    }

    private fun <E : BaseExtension, V : BaseVariant> Project.applyPlugin(
        extensionType: KClass<E>,
        block: (E) -> DomainObjectCollection<V>
    ) {
        val extension = getExtension(extensionType)
        val variants = block(extension)
        val mainSourceSet = extension.sourceSet(MAIN_SOURCE_SET)

        registerTask(createGenerateActivityClassNameConstantsTask(), variants)
        addTargetDirectoryToSourceSet(mainSourceSet)
    }

    private fun <V : BaseVariant> Project.registerTask(
        taskToBeRegistered: Task,
        variants: DomainObjectCollection<V>
    ) {
        afterEvaluate {
            variants.all { variant ->
                tasks.all { task ->
                    if (task.isCompileOrKspKotlinTask(variant)) {
                        task.dependsOn(taskToBeRegistered)
                    }
                }
            }
        }
    }

    private fun Project.addTargetDirectoryToSourceSet(sourceSet: AndroidSourceSet) {
        sourceSet.java.srcDir("$buildDir${File.separator}$TARGET_DIRECTORY")
    }

    private fun <E : BaseExtension> Project.getExtension(type: KClass<E>) = extensions[type]

    private operator fun <T : BaseExtension> ExtensionContainer.get(type: KClass<T>): T {
        return getByType(type.java)
    }

    private fun Project.createGenerateActivityClassNameConstantsTask(): Task {
        val taskType = GenerateActivityClassNameConstants::class.java
        val taskName = taskType.simpleName.decapitalize()
        val extension = registerConfigurationExtension()
        return tasks.create(taskName, taskType) { task ->
            val rawModuleList = retrieveRawModuleList(this)
            task.apply {
                configurationExtension.set(extension)
                buildScript.set(buildFile)
                manifestFiles.set(rawModuleList.flatMap { it.manifestFiles })
                targetDirectory.set(buildDir.resolve(TARGET_DIRECTORY))
                rawModules.set(rawModuleList)
            }
        }
    }

    private fun Project.registerConfigurationExtension() =
        extensions.create(PLUGIN_CONFIG, QuadrantConfigurationExtension::class.java)

    private fun retrieveRawModuleList(project: Project) =
        project // This project is the project of the module where the plugin is applied.
            .rootProject
            .allprojects
            .map { it.toRawModule() }

    private fun Project.toRawModule(): RawModule = RawModule(
        name = name,
        manifestFiles = manifestFiles,
        namespace = extensions.findByType(BaseExtension::class.java)?.namespace.orEmpty()
    )

    private val Project.manifestFiles: List<File>
        get() = projectDir
            .walk()
            .maxDepth(MANIFEST_FILE_DEPTH)
            .filter { it.name == MANIFEST_FILE_NAME }
            .toList()

    private fun BaseExtension.sourceSet(name: String) = sourceSets.getByName(name)

    private fun <T : BaseVariant> Task.isCompileOrKspKotlinTask(variant: T): Boolean {
        val variantName = variant.name.capitalize()
        return name in listOf(
            "compile${variantName}Kotlin",
            "ksp${variantName}Kotlin",
        )
    }

    companion object {

        private const val MAIN_SOURCE_SET = "main"
        private const val MANIFEST_FILE_DEPTH = 3
        private const val MANIFEST_FILE_NAME = "AndroidManifest.xml"
    }
}
