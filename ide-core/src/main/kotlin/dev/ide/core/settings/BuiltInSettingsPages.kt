package dev.ide.core.settings

import dev.ide.platform.settings.SettingControl
import dev.ide.platform.settings.SettingsPage
import dev.ide.platform.settings.SettingsScope

/**
 * The built-in Settings pages, declared against the same [SettingsPage] SPI a plugin uses — so built-ins and
 * plugin pages render through one generic path. These are pure *declarations* (control lists); their effects
 * are applied centrally by the backend (it knows the built-in keys), so the hooks stay empty here. A plugin
 * page, by contrast, carries its own [SettingsPage.onChanged]/[SettingsPage.onAction] logic.
 *
 * Control keys are page-local; the host stores them under `settings.<pageId>.<key>` (app scope) — the exact
 * keys [SettingsStore] reads, so a generic write and the typed [IdeSettings] view stay in sync.
 */
object BuiltInSettingsPages {
    const val APPEARANCE = "appearance"
    const val EDITOR = "editor"
    const val COMPLETION = "completion"
    const val ANALYSIS = "analysis"
    const val BUILD = "build"
    /** App-scoped build-runtime page (distinct from the project-scoped [BUILD] page) — holds the
     *  separate-process toggle, which is app-global. See docs/build-process-isolation.md. */
    const val BUILD_RUNTIME = "buildRuntime"
    const val PRIVACY = "privacy"

    /** Toggle key on the [BUILD_RUNTIME] page: route builds/runs through the isolated `:build` process. */
    const val SEPARATE_PROCESS = "separateProcess"

    /** IntSlider key on the [BUILD_RUNTIME] page: the heap (MB) the on-device R8 (release/minify) pass runs
     *  with in a forked VM — larger than the app's own heap cap. Read by `ForkedR8Shrinker` (:ide-android),
     *  which steps down + warns in the build log if the device can't grant it. Android-only effect. */
    const val R8_MAX_HEAP = "r8MaxHeapMb"
    const val R8_MAX_HEAP_DEFAULT = 1536

    /** Choice key on the [BUILD_RUNTIME] page: where the release/minify R8 pass runs. Read by
     *  `ForkedR8Shrinker`. [R8_MODE_FORKED] (the default) runs R8 in a separate VM with more memory than the
     *  app cap, falling back to in-process if the device can't; [R8_MODE_INPROCESS] always runs in-process. */
    const val R8_MODE = "r8Mode"
    const val R8_MODE_FORKED = "forked"
    const val R8_MODE_INPROCESS = "inprocess"
    const val R8_MODE_DEFAULT = R8_MODE_FORKED

    /** App preference (NOT a user control): the largest heap (MB) a forked VM grants R8 on this device,
     *  measured once per app version in the background (`0` = forking unavailable, absent = not yet measured).
     *  The host (:ide-android) writes it; the settings UI reads it for the slider's MAX and the shrinker for
     *  its default heap, so the user can only scale DOWN from the real device limit. */
    const val R8_CEILING_PREF = "r8.detectedCeilingMb"

    /** IntSlider key on [BUILD_RUNTIME]: input size (MB) at/above which an on-device debug-dex step (the
     *  dexBuilder archive) runs in a separate VM instead of the app heap. Read by `ForkedD8Dexer` (:ide-android),
     *  and only when R8 execution is Forked VM. Android-only. Lower = safer on small heaps but more VM spawns. */
    const val DEX_OFFHEAP_MB = "dexOffHeapMb"
    const val DEX_OFFHEAP_MB_DEFAULT = 8

    /** IntSlider key on [BUILD_RUNTIME]: the most classes merged into Dalvik bytecode in one batch on a large
     *  app (debug, native multidex). Read by `DexMergeTask` via the on-device `AndroidDeviceTools.mergeChunkProvider`.
     *  Smaller = lower peak memory + slightly larger APK; larger = tighter packing + more memory. Android-only. */
    const val DEX_MERGE_BATCH = "dexMergeBatch"
    const val DEX_MERGE_BATCH_DEFAULT = 6000

