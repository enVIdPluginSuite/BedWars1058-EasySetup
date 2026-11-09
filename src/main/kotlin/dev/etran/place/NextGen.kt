package dev.etran.place

import dev.etran.EasySetupPlugin
import dev.etran.Msg
import dev.etran.bw.ElementPlacer
import dev.etran.model.Element
import dev.etran.model.Frame
import dev.etran.scan.GeneratorScanner
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitRunnable
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** `/nextgen`: go to the next unplaced map generator and place it, or rotate around the map centre. */
object NextGen {
    @JvmStatic
    fun run(plugin: EasySetupPlugin, p: Player, type: String?, degrees: Double) {
        val s = plugin.sessions().get(p)
        if (s.busy) {
            Msg.err(p, "Another placement is still running - wait a moment.")
            return
        }
        if (type != null) {
            val found = GeneratorScanner.findUnplaced(p.world, p.location, type, plugin.settings(), s)
            if (found.isNotEmpty()) {
                p.teleport(found[0])
                val secs = plugin.settings().countdownSeconds()
                Msg.ok(p, "Teleported to the closest unplaced $type generator. Placing in $secs seconds... (move to cancel)")
                countdown(plugin, p, type, degrees, p.location.clone(), secs)
                return
            }
            Msg.info(p, "No unplaced $type generators detected via scan (see /bwauto gens). Rotating by ${fmt(degrees)} degrees instead...")
        }
        rotate(plugin, p, degrees)
    }

    private val countdowns = HashMap<java.util.UUID, org.bukkit.scheduler.BukkitTask>()

    private fun countdown(plugin: EasySetupPlugin, p: Player, type: String, degrees: Double, start: Location, seconds: Int) {
        // A second /nextgen replaces the first countdown; two would place the same generator twice.
        countdowns.remove(p.uniqueId)?.cancel()
        val task = object : BukkitRunnable() {
            var left = seconds

            override fun run() {
                if (!p.isOnline) {
                    cancel()
                    countdowns.remove(p.uniqueId)
                    return
                }
                if (p.world != start.world || p.location.distance(start) > 0.5) {
                    Msg.err(p, "Movement detected! Setup cancelled.")
                    cancel()
                    countdowns.remove(p.uniqueId)
                    return
                }
                if (--left > 0) return
                cancel()
                countdowns.remove(p.uniqueId)
                val el = if (type == "diamond") Element.GEN_DIAMOND else Element.GEN_EMERALD
                val s = plugin.sessions().get(p)
                ElementPlacer.placeHere(plugin, p, s, null, el) {
                    Msg.ok(p, (if (type == "diamond") "Diamond" else "Emerald") + " generator placed!")
                    Bukkit.getScheduler().runTaskLater(plugin, { run(plugin, p, type, degrees) }, 5L)
                }
            }
        }.runTaskTimer(plugin, 20L, 20L)
        countdowns[p.uniqueId] = task
    }

    /** Rotate the player's position and yaw around the map centre by [degrees]. */
    @JvmStatic
    fun rotate(plugin: EasySetupPlugin, p: Player, degrees: Double) {
        val cx = plugin.settings().centerX()
        val cz = plugin.settings().centerZ()
        val l = p.location
        val rad = Math.toRadians(degrees)
        val dx = l.x - cx
        val dz = l.z - cz
        // 90 degrees maps (x, z) -> (-z, x), exactly as the Skript did.
        var nx = cx + dx * cos(rad) - dz * sin(rad)
        var nz = cz + dx * sin(rad) + dz * cos(rad)
        if (plugin.settings().snap() && abs(degrees % 90.0) < 1e-9) {
            nx = Math.round(nx * 2.0) / 2.0
            nz = Math.round(nz * 2.0) / 2.0
        }
        val out = Location(l.world, nx, l.y, nz, Frame.norm360(l.yaw + degrees).toFloat(), 0f)
        if (out.block.type.isSolid) out.add(0.0, 1.0, 0.0)
        p.teleport(out)
        Msg.ok(p, "Rotated ${fmt(degrees)} degrees around the map centre (${fmt(cx)}, ${fmt(cz)}).")
    }

    private fun fmt(d: Double): String = if (d == Math.rint(d)) d.toLong().toString() else String.format("%.2f", d)
}
