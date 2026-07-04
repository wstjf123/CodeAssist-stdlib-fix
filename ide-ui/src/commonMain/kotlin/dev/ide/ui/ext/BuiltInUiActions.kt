package dev.ide.ui.ext

import dev.ide.ui.backend.UiActionPlaces

/**
 * The app's built-in UI-side actions, registered through [UiActionRegistry] the same way an in-UI plugin
 * would. Currently the "更多" menu's secondary actions; the command palette's UI-navigation commands and the
 * top-bar's stateful buttons move here in later increments.
 */
object BuiltInUiActions {
    private var registered = false

    /** Idempotent: registers the built-ins once for the process. */
    fun ensureRegistered() {
        if (registered) return
        registered = true

        val more = setOf(UiActionPlaces.MORE_MENU)
        val palette = UiActionPlaces.COMMAND_PALETTE
        val moreAndPalette = setOf(UiActionPlaces.MORE_MENU, palette)

        UiActionRegistry.register(
            SimpleUiAction("ui.hub", "设置与工具", moreAndPalette, "设置 · 代码样式 · SDK 管理器 · 密钥库管理器", "gear", 10) {
                it.navigate(UiDestinations.HUB)
            },
        )
        UiActionRegistry.register(
            SimpleUiAction("ui.modules", "模块", more, "添加/移除模块 · Java 版本 · 依赖 · 仓库", "layers", 20) {
                it.navigate(UiDestinations.MODULES)
            },
        )
        UiActionRegistry.register(
            SimpleUiAction("ui.dependencies", "管理依赖", setOf(palette), iconId = "layers", order = 25) {
                it.navigate(UiDestinations.DEPENDENCIES)
            },
        )
        UiActionRegistry.register(
            SimpleUiAction("ui.reindex", "重新索引项目", more, "重建符号与补全索引", "refresh", 40) {
                it.backend.search.reindex()
            },
        )
        UiActionRegistry.register(
            SimpleUiAction("ui.logs", "查看日志", more, "编辑器、分析与构建日志——出现问题时可用于分享", "terminal", 50) {
                it.navigate(UiDestinations.LOGS)
            },
        )
        UiActionRegistry.register(
            SimpleUiAction("ui.toggleTheme", "切换主题", moreAndPalette, "在浅色和深色主题之间切换", "eye", 60) {
                it.toggleTheme()
            },
        )
        UiActionRegistry.register(
            SimpleUiAction("ui.closeProject", "关闭项目", more, "返回所有项目", "close", 70) {
                it.navigate(UiDestinations.PROJECTS)
            },
        )
    }
}
