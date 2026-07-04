package dev.ide.core.backend

import dev.ide.analysis.AnalyzerId
import dev.ide.core.BackendContext
import dev.ide.core.completion.CompletionOptions
import dev.ide.core.settings.BuiltInSettingsPages
import dev.ide.core.settings.CodeStyleSettings
import dev.ide.core.settings.IdeSettings
import dev.ide.core.settings.SettingsStore
import dev.ide.lang.dom.Severity
import dev.ide.platform.settings.PreferenceReader
import dev.ide.platform.settings.SettingControl
import dev.ide.platform.settings.SettingsPage
import dev.ide.platform.settings.SettingsScope
import dev.ide.ui.backend.SettingsService
import dev.ide.ui.backend.UiAccent
import dev.ide.ui.backend.UiCodeStyle
import dev.ide.ui.backend.UiInspection
import dev.ide.ui.backend.UiSeverity
import dev.ide.ui.backend.UiSettingControl
import dev.ide.ui.backend.UiSettings
import dev.ide.ui.backend.UiSettingsPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [SettingsService]: the app-global typed settings ([settingsStore], prefs-backed) + per-project settings
 * (engine-backed) + the extensible settings-page model + the inspection catalogue + app preferences. Writes
 * route by page scope and re-apply the effect. The analytics toggle is special-cased to the consent decision
 * (cross-cutting; reached through [BackendContext]).
 */
internal class SettingsBackend(private val ctx: BackendContext) : SettingsService {

    override fun preference(key: String): String? = ctx.manager?.preference(key)

    override fun setPreference(key: String, value: String) {
        ctx.manager?.setPreference(key, value)
    }

    private val settingsStore = SettingsStore(
        get = { k -> ctx.manager?.preference(k) },
        set = { k, v -> ctx.manager?.setPreference(k, v) },
    )

    override fun settings(): UiSettings = settingsStore.load().toUi()

    override fun settingsPages(): List<UiSettingsPage> {
        val svc = ctx.servicesOrNull
        val builtIn = BuiltInSettingsPages.all(ctx.analyticsAvailable())
        // With no project open (the picker's Settings & Tools hub) only the app-scoped built-in pages are
        // available — the project-scoped built-ins (e.g. Build) and any plugin-contributed pages need an
        // engine, so they're added only once a project is open. (The hub shows the app pages; the in-project
        // Project Settings surface shows the project pages — see UiSettingsPage.scope.)
        val pages = if (svc == null) builtIn.filter { it.scope != SettingsScope.PROJECT }
        else builtIn + svc.settingsPages()
        return pages.sortedBy { it.order }.map { toUiPage(it) }
    }

    override fun setSetting(pageId: String, key: String, value: String) {
        // The analytics toggle isn't a generic-store value — it routes to the persisted consent decision.
        if (pageId == BuiltInSettingsPages.PRIVACY && key == BuiltInSettingsPages.ANALYTICS) {
            ctx.setAnalyticsConsent(value.toBooleanStrictOrNull() ?: false)
            return
        }
        val page = findPage(pageId) ?: return
        val fullKey = settingKey(pageId, key)
        if (page.scope == SettingsScope.PROJECT) ctx.servicesOrNull?.setProjectPref(fullKey, value)
        else ctx.manager?.setPreference(fullKey, value)
        applyAfterChange(page, key)
    }

    override suspend fun invokeSettingAction(pageId: String, key: String): String? {
        if (pageId == BuiltInSettingsPages.PRIVACY && key == BuiltInSettingsPages.CLEAR_CACHES) {
            return withContext(Dispatchers.IO) { ctx.servicesOrNull?.clearProjectCaches() }
        }
        // viewLogs / backup are wired to UI flows by the screen, not here.
        val page = findPage(pageId) ?: return null
        return if (isBuiltIn(pageId)) null else page.onAction(key, scopedReader(page))
    }

    override fun codeStyle(languageId: String): UiCodeStyle = settingsStore.loadCodeStyle(languageId).toUi()

    override fun setCodeStyle(languageId: String, style: UiCodeStyle) {
        settingsStore.saveCodeStyle(languageId, style.toSettings())
    }

    override suspend fun formatStylePreview(languageId: String, style: UiCodeStyle): String =
        withContext(Dispatchers.Default) {
            ctx.servicesOrNull?.formatStylePreview(languageId, style.toSettings().toFormatStyle()) ?: ""
        }