    /** IntSlider key on [BUILD_RUNTIME]: the most forked dexing VMs (the dex merge / off-heap archive) allowed
     *  to run at once. `0` = auto (sized from available device RAM ÷ the forked-VM heap). Read by `ForkedD8Dexer`
     *  (:ide-android), and only when R8 execution is Forked VM. Higher = faster merges on roomy devices but more
     *  RAM committed at once; `0`/lower is safer on tight devices. Android-only. */
    const val DEX_FORK_CONCURRENCY = "dexForkConcurrency"
    const val DEX_FORK_CONCURRENCY_DEFAULT = 0

    // Keys the backend special-cases (routed to a non-generic-store effect).
    const val CONFLICT_POLICY = "conflictPolicy"
    const val ANALYTICS = "analytics"
    const val CLEAR_CACHES = "clearCaches"
    const val VIEW_LOGS = "viewLogs"
    const val BACKUP = "backup"

    /** The conflict-policy choice values (mirror `dev.ide.deps.ConflictPolicy`). */
    const val CONFLICT_NEWEST = "newest"
    const val CONFLICT_PINNED = "pinned"
    const val CONFLICT_FAIL = "failOnConflict"

    private val d = IdeSettings()

    /** All built-in pages in display order. [analyticsAvailable] gates the analytics toggle on the Privacy page.
     *  Code Style is not here: it has its own dedicated screen (the formatting profiles are per-language). */
    fun all(analyticsAvailable: Boolean): List<SettingsPage> = listOf(
        appearance, editor, completion, analysis, build, buildRuntime, privacy(analyticsAvailable),
    )

    private val appearance = page(APPEARANCE, "外观", "eye", 0) {
        listOf(
            SettingControl.Choice(
                "themeMode", "主题", "使用固定主题或跟随操作系统",
                default = d.themeMode,
                options = listOf(
                    SettingControl.Choice.Option(IdeSettings.THEME_LIGHT, "浅色"),
                    SettingControl.Choice.Option(IdeSettings.THEME_DARK, "深色"),
                    SettingControl.Choice.Option(IdeSettings.THEME_SYSTEM, "跟随系统"),
                ),
            ),
            SettingControl.Choice(
                "accent", "强调色", "界面高亮颜色",
                default = d.accent,
                options = listOf(
                    SettingControl.Choice.Option(IdeSettings.ACCENT_VIOLET, "紫色"),
                    SettingControl.Choice.Option(IdeSettings.ACCENT_TEAL, "青色"),
                    SettingControl.Choice.Option(IdeSettings.ACCENT_ORANGE, "橙色（经典）"),
                ),
            ),
        )
    }

    private val editor = page(EDITOR, "编辑器", "code", 10) {
        listOf(
            SettingControl.IntSlider("fontScale", "字体大小", default = (d.editorFontScale * 100).toInt(), min = 70, max = 200, step = 5, unit = "%"),
            SettingControl.Choice(
                "codeFont", "代码字体",
                default = d.codeFont,
                options = listOf(
                    SettingControl.Choice.Option(IdeSettings.CODE_FONT_JETBRAINS, "JetBrains Mono"),
                    SettingControl.Choice.Option(IdeSettings.CODE_FONT_MONOSPACE, "系统等宽字体"),
                ),
            ),
            SettingControl.Toggle("fontLigatures", "字体连字", "代码字体支持时渲染编程连字（-> != >= …）", default = d.fontLigatures),
            SettingControl.Toggle("inlayHints", "内联提示", "以内联方式显示推断类型和参数名提示", default = d.inlayHints),
            SettingControl.Toggle("semanticHighlighting", "语义高亮", "在词法高亮之上叠加类型感知着色", default = d.semanticHighlighting),
            SettingControl.Toggle("codeFolding", "代码折叠", "折叠导入、代码体和块注释", default = d.codeFolding),
            SettingControl.Toggle("wordWrap", "自动换行", "在视口边缘软换行长行，而不是水平滚动", default = d.wordWrap),
            SettingControl.Toggle("wrapIndent", "缩进换行行", "启用自动换行时，让续行与原缩进对齐", default = d.wrapIndent),
            SettingControl.Toggle("twoAxisScroll", "双轴滚动", "触控时可向任意方向拖动，同时滚动两个轴", default = d.twoAxisScroll, group = "手势"),
            SettingControl.Toggle("pinchZoom", "捏合缩放", "用双指捏合调整代码字体大小", default = d.pinchZoom, group = "手势"),
            SettingControl.Toggle("softKeyboardSuggestions", "键盘建议", "允许软键盘自动更正、建议和自动空格（普通键盘行为）。若要原始代码输入可关闭它，避免输入 . 后自动插入空格，但会失去建议栏。", default = d.softKeyboardSuggestions, group = "键盘"),
        )
    }

