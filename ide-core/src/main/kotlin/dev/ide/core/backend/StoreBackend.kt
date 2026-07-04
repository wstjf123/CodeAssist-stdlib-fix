package dev.ide.core.backend

import dev.ide.core.BackendContext
import dev.ide.model.template.ProjectTemplate
import dev.ide.ui.backend.StoreService
import dev.ide.ui.backend.UiStoreCatalog
import dev.ide.ui.backend.UiStoreInstallResult
import dev.ide.ui.backend.UiStoreItem
import dev.ide.ui.backend.UiStoreItemKind
import dev.ide.ui.backend.UiStoreSection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [StoreService] for the home screen's Store tab. This is the UI-shell wiring: the catalog is served from the
 * bundled [ProjectTemplate]s (the real, installable content) plus a curated set of preview sample projects,
 * and the Community shelf is left empty. The interface is exactly what a remote, submission-backed catalog
 * will later implement, so the UI does not change when [catalog]/[search]/[install] move to a backend.
 *
 * Template items route through the normal Create-Project flow (the UI opens the configure form pre-selected
 * to [UiStoreItem.templateId]); only sample/community items would download through [install], which is not
 * wired yet (those items are marked [UiStoreItem.available] = false).
 */
internal class StoreBackend(private val ctx: BackendContext) : StoreService {

    private fun templates(): List<ProjectTemplate> =
        ctx.servicesOrNull?.projectTemplates() ?: ctx.manager?.projectTemplates() ?: emptyList()

    override fun storeAvailable(): Boolean = templates().isNotEmpty()

    override suspend fun catalog(): UiStoreCatalog = withContext(Dispatchers.Default) {
        val templateItems = templates().map(::toItem)
        // Featured = the curated highlights, falling back to the first few templates so the carousel is
        // never empty on a host that contributes its own (uncurated) templates.
        val featured = templateItems.filter { it.featured }.ifEmpty { templateItems.take(3) }
        val categories = buildList {
            templateItems.map { it.category }.distinct().forEach(::add)
            if (SAMPLE_ITEMS.isNotEmpty()) add(CATEGORY_SAMPLES)
            add(CATEGORY_COMMUNITY)
        }
        val sections = listOf(
            UiStoreSection("templates", "入门模板", "从精选脚手架快速创建新项目", templateItems),
            UiStoreSection("samples", "示例项目", "可阅读和运行的完整示例应用", SAMPLE_ITEMS),
            UiStoreSection("community", "社区", "社区分享的项目", emptyList()),
        )
        UiStoreCatalog(featured = featured, categories = categories, sections = sections)
    }

    override suspend fun search(query: String, category: String?): List<UiStoreItem> = withContext(Dispatchers.Default) {
        val all = templates().map(::toItem) + SAMPLE_ITEMS
        val q = query.trim().lowercase()
        all.filter { item -> matchesCategory(item, category) && matchesQuery(item, q) }
    }

    override suspend fun install(id: String, args: Map<String, String>): UiStoreInstallResult {
        // Template items are created through the Create-Project flow (the UI routes them there with the
        // configure form), never here. Sample/community downloads are filled in by the remote-catalog seam.
        return UiStoreInstallResult(false, "示例和社区项目即将推出")
    }

    private fun matchesCategory(item: UiStoreItem, category: String?): Boolean = when (category) {
        null -> true
        CATEGORY_SAMPLES -> item.kind == UiStoreItemKind.Sample
        CATEGORY_COMMUNITY -> item.kind == UiStoreItemKind.Community
        else -> item.category.equals(category, ignoreCase = true)
    }

    private fun matchesQuery(item: UiStoreItem, q: String): Boolean = q.isEmpty() ||
        item.title.lowercase().contains(q) ||
        item.summary.lowercase().contains(q) ||
        item.category.lowercase().contains(q) ||
        item.tags.any { it.lowercase().contains(q) }