    private fun CodeStyleSettings.toUi() = UiCodeStyle(
        preset = preset, indentSize = indentSize, continuationIndent = continuationIndent,
        maxLineLength = maxLineLength, useTabs = useTabs, braceStyle = braceStyle,
        spaceBeforeParens = spaceBeforeParens, spaceWithinParens = spaceWithinParens,
        spaceAfterComma = spaceAfterComma, spaceAroundOperators = spaceAroundOperators,
        spaceBeforeBrace = spaceBeforeBrace, blankLinesToKeep = blankLinesToKeep,
        wrapMethodParameters = wrapMethodParameters, wrapMethodArguments = wrapMethodArguments,
        wrapChainedCalls = wrapChainedCalls, wrapBinaryExpressions = wrapBinaryExpressions,
        blankLinesAfterImports = blankLinesAfterImports, blankLinesBeforeMethod = blankLinesBeforeMethod,
        blankLinesBeforeField = blankLinesBeforeField, blankLinesBeforeFirstMember = blankLinesBeforeFirstMember,
        blankLinesBetweenTypes = blankLinesBetweenTypes, spaceBeforeSemicolon = spaceBeforeSemicolon,
        spaceAroundLambdaArrow = spaceAroundLambdaArrow, spaceAroundTernary = spaceAroundTernary,
        spaceAfterTypeCast = spaceAfterTypeCast, formatComments = formatComments, wrapComments = wrapComments,
    )

    private fun UiCodeStyle.toSettings() = CodeStyleSettings(
        preset = preset, indentSize = indentSize, continuationIndent = continuationIndent,
        maxLineLength = maxLineLength, useTabs = useTabs, braceStyle = braceStyle,
        spaceBeforeParens = spaceBeforeParens, spaceWithinParens = spaceWithinParens,
        spaceAfterComma = spaceAfterComma, spaceAroundOperators = spaceAroundOperators,
        spaceBeforeBrace = spaceBeforeBrace, blankLinesToKeep = blankLinesToKeep,
        wrapMethodParameters = wrapMethodParameters, wrapMethodArguments = wrapMethodArguments,
        wrapChainedCalls = wrapChainedCalls, wrapBinaryExpressions = wrapBinaryExpressions,
        blankLinesAfterImports = blankLinesAfterImports, blankLinesBeforeMethod = blankLinesBeforeMethod,
        blankLinesBeforeField = blankLinesBeforeField, blankLinesBeforeFirstMember = blankLinesBeforeFirstMember,
        blankLinesBetweenTypes = blankLinesBetweenTypes, spaceBeforeSemicolon = spaceBeforeSemicolon,
        spaceAroundLambdaArrow = spaceAroundLambdaArrow, spaceAroundTernary = spaceAroundTernary,
        spaceAfterTypeCast = spaceAfterTypeCast, formatComments = formatComments, wrapComments = wrapComments,
    )

    override fun inspections(): List<UiInspection> {
        val svc = ctx.servicesOrNull ?: return emptyList()
        val profile = svc.inspectionProfile()
        return svc.registeredAnalyzers().map { a ->
            UiInspection(
                id = a.id.value,
                displayName = a.displayName,
                language = a.languages.firstOrNull()?.id?.let(::prettyLang) ?: "全部",
                tier = a.tier.name.lowercase().replaceFirstChar { it.uppercase() },
                enabled = profile.isEnabled(a.id),
                severity = (profile.severityOverrides[a.id] ?: a.defaultSeverity).toUiSeverity(),
                defaultSeverity = a.defaultSeverity.toUiSeverity(),
            )
        }.sortedWith(compareBy({ it.language }, { it.displayName }))
    }

    override fun setInspection(id: String, enabled: Boolean, severity: UiSeverity) {
        ctx.servicesOrNull?.setInspection(AnalyzerId(id), enabled, severity.toDomSeverity())
    }

    // --- settings helpers ---

    private fun settingKey(pageId: String, key: String) = "settings.$pageId.$key"

    private fun findPage(pageId: String): SettingsPage? =
        BuiltInSettingsPages.all(ctx.analyticsAvailable()).firstOrNull { it.id == pageId }
            ?: ctx.servicesOrNull?.settingsPages()?.firstOrNull { it.id == pageId }

    private fun isBuiltIn(pageId: String): Boolean =
        BuiltInSettingsPages.all(ctx.analyticsAvailable()).any { it.id == pageId }

    /** Read a control's raw stored value (scoped store), or null to fall back to the control default. */
    private fun readSetting(pageId: String, key: String, project: Boolean): String? {
        val fullKey = settingKey(pageId, key)
        return if (project) ctx.servicesOrNull?.projectPref(fullKey) else ctx.manager?.preference(fullKey)
    }

