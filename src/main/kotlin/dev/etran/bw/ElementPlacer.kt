package dev.etran.bw

import dev.etran.EasySetupPlugin
import dev.etran.Msg
import dev.etran.model.BedInfo
import dev.etran.model.Element
import dev.etran.model.SetupMode
import dev.etran.model.SetupSession
import dev.etran.model.TeamColor
import dev.etran.model.TeamState
import dev.etran.place.FacingPolicy
import dev.etran.place.TeamManager
import dev.etran.scan.BedScanner
import org.bukkit.Location
import org.bukkit.entity.Player

/**
 * Runs the Bedwars1058 command for one element at a chosen spot and records the result. Bedwars1058 reads
 * the player's own location, so the player is moved to the target, the command runs, and they are moved back.
 */
object ElementPlacer {
    @JvmStatic
    fun commandFor(bw: BwCommands, team: TeamColor?, e: Element): String? {
        val tn = team?.bwName() ?: ""
        return when (e) {
            Element.SPAWN -> bw.build("set-spawn", "team", tn)
            Element.BED -> bw.build("set-bed", "team", tn)
            Element.SHOP -> bw.build("set-shop", "team", tn)
            Element.UPGRADE -> bw.build("set-upgrade", "team", tn)
            Element.KILL_DROPS -> bw.build("set-kill-drops", "team", tn)
            Element.GEN_IRON -> bw.build("add-generator", "type", "iron", "team", tn)
            Element.GEN_GOLD -> bw.build("add-generator", "type", "gold", "team", tn)
            Element.WAITING_SPAWN -> bw.build("set-waiting-spawn")
            Element.SPECTATOR_SPAWN -> bw.build("set-spectator-spawn")
            Element.LOBBY_POS1 -> bw.build("waiting-pos1")
            Element.LOBBY_POS2 -> bw.build("waiting-pos2")
            Element.GEN_DIAMOND -> bw.build("add-map-generator", "type", "diamond")
            Element.GEN_EMERALD -> bw.build("add-map-generator", "type", "emerald")
        }
    }

    /** Place one element at the player's current position. */
    @JvmStatic
    fun placeHere(plugin: EasySetupPlugin, p: Player, s: SetupSession, team: TeamColor?, e: Element, after: Runnable?) =
        placeMany(plugin, p, s, team, listOf(e), after)

    /** Place several elements at the player's current position, in order (e.g. the forge combo). */
    @JvmStatic
    fun placeMany(plugin: EasySetupPlugin, p: Player, s: SetupSession, team: TeamColor?, elems: List<Element>, after: Runnable?) {
        val here = p.location
        placeAt(plugin, p, s, team, elems, here, here.yaw.toDouble(), after)
    }

    /**
     * Place elements at [at] (not necessarily where the player stands). Yaw/pitch come from the facing policy:
     * shops and upgrades face back toward the player, snapped to the configured step, with pitch 0.
     */
    @JvmStatic
    fun placeAt(
        plugin: EasySetupPlugin, p: Player, s: SetupSession, team: TeamColor?, elems: List<Element>,
        at: Location, playerYaw: Double, after: Runnable?
    ) {
        if (s.busy) {
            Msg.err(p, "Another placement is still running - wait a moment.")
            return
        }
        val needsTeam = elems.any { it.perTeam }
        if (needsTeam && !TeamManager.requireTeam(p, s, team)) return

        val original = p.location.clone()
        val q = ActionQueue(plugin, p)
        for (e in elems) {
            val loc = FacingPolicy.orient(plugin.settings(), s, e, at, playerYaw)
            val cmd = commandFor(plugin.bw(), team, e)
            if (cmd == null) {
                Msg.err(p, "No command is configured for " + e.label + " (bw-commands in config.yml).")
                continue
            }
            // Re-placing a generator: remove the one we placed here before, so output is not doubled.
            val type = e.genType()
            if (type != null) {
                val rm = plugin.bw().build("remove-generator")
                if (e.perTeam && team != null) {
                    val ts = s.team(team)
                    val ex = findGen(ts, type, loc)
                    if (ex != null && rm != null) {
                        q.teleport(ex.loc)
                        q.command(rm)
                        q.then { ts.gens.remove(ex) }
                    }
                } else {
                    val ex = findMapGen(s, e, loc)
                    if (ex != null && rm != null) {
                        q.teleport(ex)
                        q.command(rm)
                        q.then { s.mapElements[e]?.remove(ex) }
                    }
                }
            }
            q.teleport(loc)
            q.command(cmd)
            q.then { record(plugin, p, s, team, e, loc) }
        }
        s.busy = true
        q.teleport(original)
        q.done {
            s.busy = false
            if (s.toolsActive) plugin.ghosts().refresh(p, s)
            after?.run()
        }
        q.start()
    }

    /** Mark an element as placed (and capture it into the template). The Bedwars command already ran. */
    @JvmStatic
    fun record(plugin: EasySetupPlugin, p: Player, s: SetupSession, team: TeamColor?, e: Element, loc: Location) {
        if (e.perTeam) {
            if (team == null) return
            val ts = s.team(team)
            ts.placed.add(e)
            if (e.isGenerator()) {
                // The same generator typed or placed twice at one spot is still one record.
                val type = e.genType()!!
                ts.gens.removeAll { it.type == type && sameWorld(it.loc, loc) && it.loc.distanceSquared(loc) < 0.25 }
                ts.gens.add(TeamState.GenRecord(type, loc.clone()))
            }
            else ts.pos[e] = loc.clone()
            if (s.mode != SetupMode.MANUAL) capture(plugin, s, team, e, loc)
        } else if (e.isGenerator()) {
            s.mapElements[e]?.removeAll { sameWorld(it, loc) && it.distanceSquared(loc) < 0.25 }
            s.addMapGen(e, loc)
        } else {
            s.setMap(e, loc)
        }
    }

    private fun capture(plugin: EasySetupPlugin, s: SetupSession, team: TeamColor, e: Element, loc: Location) {
        val t = s.template
        if (t.team == null) t.team = team
        if (t.team != team) return // only the template team defines the deltas
        if (e == Element.BED) {
            val b = BedScanner.nearest(loc.world, loc, plugin.settings().templateBedRadius())
            if (b != null) {
                t.bed = b
                s.team(team).bed = b
                s.addAssigned(b)
            }
            return
        }
        t.capture(e, loc, plugin.settings().snap(), FacingPolicy.step(plugin.settings(), s))
        if (t.bed == null) {
            val b: BedInfo? = BedScanner.nearest(loc.world, loc, plugin.settings().templateBedRadius())
            if (b != null) {
                t.bed = b
                s.addAssigned(b)
            }
        }
    }

    @JvmStatic
    fun findGen(ts: TeamState, type: String, near: Location): TeamState.GenRecord? =
        ts.gens.firstOrNull { it.type == type && sameWorld(it.loc, near) && it.loc.distance(near) < 1.5 }

    private fun findMapGen(s: SetupSession, e: Element, near: Location): Location? =
        s.mapElements[e]?.firstOrNull { sameWorld(it, near) && it.distance(near) < 1.5 }

    private fun sameWorld(a: Location, b: Location): Boolean = a.world != null && a.world == b.world
}
