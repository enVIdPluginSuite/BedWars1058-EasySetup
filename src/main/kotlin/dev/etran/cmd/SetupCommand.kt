package dev.etran.cmd

import dev.etran.EasySetupPlugin
import dev.etran.Msg
import dev.etran.gui.MainMenu
import dev.etran.model.Facing
import dev.etran.model.GameModeType
import dev.etran.model.SetupMode
import dev.etran.model.SetupSession
import dev.etran.model.Element
import dev.etran.model.TeamColor
import dev.etran.tools.Remover
import dev.etran.place.FacingPolicy
import dev.etran.place.TeamManager
import dev.etran.place.Validator
import dev.etran.tools.ArenaImporter
import dev.etran.tools.Guide
import dev.etran.tools.Hotbar
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.TabExecutor
import org.bukkit.entity.Player
import java.util.Locale

/** /bwsetup [open|exit|tools|page|mode|gamemode|facing|snap|pitch|import|delete|list|status|reset] */
class SetupCommand(private val plugin: EasySetupPlugin) : TabExecutor {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage("Players only.")
            return true
        }
        val p: Player = sender
        val s = plugin.sessions().get(p)
        when (if (args.isEmpty()) "open" else args[0].lowercase(Locale.ROOT)) {
            "open" -> {
                Hotbar.enter(plugin, p, s)
                MainMenu(plugin, p).open()
            }
            "exit" -> {
                if (s.toolsActive) {
                    Hotbar.deactivate(plugin, p, s)
                    Msg.ok(p, "Setup tools removed; your hotbar is back.")
                } else {
                    Msg.info(p, "Setup tools are not active.")
                }
            }
            "tools" -> {
                if (s.toolsActive) {
                    Hotbar.deactivate(plugin, p, s)
                    Msg.ok(p, "Setup tools removed; your hotbar is back.")
                } else {
                    Hotbar.enter(plugin, p, s)
                }
            }
            "page" -> {
                val which = if (args.size > 1) args[1].lowercase(Locale.ROOT) else ""
                val idx = when (which) {
                    "1", "base", "team" -> 0
                    "2", "extras", "extra" -> 1
                    "3", "map" -> 2
                    else -> -1
                }
                if (idx < 0) {
                    Msg.err(p, "Usage: /$label page <1|2|3|base|extras|map>")
                    return true
                }
                Hotbar.enter(plugin, p, s)
                Hotbar.setPage(plugin, p, s, idx)
            }
            "help", "?" -> help(p, label)
            "next" -> Guide.hint(plugin, p, s)
            "reload" -> {
                plugin.reloadConfig()
                Msg.ok(p, "config.yml reloaded.")
            }
            "delete", "remove" -> delete(p, s, label, args)
            "list" -> {
                val all = Remover.all(s)
                if (all.isEmpty()) Msg.info(p, "Nothing has been placed yet.")
                else for (t in all) Msg.plain(p, " - " + t.name() + " at " + t.loc.blockX + ", " + t.loc.blockY + ", " + t.loc.blockZ)
            }
            "facing" -> facing(p, s, label, args)
            "snap" -> {
                val v = if (args.size > 1) args[1].lowercase(Locale.ROOT) else ""
                if (v == "reset") {
                    s.snapOverride = null
                } else if (v == "off" || v == "none") {
                    s.snapOverride = 0.0
                } else {
                    val d = v.toDoubleOrNull()
                    if (d == null || d < 0) {
                        Msg.err(p, "Usage: /$label snap <degrees|off|reset>   (shops and upgrades; default 45)")
                        return true
                    }
                    s.snapOverride = d
                }
                val step = FacingPolicy.step(plugin.settings(), s)
                Msg.ok(p, "Shop/upgrade snap: " + (if (step <= 0) "off" else "$step°"))
            }
            "pitch" -> {
                val v = if (args.size > 1) args[1].lowercase(Locale.ROOT) else ""
                if (v == "reset") {
                    s.pitchOverride = null
                } else {
                    val d = v.toDoubleOrNull()
                    if (d == null) {
                        Msg.err(p, "Usage: /$label pitch <degrees|reset>   (shops and upgrades; default 0)")
                        return true
                    }
                    s.pitchOverride = d
                }
                Msg.ok(p, "Shop/upgrade pitch: " + FacingPolicy.pitch(plugin.settings(), s) + "°")
            }
            "import" -> {
                val n = ArenaImporter.importInto(plugin, p.world, s)
                if (n < 0) Msg.err(p, "No Bedwars1058 arena file found for world " + p.world.name + ".")
                else Msg.ok(p, "Loaded $n existing element(s).")
                plugin.ghosts().refresh(p, s)
            }
            "mode" -> {
                val m = if (args.size > 1) SetupMode.parse(args[1]) else null
                if (m == null) {
                    Msg.err(p, "Usage: /$label mode <manual|semi|auto>")
                    return true
                }
                s.mode = m
                Msg.ok(p, "Setup mode: " + m.label)
            }
            "gamemode" -> {
                val g = if (args.size > 1) GameModeType.parse(args[1]) else null
                if (g == null) {
                    Msg.err(p, "Usage: /$label gamemode <1v1v1v1|1v1|2v2v2v2|2v2|3v3v3v3|3v3|4v4v4v4|4v4|solos|doubles>")
                    return true
                }
                TeamManager.applyGamemode(plugin, p, s, g, null)
            }
            "status" -> {
                Msg.info(p, "Mode: " + s.mode.label + ", gamemode: " + (s.gamemode?.label ?: "none"))
                val missing = Validator.missingAll(s, plugin.settings())
                if (missing.isEmpty()) Msg.ok(p, "Nothing is missing.")
                else {
                    for (line in missing) Msg.plain(p, " - $line")
                    Guide.hint(plugin, p, s)
                }
            }
            "reset" -> {
                s.reset()
                plugin.ghosts().refresh(p, s)
                Hotbar.render(plugin, p, s)
                Msg.ok(p, "Setup state cleared (this does not delete anything inside Bedwars1058).")
            }
            else -> {
                Msg.err(p, "Unknown option '" + args[0] + "'.")
                help(p, label)
            }
        }
        return true
    }

    private fun help(p: Player, label: String) {
        Msg.info(p, "BedWars1058-EasySetup - quick guide")
        val lines = listOf(
            "&e1.&7 Run &f/bw setupArena <world>&7 (or &f/$label&7). The hotbar becomes setup tools.",
            "&e2.&7 Page 2 of the hotbar (green concrete): nether star = gamemode. Then create the teams (red concrete in the menu).",
            "&e3.&7 Page 1: pick a team with the wool, then right-click a block with Spawn, Bed, Shop, Upgrade, Iron, Gold.",
            "&e4.&7 Copy your first base: page 2 arrow = Automation. Page 3 holds the lobby and diamond/emerald tools.",
            "&e5.&7 Green concrete in the menu validates and saves. Punch a marker to delete it.",
            "&6Commands:&7 /$label next (what to do now), status, list, delete <element>, page <1|2|3>, mode, gamemode, facing, snap, exit",
            "&6Also:&7 /bwauto [run|next|confirm|deny|gens], /nextgen [diamond|emerald] [degrees]"
        )
        for (l in lines) Msg.plain(p, org.bukkit.ChatColor.translateAlternateColorCodes('&', l))
    }

    private fun delete(p: Player, s: SetupSession, label: String, args: Array<String>) {
        val what = if (args.size > 1) args[1].lowercase(Locale.ROOT) else ""
        val usage = "Usage: /$label delete <element> [team|all]   |   /$label delete team <team>   |   /$label delete all confirm   |   /$label list"
        when {
            what == "all" -> {
                if (args.size < 3 || !args[2].equals("confirm", ignoreCase = true)) {
                    Msg.err(p, "This deletes everything you placed (${Remover.all(s).size} elements). Run /$label delete all confirm to proceed.")
                    return
                }
                val t = Remover.all(s)
                if (t.isEmpty()) Msg.info(p, "Nothing has been placed yet.") else Remover.delete(plugin, p, s, t)
            }
            what == "team" -> {
                val c = if (args.size > 2) TeamColor.fromName(args[2]) else s.selected
                if (c == null) {
                    Msg.err(p, usage)
                    return
                }
                val t = Remover.all(s).filter { it.team == c }
                if (t.isEmpty()) Msg.info(p, "${c.display} has nothing placed.") else Remover.delete(plugin, p, s, t)
            }
            else -> {
                val e = ELEMENT_NAMES[what]
                if (e == null) {
                    Msg.err(p, usage)
                    Msg.plain(p, " elements: " + ELEMENT_NAMES.keys.joinToString(", "))
                    return
                }
                val arg = if (args.size > 2) args[2] else null
                val every = arg.equals("all", ignoreCase = true)
                val team = if (e.perTeam) (if (arg != null && !every) TeamColor.fromName(arg) else s.selected) else null
                if (e.perTeam && team == null) {
                    Msg.err(p, "Which team? /$label delete $what <team>")
                    return
                }
                val t = Remover.select(s, e, team, p.location, every)
                if (t.isEmpty()) Msg.info(p, "No " + (if (team == null) "" else team.display + " ") + e.label + " has been placed.")
                else Remover.delete(plugin, p, s, t)
            }
        }
    }

    private fun facing(p: Player, s: SetupSession, label: String, args: Array<String>) {
        val v = if (args.size > 1) args[1].lowercase(Locale.ROOT) else ""
        if (v == "reset") {
            s.facingMode = null
        } else {
            val m = Facing.Mode.parse(v)
            val d = v.toDoubleOrNull()
            if (m != null && m != Facing.Mode.FIXED) {
                s.facingMode = m
            } else if (d != null) {
                s.facingMode = Facing.Mode.FIXED
                s.facingFixed = d
            } else {
                Msg.err(p, "Usage: /$label facing <toward|same|degrees|reset>")
                Msg.plain(p, " toward = the shop looks back at you (default); same = the way you face; degrees = a fixed yaw (0 south, 90 west, 180 north, 270 east)")
                return
            }
        }
        val m = FacingPolicy.mode(plugin.settings(), s)
        Msg.ok(
            p,
            "Shop/upgrade facing: " + when (m) {
                Facing.Mode.FIXED -> "fixed " + FacingPolicy.fixedYaw(plugin.settings(), s) + "°"
                Facing.Mode.SAME_AS_PLAYER -> "same way you face"
                Facing.Mode.TOWARD_PLAYER -> "looks back toward you"
            }
        )
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<String>): List<String> {
        fun opts(list: List<String>, typed: String) = list.filter { it.startsWith(typed.lowercase(Locale.ROOT)) }
        if (args.size == 1) {
            return opts(listOf("open", "exit", "tools", "page", "mode", "gamemode", "facing", "snap", "pitch", "import", "delete", "list", "next", "help", "reload", "status", "reset"), args[0])
        }
        if (args.size == 3 && args[0].lowercase(Locale.ROOT) in listOf("delete", "remove")) {
            val team = TeamColor.entries.map { it.display.lowercase(Locale.ROOT) }
            return when (args[1].lowercase(Locale.ROOT)) {
                "team" -> opts(team, args[2])
                "all" -> opts(listOf("confirm"), args[2])
                else -> opts(team + "all", args[2])
            }
        }
        if (args.size != 2) return emptyList()
        return when (args[0].lowercase(Locale.ROOT)) {
            "delete", "remove" -> opts(ELEMENT_NAMES.keys.toList() + listOf("team", "all"), args[1])
            "mode" -> opts(listOf("manual", "semi", "auto"), args[1])
            "page" -> opts(listOf("base", "extras", "map"), args[1])
            "facing" -> opts(listOf("toward", "same", "reset"), args[1])
            "snap" -> opts(listOf("45", "22.5", "15", "90", "off", "reset"), args[1])
            "gamemode" -> opts(GameModeType.entries.map { it.label }, args[1])
            else -> emptyList()
        }
    }

    private companion object {
        val ELEMENT_NAMES: Map<String, Element> = linkedMapOf(
            "spawn" to Element.SPAWN, "bed" to Element.BED, "shop" to Element.SHOP, "upgrade" to Element.UPGRADE,
            "iron" to Element.GEN_IRON, "gold" to Element.GEN_GOLD, "drops" to Element.KILL_DROPS,
            "waitspawn" to Element.WAITING_SPAWN, "specspawn" to Element.SPECTATOR_SPAWN,
            "lobby1" to Element.LOBBY_POS1, "lobby2" to Element.LOBBY_POS2,
            "diamond" to Element.GEN_DIAMOND, "emerald" to Element.GEN_EMERALD
        )
    }
}