    /** A page-local reader handed to a plugin page's hooks (it reads its own control keys). */
    private fun scopedReader(page: SettingsPage): PreferenceReader = object : PreferenceReader {
        override fun raw(key: String) = readSetting(page.id, key, page.scope == SettingsScope.PROJECT)
    }

    private fun applyAfterChange(page: SettingsPage, key: String) {
        when (page.id) {
            // Completion knobs feed the engine; everything else built-in is applied UI-side (the UI re-reads
            // settings()), so there's nothing to push here.
            BuiltInSettingsPages.COMPLETION -> ctx.servicesOrNull?.let { it.completionOptions = currentCompletionOptions() }
            BuiltInSettingsPages.BUILD -> if (key == BuiltInSettingsPages.CONFLICT_POLICY) {
                ctx.servicesOrNull?.setConflictPolicy(parseConflictPolicy(readSetting(page.id, key, project = true)))
            }
            else -> if (!isBuiltIn(page.id)) page.onChanged(key, scopedReader(page)) // plugin pages react themselves
        }
    }

    private fun currentCompletionOptions(): CompletionOptions {
        val s = settingsStore.load()
        return CompletionOptions(
            maxItems = s.completionMaxItems,
            postfixTemplates = s.postfixTemplates,
            wordCompletion = s.wordCompletion,
        )
    }

    private fun parseConflictPolicy(value: String?): dev.ide.deps.ConflictPolicy = when (value) {
        BuiltInSettingsPages.CONFLICT_PINNED -> dev.ide.deps.ConflictPolicy.PINNED
        BuiltInSettingsPages.CONFLICT_FAIL -> dev.ide.deps.ConflictPolicy.FAIL_ON_CONFLICT
        else -> dev.ide.deps.ConflictPolicy.NEWEST
    }

    private fun toUiPage(page: SettingsPage): UiSettingsPage {
        val project = page.scope == SettingsScope.PROJECT
        val controls =
            if (page.id == BuiltInSettingsPages.BUILD_RUNTIME) buildRuntimeControls()
            else page.controls().map { toUiControl(page.id, it, project) }
        return UiSettingsPage(
            id = page.id,
            title = page.title,
            iconId = page.iconId,
            scope = if (project) "项目" else "app",
            controls = controls,
            inspectionsSection = BuiltInSettingsPages.isInspectionsPage(page),
        )
    }

    /**
     * The Build Runtime page is rendered dynamically: the R8 forked-VM heap slider's MAX is this device's
     * measured forked-VM ceiling (so the user can only scale DOWN from the real limit), the slider is HIDDEN
     * in In-process mode (replaced by the app's memory limit in the mode description), and a warning shows if a
     * saved value exceeds the device limit. The ceiling is read from [BuiltInSettingsPages.R8_CEILING_PREF]
     * (measured once in the background by the host): null = not yet measured, 0 = forking unavailable here.
     */
    private val R8_MIN_MB = 768
    private val FALLBACK_R8_MAX_MB = 2048 // slider max before the device limit has been measured

