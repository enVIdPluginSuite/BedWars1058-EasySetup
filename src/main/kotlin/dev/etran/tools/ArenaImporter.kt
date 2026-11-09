package dev.etran.tools

import dev.etran.EasySetupPlugin
import dev.etran.model.ArenaFormat
import dev.etran.model.SetupSession
import dev.etran.model.TeamColor
import dev.etran.model.TeamState
import dev.etran.model.Element
import dev.etran.scan.BedScanner
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File

/** Loads the elements that already exist in the arena's Bedwars1058 file into the setup session. */
object ArenaImporter {
    @JvmStatic
    fun arenaFile(plugin: EasySetupPlugin, w: World): File? {
        val bw = Bukkit.getPluginManager().getPlugin("BedWars1058") ?: return null
        return File(File(bw.dataFolder, plugin.settings().arenaFolder()), w.name + ".yml")
    }

    /** @return how many elements were imported, or -1 when there is no arena file for this world */
    @JvmStatic
    fun importInto(plugin: EasySetupPlugin, w: World, s: SetupSession): Int {
        val f = arenaFile(plugin, w)
        if (f == null || !f.isFile) return -1
        val cfg = YamlConfiguration.loadConfiguration(f)
        var n = 0
        for (path in cfg.getKeys(true)) {
            val slot = ArenaFormat.classify(path) ?: continue
            val v = cfg.get(path)
            val values = ArrayList<String>()
            if (v is String) values.add(v)
            else if (v is List<*>) for (o in v) if (o is String) values.add(o)
            for (text in values) {
                val a = ArenaFormat.parseLocation(text) ?: continue
                val loc = Location(w, a[0], a[1], a[2], a[3].toFloat(), a[4].toFloat())
                if (apply(s, slot, loc)) n++
            }
        }
        s.imported = true
        return n
    }

    private fun apply(s: SetupSession, slot: ArenaFormat.Slot, loc: Location): Boolean {
        val e = slot.element
        if (slot.team != null) {
            val c = TeamColor.fromName(slot.team) ?: return false
            val ts = s.team(c)
            ts.created = true
            if (e.isGenerator()) {
                if (ts.gens.any { it.type == e.genType() && it.loc.world == loc.world && it.loc.distance(loc) < 1.0 }) return false
                ts.gens.add(TeamState.GenRecord(e.genType()!!, loc))
                ts.placed.add(e)
                return true
            }
            if (ts.pos.containsKey(e)) return false // the session already has it
            ts.pos[e] = loc
            ts.placed.add(e)
            if (e == Element.BED) {
                // An imported bed must count as assigned, or automatic placement would give its island to another team.
                try {
                    val w = loc.world
                    val b = if (w == null) null else BedScanner.nearest(w, loc, 3)
                    if (b != null) {
                        ts.bed = b
                        s.addAssigned(b)
                    }
                } catch (ignored: Throwable) {
                    // the element is still recorded
                }
            }
            return true
        }
        if (e.isGenerator()) {
            val l = s.mapElements[e]
            if (l != null && l.any { it.world == loc.world && it.distance(loc) < 1.0 }) return false
            s.addMapGen(e, loc)
            return true
        }
        if (s.mapPlaced(e)) return false
        s.setMap(e, loc)
        return true
    }
}