    private val completion = page(COMPLETION, "代码补全", "sparkle", 20) {
        listOf(
            SettingControl.Toggle("autoPopup", "自动显示建议", "输入时弹出列表（关闭后仅 Ctrl-Space 触发）", default = d.completionAutoPopup),
            SettingControl.Toggle("postfixTemplates", "后缀模板", "提供 .val / .if / .notnull / … 补全", default = d.postfixTemplates),
            SettingControl.Toggle("wordCompletion", "单词补全", "将文件中已有单词作为后备补全", default = d.wordCompletion),
            SettingControl.Toggle("aiInlineCompletion", "AI 内联补全", "在光标后显示 AI 生成的灰色候选，可点接受插入。接口地址和密钥复用 AI 助手配置。", default = d.aiInlineCompletion, group = "AI"),
            SettingControl.Text("aiInlineModel", "AI 补全模型", "留空则使用 AI 助手模型", default = d.aiInlineModel, placeholder = "gpt-5.5", group = "AI"),
            SettingControl.Choice(
                "aiInlineReasoningEffort", "AI 思考深度", "自动补全建议使用低思考深度以降低延迟",
                default = d.aiInlineReasoningEffort,
                options = listOf(
                    SettingControl.Choice.Option("low", "低"),
                    SettingControl.Choice.Option("medium", "中"),
                    SettingControl.Choice.Option("high", "高"),
                ),
                group = "AI",
            ),
            SettingControl.IntSlider("delayMs", "自动弹出延迟", "按键后多久显示列表", default = d.completionDelayMs, min = IdeSettings.MIN_COMPLETION_DELAY_MS, max = IdeSettings.MAX_COMPLETION_DELAY_MS, step = 10, unit = "ms", advanced = true),
            SettingControl.IntSlider("maxItems", "最大建议数", default = d.completionMaxItems, min = IdeSettings.MIN_COMPLETION_MAX_ITEMS, max = IdeSettings.MAX_COMPLETION_MAX_ITEMS, step = 10, advanced = true),
        )
    }

    private val analysis = page(ANALYSIS, "分析与检查", "lightbulb", 30) {
        listOf(
            SettingControl.Toggle("onTheFly", "实时分析", "输入时显示诊断（关闭后仅构建时显示）", default = d.analyzeOnTheFly),
            SettingControl.IntSlider("reparseDelayMs", "重新解析延迟", "按键后等待多长时间再重新分析", default = d.reparseDelayMs, min = IdeSettings.MIN_REPARSE_DELAY_MS, max = IdeSettings.MAX_REPARSE_DELAY_MS, step = 50, unit = "ms", advanced = true),
        )
    }

    private val build = page(BUILD, "构建与依赖", "hammer", 40, scope = SettingsScope.PROJECT) {
        listOf(
            SettingControl.Choice(
                CONFLICT_POLICY, "依赖冲突", "依赖图中请求两个版本时采用哪个版本",
                default = CONFLICT_NEWEST,
                options = listOf(
                    SettingControl.Choice.Option(CONFLICT_NEWEST, "最新版本"),
                    SettingControl.Choice.Option(CONFLICT_PINNED, "直接依赖优先"),
                    SettingControl.Choice.Option(CONFLICT_FAIL, "冲突时失败"),
                ),
            ),
        )
    }