    private fun buildRuntimeControls(): List<UiSettingControl> {
        val pid = BuiltInSettingsPages.BUILD_RUNTIME
        val appHeapMb = (Runtime.getRuntime().maxMemory() / (1024L * 1024L)).toInt()
        val ceiling = ctx.manager?.preference(BuiltInSettingsPages.R8_CEILING_PREF)?.trim()?.toIntOrNull()
        val mode = ctx.manager?.preference(settingKey(pid, BuiltInSettingsPages.R8_MODE)) ?: BuiltInSettingsPages.R8_MODE_DEFAULT
        val sepOn = ctx.manager?.preference(settingKey(pid, BuiltInSettingsPages.SEPARATE_PROCESS))?.toBooleanStrictOrNull() ?: true

        val out = ArrayList<UiSettingControl>()
        out += UiSettingControl.Toggle(
            BuiltInSettingsPages.SEPARATE_PROCESS, "在独立进程中构建",
            "在隔离进程中运行构建和你的程序，避免内存溢出崩溃拖垮 IDE。关闭后在进程内构建（内存占用更少，但无隔离）。下次打开项目时生效。",
            sepOn, false, null,
        )

        val modeDesc = when {
            mode == BuiltInSettingsPages.R8_MODE_INPROCESS ->
                "R8 在 IDE 进程内运行，受应用内存限制（约 $appHeapMb MB）。选择独立 VM 可让 R8 在单独 VM 中获得更多内存。"
            ceiling == 0 ->
                "此设备无法在独立 VM 中运行 R8，因此会在进程内运行（约 $appHeapMb MB）。大型应用可能内存不足，此处没有可调整项。"
            else ->
                "独立 VM（默认）会在单独 VM 中运行 R8，内存高于此应用约 $appHeapMb MB 的限制，避免大型应用内存不足；如果设备不支持，会回退到进程内。仅 Android 可用。"
        }
        out += UiSettingControl.Choice(
            BuiltInSettingsPages.R8_MODE, "R8 执行方式", modeDesc, mode,
            listOf(
                UiSettingControl.Choice.Option(BuiltInSettingsPages.R8_MODE_FORKED, "独立 VM"),
                UiSettingControl.Choice.Option(BuiltInSettingsPages.R8_MODE_INPROCESS, "进程内"),
            ),
            false, null,
        )

        // The heap slider applies only to the forked VM; hide it in In-process and when forking is unavailable.
        if (mode != BuiltInSettingsPages.R8_MODE_INPROCESS && ceiling != 0) {
            val max = (ceiling ?: FALLBACK_R8_MAX_MB).coerceAtLeast(R8_MIN_MB)
            val saved = ctx.manager?.preference(settingKey(pid, BuiltInSettingsPages.R8_MAX_HEAP))?.trim()?.toIntOrNull()
            // Default to the device limit (the max) so the user only ever scales DOWN; clamp the displayed value.
            val value = (saved ?: max).coerceIn(R8_MIN_MB, max)
            val limitNote = if (ceiling != null) "此设备限制为 $ceiling MB。" else "正在测量此设备限制…"
            val warn = if (ceiling != null && saved != null && saved > ceiling)
                " ⚠ 已保存值（$saved MB）高于设备限制；R8 将使用 $ceiling MB。"
            else ""
            out += UiSettingControl.Slider(
                BuiltInSettingsPages.R8_MAX_HEAP, "R8 独立 VM 堆大小",
                "R8 独立 VM 的堆大小。$limitNote$warn",
                value, R8_MIN_MB, max, 128, "MB", false, null,
            )
        }

        // Debug-build dexing memory knobs (the R8 controls above govern the release/minify path). Both are
        // advanced and Android-only, grouped apart so they don't read as part of R8.
        val dexGroup = "调试构建（dex）"
        val forkable = mode != BuiltInSettingsPages.R8_MODE_INPROCESS && ceiling != 0
        val offHeap = (ctx.manager?.preference(settingKey(pid, BuiltInSettingsPages.DEX_OFFHEAP_MB))?.trim()?.toIntOrNull()
            ?: BuiltInSettingsPages.DEX_OFFHEAP_MB_DEFAULT).coerceIn(2, 64)
        val offHeapDesc = if (forkable)
            "全量构建时，dex 工具会把整个项目（以及大型库）转换为 Dalvik 字节码——这通常是在 IDE 内执行的重任务。当其中某个步骤达到此大小时，会移动到独立 VM（与 R8 使用的相同）中执行，避免占用 IDE 约 $appHeapMb MB 的堆。较低更适合低内存设备但会启动更多短生命周期 VM（略慢）；较高会减少 VM 数量但增加 IDE 压力。小改动始终在进程内执行。"
        else
            "全量构建时将大型 dex 步骤从 IDE 堆移动到独立 VM。R8 执行方式为进程内（或设备无法启动独立 VM）时不生效——此时所有 dex 都在进程内执行。"
        out += UiSettingControl.Slider(
            BuiltInSettingsPages.DEX_OFFHEAP_MB, "堆外 dex 阈值",
            offHeapDesc, offHeap, 2, 64, 2, "MB", true, dexGroup,
        )

        val mergeBatch = (ctx.manager?.preference(settingKey(pid, BuiltInSettingsPages.DEX_MERGE_BATCH))?.trim()?.toIntOrNull()
            ?: BuiltInSettingsPages.DEX_MERGE_BATCH_DEFAULT).coerceIn(1000, 20000)
        out += UiSettingControl.Slider(
            BuiltInSettingsPages.DEX_MERGE_BATCH, "Dex 合并批量大小",
            "对于超大型应用，最终 dex 步骤会分批合并 class，避免一次性将所有内容载入内存。较小批量可降低内存占用（适合低内存设备），但 APK 可能略大；较大批量压缩更紧凑，但每次合并需要更多内存。大多数应用不会触发此项——通常在超过数千个 class 后才生效。",
            mergeBatch, 1000, 20000, 1000, "个 class", true, dexGroup,
        )

        val forkConc = (ctx.manager?.preference(settingKey(pid, BuiltInSettingsPages.DEX_FORK_CONCURRENCY))?.trim()?.toIntOrNull()
            ?: BuiltInSettingsPages.DEX_FORK_CONCURRENCY_DEFAULT).coerceIn(0, 4)
        val forkConcDesc = if (forkable)
            "允许同时运行多少个独立 dex VM。dex 合并会拆分到其中几个 VM 中，因此多个库可以并行 dex，而不是逐个处理。0 = 自动（根据设备可用内存和上方 VM 堆大小选择）。内存充足的设备上数值越高越快，但会一次占用更多内存；较低（或 0）对内存紧张设备更安全。下次构建开始时生效。"
        else
            "限制同时运行的独立 dex VM 数。R8 执行方式为进程内（或设备无法启动独立 VM）时不生效——此时所有 dex 都在进程内执行。"
        out += UiSettingControl.Slider(
            BuiltInSettingsPages.DEX_FORK_CONCURRENCY, "最大并发 dex VM 数",
            forkConcDesc, forkConc, 0, 4, 1, null, true, dexGroup,
        )
        return out
    }

