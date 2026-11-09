package dev.etran.bw

import dev.etran.EasySetupPlugin
import dev.etran.model.Element
import dev.etran.model.SetupSession
import dev.etran.model.TeamColor
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.scheduler.BukkitRunnable
import dev.etran.Msg
import dev.etran.gui.MainMenu
import dev.etran.tools.Hotbar
import dev.etran.tools.Guide
import org.bukkit.scheduler.BukkitTask
import java.util.Locale
import java.util.UUID

/**
 * Watches the Bedwars1058 setup commands a player types by hand and records them in the session, so manual
 * and menu-driven setup stay in sync (the Skript's `on command`).
 */
class CommandSniffer(private val plugin: EasySetupPlugin) : Listener {
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onCommand(ev: PlayerCommandPreprocessEvent) {
        val p = ev.player
        var msg = ev.message.trim()
        if (msg.startsWith("/")) msg = msg.substring(1)
        val a = msg.split(Regex("\\s+"))
        if (a.size < 2 || !plugin.settings().commandRoots().contains(a[0].lowercase(Locale.ROOT))) return
        if (a[1].equals("setuparena", ignoreCase = true) && plugin.settings().autoStart() && p.hasPermission("bwauto.use")) {
            startWhenInArena(p, if (a.size > 2) a[2] else null)
            return
        }
        val s = plugin.sessions().peek(p) ?: return
        when (a[1].lowercase(Locale.ROOT)) {
            "createteam" -> {
                val c = if (a.size > 2) TeamColor.fromName(a[2]) else null
                if (c != null) s.team(c).created = true
            }
            "removeteam" -> {
                val c = if (a.size > 2) TeamColor.fromName(a[2]) else null
                if (c != null) {
                    val key = s.teams.remove(c)?.bed?.key()
                    if (key != null) s.assignedBeds.removeAll { it.key() == key }
                }
            }
            "setspawn" -> teamElement(p, s, a, 2, Element.SPAWN)
            "setbed" -> teamElement(p, s, a, 2, Element.BED)
            "setshop" -> teamElement(p, s, a, 2, Element.SHOP)
            "setupgrade" -> teamElement(p, s, a, 2, Element.UPGRADE)
            "setkilldrops" -> teamElement(p, s, a, 2, Element.KILL_DROPS)
            "addgenerator" -> {
                if (a.size < 3) return
                when (a[2].lowercase(Locale.ROOT)) {
                    "iron" -> teamElement(p, s, a, 3, Element.GEN_IRON)
                    "gold" -> teamElement(p, s, a, 3, Element.GEN_GOLD)
                    "diamond" -> ElementPlacer.record(plugin, p, s, null, Element.GEN_DIAMOND, p.location)
                    "emerald" -> ElementPlacer.record(plugin, p, s, null, Element.GEN_EMERALD, p.location)
                }
            }
            "removegenerator" -> {
                // Typed by hand: forget the generator nearest the player, as Bedwars1058 removes the nearest one.
                var bestTs: dev.etran.model.TeamState? = null
                var best: dev.etran.model.TeamState.GenRecord? = null
                var bd = 9.0
                for (ts in s.teams.values) for (g in ts.gens) {
                    if (g.loc.world != p.world) continue
                    val d = g.loc.distanceSquared(p.location)
                    if (d < bd) { bd = d; best = g; bestTs = ts }
                }
                if (best != null && bestTs != null) {
                    bestTs.gens.remove(best)
                    if (bestTs.gens.none { it.type == best.type }) bestTs.placed.remove(if (best.type == "iron") Element.GEN_IRON else Element.GEN_GOLD)
                } else {
                    for ((el, list) in s.mapElements) {
                        if (!el.isGenerator()) continue
                        val hit = list.firstOrNull { it.world == p.world && it.distanceSquared(p.location) < 9.0 }
                        if (hit != null) { list.remove(hit); break }
                    }
                }
            }
            "setwaitingspawn" -> ElementPlacer.record(plugin, p, s, null, Element.WAITING_SPAWN, p.location)
            "setspectatorspawn" -> ElementPlacer.record(plugin, p, s, null, Element.SPECTATOR_SPAWN, p.location)
            "waitingpos1" -> ElementPlacer.record(plugin, p, s, null, Element.LOBBY_POS1, p.location)
            "waitingpos2" -> ElementPlacer.record(plugin, p, s, null, Element.LOBBY_POS2, p.location)
        }
        if (s.toolsActive) {
            Bukkit.getScheduler().runTask(plugin, Runnable { plugin.ghosts().refresh(p, s) })
        }
    }

    /**
     * `/bw setupArena <world>` teleports the player into the arena world a moment later. Wait for that, then start
     * a fresh session: tools on the hotbar, existing elements imported, main menu open.
     */
    private fun startWhenInArena(p: Player, world: String?) {
        var waited = 0
        // Typing the command twice must not start two waiters (two sessions fighting over the hotbar).
        waiting.remove(p.uniqueId)?.cancel()
        val task = object : BukkitRunnable() {
            override fun run() {
                waited += 5
                if (!p.isOnline) {
                    cancel()
                    waiting.remove(p.uniqueId)
                    return
                }
                val inArena = world == null || p.world.name.equals(world, ignoreCase = true)
                if (!inArena && waited < 120) return
                cancel()
                waiting.remove(p.uniqueId)
                if (!inArena) return // the setup never started (wrong name, no permission...)
                val old = plugin.sessions().peek(p)
                if (old != null) Hotbar.deactivate(plugin, p, old)
                plugin.sessions().remove(p.uniqueId)
                val fresh = plugin.sessions().get(p)
                Hotbar.enter(plugin, p, fresh)
                MainMenu(plugin, p).open()
                Msg.info(p, "Setup started for " + p.world.name + ". Pick a gamemode, then use the hotbar. /bwsetup reopens the menu, /bwsetup help explains everything.")
                Guide.hint(plugin, p, fresh)
            }
        }.runTaskTimer(plugin, 10L, 5L)
        waiting[p.uniqueId] = task
    }

    private val waiting = HashMap<UUID, BukkitTask>()

    private fun teamElement(p: Player, s: SetupSession, a: List<String>, teamArg: Int, e: Element) {
        val c = (if (a.size > teamArg) TeamColor.fromName(a[teamArg]) else s.selected) ?: return
        ElementPlacer.record(plugin, p, s, c, e, p.location)
    }

    @EventHandler
    fun onQuit(ev: PlayerQuitEvent) {
        waiting.remove(ev.player.uniqueId)?.cancel()
        plugin.sessions().remove(ev.player.uniqueId)
    }
}