    // App-global (not per-project): running the build in its own process is about this device's memory
    // headroom + your robustness preference, the same for every project. Default ON. The effect is applied
    // by the backend (it reads `settings.buildRuntime.separateProcess`); see docs/build-process-isolation.md.
    private val buildRuntime = page(BUILD_RUNTIME, "构建运行时", "hammer", 45) {
        listOf(
            SettingControl.Toggle(
                SEPARATE_PROCESS, "在独立进程中构建",
                "在隔离进程中运行构建和你的程序，避免内存溢出崩溃拖垮 IDE。关闭后在进程内构建（内存占用更少，但无隔离）。下次打开项目时生效。",
                default = true,
            ),
            // The Build Runtime page's R8 controls are rendered dynamically by SettingsBackend (the slider's
            // max is this device's measured forked-VM limit, and it's hidden in In-process mode), so these
            // static descriptors only supply keys / scope / defaults — their descriptions aren't shown.
            SettingControl.Choice(
                R8_MODE, "R8 执行方式", null,
                default = R8_MODE_DEFAULT,
                options = listOf(
                    SettingControl.Choice.Option(R8_MODE_FORKED, "独立 VM"),
                    SettingControl.Choice.Option(R8_MODE_INPROCESS, "进程内"),
                ),
            ),
            SettingControl.IntSlider(
                R8_MAX_HEAP, "R8 独立 VM 堆大小", null,
                default = R8_MAX_HEAP_DEFAULT, min = 768, max = 4096, step = 128, unit = "MB",
            ),
            // Rendered dynamically by SettingsBackend (rich descriptions); these descriptors only carry the
            // key / default / scope for the write path. Debug-build dexing memory knobs (R8 above = release).
            SettingControl.IntSlider(
                DEX_OFFHEAP_MB, "堆外 dex 阈值", null,
                default = DEX_OFFHEAP_MB_DEFAULT, min = 2, max = 64, step = 2, unit = "MB", advanced = true,
            ),
            SettingControl.IntSlider(
                DEX_MERGE_BATCH, "Dex 合并批量大小", null,
                default = DEX_MERGE_BATCH_DEFAULT, min = 1000, max = 20000, step = 1000, advanced = true,
            ),
            SettingControl.IntSlider(
                DEX_FORK_CONCURRENCY, "最大并发 dex VM 数", null,
                default = DEX_FORK_CONCURRENCY_DEFAULT, min = 0, max = 4, step = 1, advanced = true,
            ),
        )
    }

    private fun privacy(analyticsAvailable: Boolean) = page(PRIVACY, "隐私与数据", "info", 50) {
        buildList {
            if (analyticsAvailable) {
                add(SettingControl.Toggle(ANALYTICS, "分享性能分析", "仅匿名性能指标——绝不包含你的代码或文件名", default = false, group = "隐私"))
            }
            add(SettingControl.Action(CLEAR_CACHES, "清理缓存", "释放可重新生成的依赖/语言/预览缓存（绝不删除源码）", buttonLabel = "清理", group = "存储"))
            add(SettingControl.Action(VIEW_LOGS, "查看日志", "最近的编辑器、分析和构建活动", buttonLabel = "打开", group = "存储"))
            add(SettingControl.Action(BACKUP, "备份项目", "将所有项目导出到一个 zip 文件", buttonLabel = "备份", group = "存储"))
        }
    }

    /** Small builder for an anonymous built-in [SettingsPage] (empty hooks; effects are applied by the backend). */
    private fun page(
        id: String, title: String, iconId: String, order: Int,
        scope: SettingsScope = SettingsScope.APPLICATION,
        controlsProvider: () -> List<SettingControl>,
    ): SettingsPage = object : SettingsPage {
        override val id = id
        override val title = title
        override val iconId = iconId
        override val scope = scope
        override val order = order
        override fun controls() = controlsProvider()
    }

    /** Whether [page] is the built-in Analysis page that wants the inspection list appended. */
    fun isInspectionsPage(page: SettingsPage): Boolean = page.id == ANALYSIS
}