    private fun toUiControl(pageId: String, c: SettingControl, project: Boolean): UiSettingControl {
        val raw = readSetting(pageId, c.key, project)
        return when (c) {
            is SettingControl.Toggle -> {
                val v = if (pageId == BuiltInSettingsPages.PRIVACY && c.key == BuiltInSettingsPages.ANALYTICS) ctx.analyticsConsent() == true
                else raw?.toBooleanStrictOrNull() ?: c.default
                UiSettingControl.Toggle(c.key, c.title, c.description, v, c.advanced, c.group)
            }
            is SettingControl.IntSlider -> UiSettingControl.Slider(
                c.key, c.title, c.description, (raw?.trim()?.toIntOrNull() ?: c.default).coerceIn(c.min, c.max),
                c.min, c.max, c.step, c.unit, c.advanced, c.group,
            )
            is SettingControl.Choice -> UiSettingControl.Choice(
                c.key, c.title, c.description, raw ?: c.default,
                c.options.map { UiSettingControl.Choice.Option(it.value, it.label) }, c.advanced, c.group,
            )
            is SettingControl.Text -> UiSettingControl.Text(c.key, c.title, c.description, raw ?: c.default, c.placeholder, c.advanced, c.group)
            is SettingControl.Action -> UiSettingControl.Action(c.key, c.title, c.description, c.buttonLabel, c.destructive, c.advanced, c.group)
        }
    }

    private fun IdeSettings.toUi(): UiSettings = UiSettings(
        themeMode = themeMode,
        accent = when (accent) {
            IdeSettings.ACCENT_TEAL -> UiAccent.Teal
            IdeSettings.ACCENT_ORANGE -> UiAccent.Orange
            else -> UiAccent.Violet
        },
        editorFontScale = editorFontScale,
        codeFont = codeFont,
        fontLigatures = fontLigatures,
        inlayHints = inlayHints,
        semanticHighlighting = semanticHighlighting,
        codeFolding = codeFolding,
        completionAutoPopup = completionAutoPopup,
        completionDelayMs = completionDelayMs,
        completionMaxItems = completionMaxItems,
        postfixTemplates = postfixTemplates,
        wordCompletion = wordCompletion,
        analyzeOnTheFly = analyzeOnTheFly,
        reparseDelayMs = reparseDelayMs,
        wordWrap = wordWrap,
        wrapIndent = wrapIndent,
        twoAxisScroll = twoAxisScroll,
        pinchZoom = pinchZoom,
        softKeyboardSuggestions = softKeyboardSuggestions,
        formatOnSave = formatOnSave,
    )

    private fun Severity.toUiSeverity(): UiSeverity = when (this) {
        Severity.ERROR -> UiSeverity.Error
        Severity.WARNING -> UiSeverity.Warning
        Severity.INFO -> UiSeverity.Info
        Severity.HINT -> UiSeverity.Hint
    }

    private fun UiSeverity.toDomSeverity(): Severity = when (this) {
        UiSeverity.Error -> Severity.ERROR
        UiSeverity.Warning -> Severity.WARNING
        UiSeverity.Info -> Severity.INFO
        UiSeverity.Hint -> Severity.HINT
    }

    private fun prettyLang(id: String): String = when (id.lowercase()) {
        "java" -> "Java"
        "kotlin" -> "Kotlin"
        "xml" -> "XML"
        else -> id.replaceFirstChar { it.uppercase() }
    }
}
