package dev.ide.core.actions

import dev.ide.core.ApplicationEnvironment
import dev.ide.platform.ExtensionRegistry
import dev.ide.platform.PluginId
import dev.ide.plugin.action.ActionPlaces
import dev.ide.plugin.action.ActionResult
import dev.ide.plugin.action.SimpleAction
import dev.ide.plugin.action.UI_ACTION_EP

/**
 * The host's built-in [dev.ide.plugin.action.IdeAction]s — pure engine commands surfaced through the action
 * registry, the same way a plugin would contribute them. Registered ONCE, app-global, from
 * [ApplicationEnvironment]; each action resolves the open project through [ApplicationEnvironment.activeEngine]
 * at invoke time (it fires outside any service scope), so a single registration serves every opened project.
 *
 * Scope note: only commands that are pure engine operations (no UI navigation / app-state change) live here
 * in Phase A; the navigation-heavy menus (the "更多" sheet, the stateful top-bar buttons) move to the action
 * model once the UI-side action registry lands (see `docs/ui-extensibility-and-plugin-api.md`, Phase B).
 */
object BuiltInActions {
    val PLUGIN = PluginId("ide-core-actions")

    fun register(extensions: ExtensionRegistry, env: ApplicationEnvironment) {
        // Command palette: engine commands. These need no UI effect — they act on the engine and report a
        // status message the palette surfaces. The palette renders these through [IdeBackend.actionsFor],
        // replacing the previously-hardcoded entries.
        extensions.register(
            UI_ACTION_EP,
            SimpleAction(
                id = "ide.runBuild",
                text = "运行构建",
                places = setOf(ActionPlaces.COMMAND_PALETTE),
                iconId = "run",
                order = 70,
            ) {
                val s = env.activeEngine ?: return@SimpleAction ActionResult.message("未打开项目")
                s.build.runBuild(); ActionResult.message("构建已开始")
            },
            PLUGIN,
        )
        extensions.register(
            UI_ACTION_EP,
            SimpleAction(
                id = "ide.stopBuild",
                text = "停止构建",
                places = setOf(ActionPlaces.COMMAND_PALETTE),
                iconId = "stop",
                order = 71,
            ) {
                val s = env.activeEngine ?: return@SimpleAction ActionResult.message("未打开项目")
                s.build.stopBuild(); ActionResult.message("构建已停止")
            },
            PLUGIN,
        )
        extensions.register(
            UI_ACTION_EP,
            SimpleAction(
                id = "ide.reindex",
                text = "重新索引项目",
                places = setOf(ActionPlaces.COMMAND_PALETTE),
                iconId = "refresh",
                order = 80,
            ) {
                val s = env.activeEngine ?: return@SimpleAction ActionResult.message("未打开项目")
                s.reindex(); ActionResult.message("正在重新索引项目…")
            },
            PLUGIN,
        )
    }
}