    private fun toItem(t: ProjectTemplate): UiStoreItem {
        val meta = CURATION[t.id.value]
        return UiStoreItem(
            id = "template:${t.id.value}",
            kind = UiStoreItemKind.Template,
            title = t.displayName,
            summary = t.description,
            category = t.category.displayName,
            iconId = t.iconId,
            tags = meta?.tags ?: listOf(t.category.displayName),
            featured = meta?.featured ?: false,
            accentColor = meta?.accent,
            templateId = t.id.value,
            available = true,
        )
    }

    /** Per-template curation layered over the template's own metadata (featured flag, brand accent, tags). */
    private data class Curation(val featured: Boolean = false, val accent: Long? = null, val tags: List<String> = emptyList())

    private companion object {
        const val CATEGORY_SAMPLES = "示例"
        const val CATEGORY_COMMUNITY = "社区"

        val CURATION: Map<String, Curation> = mapOf(
            "compose-app" to Curation(featured = true, accent = 0xFF3FBDD9, tags = listOf("Jetpack Compose", "Material 3", "Kotlin")),
            "android-material-you" to Curation(featured = true, accent = 0xFFB487F7, tags = listOf("Material You", "视图", "Kotlin")),
            "android-app" to Curation(featured = true, accent = 0xFF3DDC84, tags = listOf("Android", "Activity", "XML 布局")),
            "kotlin-console" to Curation(tags = listOf("Kotlin", "控制台")),
            "java-console" to Curation(tags = listOf("Java", "控制台")),
            "android-library" to Curation(tags = listOf("Android", "AAR")),
        )

        // Curated preview samples (browse-only until the remote catalog wires downloads). Marked unavailable
        // so the detail sheet shows "即将推出" rather than a working install.
        val SAMPLE_ITEMS: List<UiStoreItem> = listOf(
            UiStoreItem(
                id = "sample:notes", kind = UiStoreItemKind.Sample, title = "便签",
                summary = "使用列表/详情流程的 Material 3 便签应用。",
                description = "完整的记事应用：Material 3 列表/详情流程、滑动删除，以及 " +
                    "本地持久化。适合了解状态处理和导航。",
                category = "Android", iconId = "module.android", author = "CodeAssist",
                tags = listOf("Material 3", "CRUD", "导航"), accentColor = 0xFF3DDC84, available = false,
            ),
            UiStoreItem(
                id = "sample:weather", kind = UiStoreItemKind.Sample, title = "天气",
                summary = "通过 HTTP 获取天气预报并用 Compose 渲染。",
                description = "一个小型 Compose 应用，通过 HTTP 加载预报并渲染逐小时 + 每日 " +
                    "视图。展示网络请求、加载/错误状态和列表。",
                category = "Android", iconId = "module.android", author = "CodeAssist",
                tags = listOf("Compose", "网络", "Coroutines"), accentColor = 0xFF3FBDD9, available = false,
            ),
            UiStoreItem(
                id = "sample:snake", kind = UiStoreItemKind.Sample, title = "贪吃蛇",
                summary = "在 Compose 画布上绘制的经典贪吃蛇游戏。",
                description = "经典贪吃蛇游戏：游戏循环、手势输入和 Canvas 绘制。一个紧凑的 " +
                    "帧驱动 UI 示例。",
                category = "游戏", iconId = "kotlin", author = "CodeAssist",
                tags = listOf("Canvas", "游戏循环", "Kotlin"), accentColor = 0xFFB487F7, available = false,
            ),
            UiStoreItem(
                id = "sample:calc", kind = UiStoreItemKind.Sample, title = "计算器",
                summary = "表达式解析计算器，包含控制台和测试。",
                description = "带表达式解析器和 JUnit 测试套件的 Java 控制台计算器。一个清晰的 " +
                    "解析与单元测试起点。",
                category = "Java", iconId = "java", author = "CodeAssist",
                tags = listOf("解析", "JUnit", "控制台"), available = false,
            ),
        )
    }
}
