package dev.etran.gui

import dev.etran.EasySetupPlugin
import dev.etran.Msg
import dev.etran.model.SetupMode
import dev.etran.place.AutoRunner
import dev.etran.place.TeamManager
import dev.etran.place.Validator
import dev.etran.tools.ArenaImporter
import dev.etran.tools.Hotbar
import org.bukkit.entity.Player

class MainMenu(plugin: EasySetupPlugin, player: Player) : Menu(plugin, player) {
    override fun title(): String = "Bedwars Auto Setup"

    override fun rows(): Int = 3

    override fun render() {
        fill(Items.GRAY_PANE)
        val req = session.requiredTeams()
        val missingTeams = req.count { !session.hasTeam(it) }

        // Red concrete on the left: the auto-team creator.
        val teamLore = ArrayList<String>()
        teamLore.add("&7Creates every team the gamemode needs.")
        if (session.gamemode == null) {
            teamLore.add("&cPick a gamemode first.")
        } else {
            teamLore.add(if (missingTeams == 0) "&aAll ${req.size} teams exist." else "&eMissing: $missingTeams of ${req.size}")
        }
        set(9, Items.of(Items.RED_CONCRETE, "&c&lAuto-Create Teams", teamLore)) {
            TeamManager.autoCreate(plugin, player, session) { refresh() }
        }
        set(
            11,
            Items.of(
                "NETHER_STAR", "&6Gamemode",
                "&7Current: &f" + (session.gamemode?.label ?: "none"),
                "&7Changing to fewer teams deletes and",
                "&7recreates the existing teams."
            )
        ) { go(GamemodeMenu(plugin, player)) }
        set(
            12,
            Items.of(
                Items.COMPARATOR, "&6Setup mode: &f" + session.mode.label,
                "&7Left-click: next mode  Right-click: previous",
                "&8Manual: ArenaSetup style",
                "&8Semi-Automatic: base by base",
                "&8Fully Automatic: algorithmic"
            )
        ) {
            session.mode = if (it.isRightClick) session.mode.prev() else session.mode.next()
            refresh()
        }
        set(
            14,
            Items.of(
                "COMPASS", "&6Lobby & Boundary",
                "&7Player/spectator spawns and the lobby",
                "&7region that is removed at game start."
            )
        ) { go(LobbyMenu(plugin, player)) }
        set(
            15,
            Items.of(
                "DIAMOND_BLOCK", "&6Map Generators",
                "&7Diamond and emerald generators, plus",
                "&7rotation around the map centre."
            )
        ) { go(MapGenMenu(plugin, player)) }

        // ---- bottom row: hotbar controls
        val sel = session.selected
        set(
            13,
            Items.of(
                sel?.woolSpec() ?: Items.WHITE_WOOL,
                "&eTeam: " + (if (sel == null) "&7none" else sel.chat.toString() + sel.display),
                "&7Choose which team the hotbar tools", "&7place for, or create the teams."
            )
        ) { go(TeamMenu(plugin, player)) }
        set(21, FacingControl.item(plugin, session)) {
            FacingControl.click(plugin, session, it)
            refresh()
        }
        set(
            22,
            Items.of(
                if (session.toolsActive) "LIME_DYE|INK_SACK:10" else "RED_DYE|INK_SACK:1",
                "&eHotbar tools: " + (if (session.toolsActive) "&aON" else "&cOFF"),
                "&7" + (if (session.toolsActive) "Click to give your items back." else "Click to put the tools on your hotbar.")
            )
        ) {
            if (session.toolsActive) Hotbar.deactivate(plugin, player, session) else Hotbar.enter(plugin, player, session)
            refresh()
        }
        set(
            23,
            Items.of(
                "CHEST", "&eLoad existing elements",
                "&7Reads this arena's Bedwars1058 file so",
                "&7existing shops, spawns... get ghosts and",
                "&7can be deleted here."
            )
        ) {
            val n = ArenaImporter.importInto(plugin, player.world, session)
            if (n < 0) Msg.err(player, "No Bedwars1058 arena file found for world " + player.world.name + ".")
            else Msg.ok(player, "Loaded $n element(s).")
            plugin.ghosts().refresh(player, session)
            refresh()
        }

        val status = ArrayList<String>()
        status.add("&7Mode: &f" + session.mode.label)
        status.add("&7Gamemode: &f" + (session.gamemode?.label ?: "none"))
        val bed = session.template.bed
        status.add(
            "&7Template base: &f" + (session.template.team?.display ?: "none") +
                (if (bed == null) " &c(no bed yet)" else " &a(bed found)")
        )
        val missing = Validator.missingAll(session, plugin.settings())
        status.add(if (missing.isEmpty()) "&aNothing missing." else "&eStill missing: &f" + missing.size + " item(s)")
        set(4, Items.of("BOOK", "&eStatus", status))

        // One slot in from the edge: the next screen's save button sits further along, so a double click cannot save.
        set(16, Items.of(Items.LIME_CONCRETE, "&a&lAdvanced Team & Base Setup ➜", "&7Place by position, template, validation.")) {
            if (session.gamemode == null) {
                Msg.err(player, "Pick a gamemode first.")
            } else {
                if (session.selected == null) session.selected = session.requiredTeams()[0]
                go(BaseMenu(plugin, player))
            }
        }
    }

    companion object {
        /** Shared by the main and base menus: the semi/auto action button. */
        internal fun automationButton(m: Menu, slot: Int, plugin: EasySetupPlugin, player: Player, mode: SetupMode) {
            when (mode) {
                SetupMode.MANUAL -> m.set(
                    slot,
                    Items.of(
                        "BARRIER", "&7Automation off",
                        "&7Manual mode only runs the Bedwars", "&7command where you stand.",
                        "&7Change the mode to enable automation."
                    )
                )
                SetupMode.SEMI -> m.set(
                    slot,
                    Items.of(
                        "ARROW", "&bApply to next base",
                        "&7Copies the first base onto the nearest",
                        "&7unassigned bed, then walks you to the",
                        "&7bed after it."
                    )
                ) { AutoRunner.request(plugin, player, true) }
                SetupMode.AUTO -> m.set(
                    slot,
                    Items.of(
                        Items.ENDER_EYE, "&dRun automatic placement",
                        "&7Copies the first base onto every other",
                        "&7bed, detects the lobby and places all",
                        "&7diamond and emerald generators."
                    )
                ) { AutoRunner.request(plugin, player, false) }
            }
        }
    }
}
