package dev.etran.scan

import dev.etran.Settings
import dev.etran.compat.Compat
import org.bukkit.Location
import org.bukkit.World
import java.util.BitSet
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot

/**
 * Finds the waiting-lobby structure near the map centre: flood-fills the solid blocks in a box around the
 * centre into 26-connected structures, drops those that contain a bed or are tiny, and keeps the one nearest
 * to the centre. Its bounding box becomes the lobby region and its middle the spawn point.
 */
object LobbyDetector {
    class Result(
        private val world: World,
        val minX: Int, val minY: Int, val minZ: Int,
        val maxX: Int, val maxY: Int, val maxZ: Int,
        val blocks: Int
    ) {
        /** Exact centre of the box in X/Z (block space), at a standable height nearest the box's vertical middle. */
        fun center(): Location {
            val cx = (minX + maxX + 1) / 2.0
            val cz = (minZ + maxZ + 1) / 2.0
            val bx = floor(cx).toInt()
            val bz = floor(cz).toInt()
            val mid = (minY + maxY) / 2
            val span = maxY - minY
            for (d in 0..span) {
                if (standable(world, bx, mid - d, bz)) return Location(world, cx, (mid - d).toDouble(), cz)
                if (d > 0 && standable(world, bx, mid + d, bz)) return Location(world, cx, (mid + d).toDouble(), cz)
            }
            return Location(world, cx, mid.toDouble(), cz)
        }

        fun corner1(): Location = Location(world, minX + 0.5, minY.toDouble(), minZ + 0.5)

        fun corner2(): Location = Location(world, maxX + 0.5, maxY.toDouble(), maxZ + 0.5)
    }

    @JvmStatic
    fun detect(w: World, st: Settings): Result? {
        val r = st.lobbyRadius()
        val cx = floor(st.centerX()).toInt()
        val cz = floor(st.centerZ()).toInt()
        val minX = cx - r
        val maxX = cx + r
        val minZ = cz - r
        val maxZ = cz + r
        val snap = WorldSnap(w, minX, minZ, maxX, maxZ)
        val minY = Compat.minY(w)
        val maxY = Compat.maxY(w)
        val width = 2 * r + 1
        val height = maxY - minY
        val total = width.toLong() * width * height
        if (total > 20_000_000L) return null
        val n = total.toInt()
        val solid = BitSet(n)
        val bed = BitSet(n)
        for (x in 0 until width) {
            for (z in 0 until width) {
                for (y in 0 until height) {
                    val m = snap.type(minX + x, minY + y, minZ + z)
                    if (Mats.isAir(m)) continue
                    val idx = (x * width + z) * height + y
                    solid.set(idx)
                    if (Mats.isBed(m)) bed.set(idx)
                }
            }
        }

        val seen = BitSet(n)
        val queue = IntArray(n)
        var bestDist = Double.MAX_VALUE
        var best: IntArray? = null
        var bestCount = 0
        var i = solid.nextSetBit(0)
        while (i >= 0) {
            if (!seen.get(i)) {
                var head = 0
                var tail = 0
                queue[tail++] = i
                seen.set(i)
                var count = 0
                var hasBed = false
                var x0 = Int.MAX_VALUE
                var y0 = Int.MAX_VALUE
                var z0 = Int.MAX_VALUE
                var x1 = Int.MIN_VALUE
                var y1 = Int.MIN_VALUE
                var z1 = Int.MIN_VALUE
                while (head < tail) {
                    val c = queue[head++]
                    count++
                    val y = c % height
                    val t = c / height
                    val z = t % width
                    val x = t / width
                    x0 = minOf(x0, x); x1 = maxOf(x1, x)
                    y0 = minOf(y0, y); y1 = maxOf(y1, y)
                    z0 = minOf(z0, z); z1 = maxOf(z1, z)
                    if (bed.get(c)) hasBed = true
                    for (dx in -1..1) {
                        val nx = x + dx
                        if (nx < 0 || nx >= width) continue
                        for (dz in -1..1) {
                            val nz = z + dz
                            if (nz < 0 || nz >= width) continue
                            for (dy in -1..1) {
                                if (dx == 0 && dy == 0 && dz == 0) continue
                                val ny = y + dy
                                if (ny < 0 || ny >= height) continue
                                val ni = (nx * width + nz) * height + ny
                                if (solid.get(ni) && !seen.get(ni)) {
                                    seen.set(ni)
                                    queue[tail++] = ni
                                }
                            }
                        }
                    }
                }
                if (!hasBed && count >= st.lobbyMinBlocks()) {
                    val midX = minX + (x0 + x1 + 1) / 2.0
                    val midZ = minZ + (z0 + z1 + 1) / 2.0
                    val dist = hypot(midX - st.centerX(), midZ - st.centerZ())
                    if (dist <= st.lobbyMaxOffset() &&
                        (best == null || dist < bestDist - 1e-6 || (abs(dist - bestDist) <= 1e-6 && count > bestCount))
                    ) {
                        bestDist = dist
                        bestCount = count
                        best = intArrayOf(x0, y0, z0, x1, y1, z1)
                    }
                }
            }
            i = solid.nextSetBit(i + 1)
        }
        val b = best ?: return null
        val pad = st.lobbyPadding()
        return Result(
            w,
            minX + b[0] - pad, maxOf(minY, minY + b[1] - pad), minZ + b[2] - pad,
            minX + b[3] + pad, minOf(maxY - 1, minY + b[4] + pad), minZ + b[5] + pad,
            bestCount
        )
    }

    internal fun standable(w: World, x: Int, y: Int, z: Int): Boolean {
        if (y <= Compat.minY(w) || y >= Compat.maxY(w) - 1) return false
        return !Mats.passable(w.getBlockAt(x, y - 1, z)) &&
            Mats.passable(w.getBlockAt(x, y, z)) &&
            Mats.passable(w.getBlockAt(x, y + 1, z))
    }
}
