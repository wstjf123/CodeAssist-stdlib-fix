package dev.ide.core.templates

import dev.ide.model.BuildSystemId
import dev.ide.model.ContentRole
import dev.ide.model.DependencyScope
import dev.ide.model.SourceSetTemplate
import dev.ide.model.template.ProjectScaffold
import dev.ide.model.template.ProjectTemplate
import dev.ide.model.template.TemplateArgs
import dev.ide.model.template.TemplateCategory
import dev.ide.model.template.TemplateId
import dev.ide.model.template.TemplateParameter

/**
 * Built-in Kotlin project templates. These scaffold a Kotlin source tree (`src/main/kotlin`) that
 * the editor analyzes with the `lang-kotlin` backend (completion, resolution, go-to-definition, and the
 * inference subset), so a Kotlin project is editable out of the box.
 *
 * The native build compiles the Kotlin sources to bytecode (the `compileKotlin` task that jvm-build's
 * `JavaPlugin` registers for any module with `.kt`), so these `java-lib` modules build, and a console
 * template's top-level `fun main()` runs.
 */
private object KotlinTemplateSupport {
    /** A `main` source set rooted at `src/main/kotlin` (the Kotlin source-dir convention). */
    private fun mainSources() =
        SourceSetTemplate("main", DependencyScope.IMPLEMENTATION, mapOf("src/main/kotlin" to setOf(ContentRole.SOURCE)))

    /** Add a single-module Kotlin project ([moduleName] of [typeId]) and commit the model. */
    fun singleModule(scaffold: ProjectScaffold, projectName: String, moduleName: String, typeId: String) {
        scaffold.workspace.beginModification().apply {
            addProject(projectName, BuildSystemId.NATIVE, scaffold.rootDir)
            commit()
        }
        scaffold.workspace.projects.first { it.name == projectName }.beginModification().apply {
            addModule(moduleName, scaffold.moduleType(typeId)).apply {
                languageLevel = scaffold.languageLevel
                addSourceSet(mainSources())
            }
            commit()
        }
    }
}

/**
 * A Kotlin console app: one `app` module with a `Main.kt` that has a top-level `fun main()`. Editable,
 * buildable, and runnable — Run launches it in the interactive console.
 */
object KotlinConsoleAppTemplate : ProjectTemplate {
    override val id = TemplateId("kotlin-console")
    override val displayName = "Kotlin 控制台应用"
    override val description = "带顶层 main() 的 Kotlin 应用。支持完整编辑器智能，可在交互式控制台中构建和运行。"
    override val category = TemplateCategory.KOTLIN
    override val iconId = "kotlin"

    override fun parameters(): List<TemplateParameter> = emptyList()

    override fun generate(scaffold: ProjectScaffold, args: TemplateArgs) {
        KotlinTemplateSupport.singleModule(scaffold, args.name, "app", "java-lib")
        val pkg = args.packageName
        scaffold.writeText(
            "app/src/main/kotlin/${JavaTemplateSupport.pkgPath(pkg)}/Main.kt",
            """
            package $pkg

            fun main() {
                println("Hello from ${args.name}!")
            }
            """,
        )
    }
}

/** A plain Kotlin library: one `lib` module with a sample class, no entry point (nothing to run). */
object KotlinLibraryTemplate : ProjectTemplate {
    override val id = TemplateId("kotlin-library")
    override val displayName = "Kotlin 库"
    override val description = "可复用的 Kotlin 库模块。支持完整编辑器智能，并在原生构建中编译。"
    override val category = TemplateCategory.KOTLIN
    override val iconId = "kotlin"

    override fun parameters(): List<TemplateParameter> = emptyList()

    override fun generate(scaffold: ProjectScaffold, args: TemplateArgs) {
        KotlinTemplateSupport.singleModule(scaffold, args.name, "lib", "java-lib")
        val pkg = args.packageName
        val type = JavaTemplateSupport.typeName(args.name)
        scaffold.writeText(
            "lib/src/main/kotlin/${JavaTemplateSupport.pkgPath(pkg)}/$type.kt",
            """
            package $pkg

            /** Entry point of the ${args.name} library. */
            class $type {
                fun greet(name: String): String = "Hello, " + name + "!"
            }
            """,
        )
    }
}
