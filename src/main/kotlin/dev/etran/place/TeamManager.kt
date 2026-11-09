package dev.etran.place

import dev.etran.EasySetupPlugin
import dev.etran.Msg
import dev.etran.bw.ActionQueue
import dev.etran.compat.Compat
import dev.etran.model.GameModeType
import dev.etran.model.SetupSession
import dev.etran.model.TeamColor
import dev.etran.scan.BedScanner
import dev.etran.scan.WoolDetector
import org.bukkit.ChatColor
import org.bukkit.Location
import org.bukkit.entity.Player

/** Team creation, removal and gamemode resizing. */
object TeamManager {
    @JvmStatic
    fun requireTeam(p: Player, s: SetupSession, team: TeamColor?): Boolean {
        if (team != null && s.hasTeam(team)) return true
        if (!s.warnedMissingTeam) {
            s.warnedMissingTeam = true
            Msg.err(
                p,
                "Team " + (if (team == null) "" else team.display + " ") + "does not exist yet. Open /bwsetup and click the " +
                    ChatColor.DARK_RED + "red concrete" + ChatColor.RED + " on the left to auto-create all required teams."
            )
        } else {
            Compat.playNo(p)
        }
        return false
    }

    /** Create every required team that does not exist yet and set the max players per team. */
    @JvmStatic
    fun autoCreate(plugin: EasySetupPlugin, p: Player, s: SetupSession, after: Runnable?) {
        val gm = s.gamemode
        if (gm == null) {
            Msg.err(p, "Pick a gamemode first (the nether star in /bwsetup).")
            return
        }
        if (s.busy) {
            Msg.err(p, "Another placement is still running - wait a moment.")
            return
        }
        val q = ActionQueue(plugin, p)
        var created = 0
        for (c in s.requiredTeams()) {
            if (s.hasTeam(c)) continue
            q.command(plugin.bw().build("create-team", "team", c.bwName(), "color", c.bwName()))
            q.then { s.team(c).created = true }
            created++
        }
        q.command(plugin.bw().build("set-max-in-team", "size", gm.size.toString()))
        val n = created
        s.busy = true
        q.done {
            s.busy = false
            s.warnedMissingTeam = false
            if (s.selected == null && s.requiredTeams().isNotEmpty()) s.selected = s.requiredTeams()[0]
            Msg.ok(p, if (n == 0) "All required teams already exist." else "Created $n team(s).")
            after?.run()
        }
        q.start()
    }

    @JvmStatic
    fun applyGamemode(plugin: EasySetupPlugin, p: Player, s: SetupSession, nm: GameModeType, after: Runnable?) {
        if (s.busy) {
            Msg.err(p, "Another placement is still running - wait a moment.")
            return
        }
        val existing = s.createdTeams()
        s.gamemode = nm
        val q = ActionQueue(plugin, p)
        val fewer = existing.isNotEmpty() && nm.teams < existing.size
        val more = existing.isNotEmpty() && nm.teams > existing.size
        if (fewer) {
            for (c in existing) {
                q.command(plugin.bw().build("remove-team", "team", c.bwName()))
                q.then { wipe(s, c) }
            }
        }
        if (fewer || more) {
            for (c in s.requiredTeams()) {
                if (!fewer && s.hasTeam(c)) continue
                q.command(plugin.bw().build("create-team", "team", c.bwName(), "color", c.bwName()))
                q.then { s.team(c).created = true }
            }
        }
        q.command(plugin.bw().build("set-max-in-team", "size", nm.size.toString()))
        s.busy = true
        q.done {
            s.busy = false
            s.warnedMissingTeam = false
            if (fewer) {
                // Every team was deleted and recreated: the first-base template and its markers belong to teams that are gone.
                s.template.clear()
                s.assignedBeds.clear()
            }
            if (s.toolsActive) plugin.ghosts().refresh(p, s)
            if (s.selected == null || !s.requiredTeams().contains(s.selected)) {
                s.selected = s.requiredTeams().firstOrNull()
            }
            Msg.ok(p, "Gamemode set to " + nm.label + (if (fewer) " (teams were deleted and recreated)." else "."))
            after?.run()
        }
        q.start()
    }

    /** Pick the team whose colour matches the wool around the base the player is standing at. */
    @JvmStatic
    fun detectWoolAt(plugin: EasySetupPlugin, player: Player, session: SetupSession, after: Runnable?) {
        val w = player.world
        val here = player.location
        val near = BedScanner.nearest(w, here, 60)
        if (near == null) {
            Msg.err(player, "No bed within 60 blocks. Stand at a base first.")
            return
        }
        val centre = Location(w, plugin.settings().centerX(), here.y, plugin.settings().centerZ())
        val beds = BedScanner.scan(w, centre, plugin.settings().bedScanRadius())
        val r = WoolDetector.detect(w, beds, plugin.settings(), session.requiredTeams())
        val c = r.byBed[near.key()]
        if (c == null) {
            Msg.err(player, "Could not isolate a team colour here (not enough wool that differs between bases).")
            return
        }
        if (!session.requiredTeams().contains(c)) {
            Msg.info(player, "This base looks ${c.display}, which the current gamemode does not use.")
            return
        }
        session.selected = c
        Msg.ok(player, "This base is ${c.display} (selected).")
        after?.run()
    }

    /** Forget everything recorded for a team (it was removed in Bedwars1058). */
    @JvmStatic
    fun wipe(s: SetupSession, c: TeamColor) {
        val key = s.teams.remove(c)?.bed?.key() ?: return
        s.assignedBeds.removeAll { it.key() == key }
    }
}
