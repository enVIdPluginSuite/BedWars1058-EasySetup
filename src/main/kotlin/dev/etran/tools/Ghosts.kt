package dev.etran.tools

import dev.etran.EasySetupPlugin
import dev.etran.model.Element
import dev.etran.model.SetupSession
import dev.etran.gui.Items
import dev.etran.model.TeamColor
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.Villager
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitTask
import java.util.UUID

/**
 * Setup markers ("ghosts"): real entities that show every placed element - villagers for shops and upgrades (looking the way they face), labelled armor stands for the rest while a
 * player is in setup mode. They are removed when the player leaves or exits, when a world is about to save, when
 * their chunk unloads and on disable, and any left behind are swept up on chunk load, so none reaches a real round.
 */
class Ghosts(private val plugin: EasySetupPlugin) {
    internal class Entry(val owner: UUID, val element: Element, val team: TeamColor?, val anchor: Location) {
        var entity: Entity? = null

        /** Spawns that another plugin cancelled (or that failed) in a row; after a few we stop retrying. */
        var failures = 0
    }

    private val byOwner = HashMap<UUID, MutableList<Entry>>()
    private val byEntity = HashMap<UUID, Entry>()
    private var keeper: BukkitTask? = null
    private var rebuildQueued = false

    fun start() {
        keeper = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { keep() }, 20L, 10L)
        // Remove ghosts a crash may have left in loaded chunks.
        for (w in Bukkit.getWorlds()) sweep(w)
    }

    fun stop() {
        keeper?.cancel()
        removeAll()
    }

    // ------------------------------------------------------------------------------------------

    /**
     * Rebuild the owner's markers from everything recorded in the session: villagers for shops and upgrades, a
     * visible armor stand (with the element's icon on its head and a label) for everything else. They are the
     * hitboxes to punch when deleting.
     */
    fun refresh(p: Player, s: SetupSession) {
        if (s.toolsActive) Hotbar.render(plugin, p, s) // keep the placed / not placed marks on the tools current
        removeOwner(p.uniqueId)
        if (!plugin.settings().ghostsEnabled() || !s.toolsActive) return
        for ((color, ts) in s.teams) {
            for ((e, l) in ts.pos) if (l.world != null) spawn(p.uniqueId, e, color, l)
            for (g in ts.gens) {
                val e = if (g.type == "iron") Element.GEN_IRON else Element.GEN_GOLD
                if (g.loc.world != null) spawn(p.uniqueId, e, color, g.loc)
            }
        }
        for ((e, list) in s.mapElements) for (l in list) if (l.world != null) spawn(p.uniqueId, e, null, l)
    }

    private fun spawn(owner: UUID, e: Element, team: TeamColor?, l: Location) {
        val en = Entry(owner, e, team, l.clone())
        byOwner.getOrPut(owner) { ArrayList() }.add(en)
        materialise(en)
    }

    private fun label(en: Entry): String =
        (en.team?.chat ?: ChatColor.YELLOW).toString() + ChatColor.BOLD + (if (en.team == null) "" else en.team.display + " ") +
            en.element.label + ChatColor.GRAY + " (punch to delete)"

    /** The block shown on a marker's head. */
    private fun icon(en: Entry): String = when (en.element) {
        Element.SPAWN -> en.team?.woolSpec() ?: "WHITE_WOOL|WOOL:0"
        Element.BED -> en.team?.bedSpec() ?: "RED_BED|BED"
        Element.KILL_DROPS -> "DROPPER"
        Element.GEN_IRON -> "IRON_BLOCK"
        Element.GEN_GOLD -> "GOLD_BLOCK"
        Element.GEN_DIAMOND -> "DIAMOND_BLOCK"
        Element.GEN_EMERALD -> "EMERALD_BLOCK"
        Element.WAITING_SPAWN -> "BEACON"
        Element.SPECTATOR_SPAWN -> "GLASS"
        Element.LOBBY_POS1, Element.LOBBY_POS2 -> "OAK_PLANKS|WOOD"
        else -> "PAPER"
    }

    private fun materialise(en: Entry) {
        val l = en.anchor
        val w = l.world
        if (w == null || !w.isChunkLoaded(l.blockX shr 4, l.blockZ shr 4)) return
        try {
            val villager = en.element == Element.SHOP || en.element == Element.UPGRADE
            val ent: LivingEntity = if (villager) {
                val v = w.spawnEntity(l, EntityType.VILLAGER) as Villager
                try {
                    v.profession = if (en.element == Element.SHOP) Villager.Profession.FARMER else Villager.Profession.LIBRARIAN
                } catch (ignored: Throwable) {
                    // profession is cosmetic
                }
                v
            } else {
                val st = w.spawnEntity(l, EntityType.valueOf("ARMOR_STAND")) as LivingEntity
                call(st, "setVisible", arrayOf(java.lang.Boolean.TYPE), true)
                call(st, "setGravity", arrayOf(java.lang.Boolean.TYPE), false)
                call(st, "setBasePlate", arrayOf(java.lang.Boolean.TYPE), false)
                call(st, "setArms", arrayOf(java.lang.Boolean.TYPE), false)
                st.equipment?.helmet = Items.create(icon(en))
                st
            }
            if (!ent.isValid) {
                // Another plugin (region protection, mob limits...) cancelled the spawn.
                failed(en)
                return
            }
            ent.customName = TAG + label(en)
            ent.isCustomNameVisible = true
            ent.removeWhenFarAway = false
            val ai = call(ent, "setAI", arrayOf(java.lang.Boolean.TYPE), false)
            call(ent, "setSilent", arrayOf(java.lang.Boolean.TYPE), true)
            call(ent, "setCollidable", arrayOf(java.lang.Boolean.TYPE), false)
            call(ent, "setPersistent", arrayOf(java.lang.Boolean.TYPE), false)
            if (villager && !ai) immobilise(ent as Villager) // 1.8: no AI switch
            ent.teleport(l)
            call(ent, "setRotation", arrayOf(java.lang.Float.TYPE, java.lang.Float.TYPE), l.yaw, l.pitch)
            en.entity = ent
            en.failures = 0
            byEntity[ent.uniqueId] = en
        } catch (t: Throwable) {
            if (en.failures == 0) plugin.logger.warning("Could not spawn a setup marker: $t")
            failed(en)
        }
    }

    private fun failed(en: Entry) {
        en.failures++
        if (en.failures == MAX_FAILURES) {
            plugin.logger.warning(
                "A setup marker for ${en.element.label} at ${en.anchor.blockX}, ${en.anchor.blockY}, ${en.anchor.blockZ} " +
                    "could not be spawned (another plugin may block entity spawns there); giving up on it."
            )
        }
    }

    private fun immobilise(v: Villager) {
        try {
            PotionEffectType.getByName("SLOW")?.let { v.addPotionEffect(PotionEffect(it, Int.MAX_VALUE, 255, true)) }
            PotionEffectType.getByName("JUMP")?.let { v.addPotionEffect(PotionEffect(it, Int.MAX_VALUE, 128, true)) }
        } catch (ignored: Throwable) {
            // the keeper task still pulls it back
        }
    }

    private fun call(target: Any, name: String, types: Array<Class<*>>, vararg args: Any): Boolean = try {
        target.javaClass.getMethod(name, *types).invoke(target, *args)
        true
    } catch (t: Throwable) {
        false
    }

    // ------------------------------------------------------------------------------------------

    fun removeOwner(owner: UUID) {
        val list = byOwner.remove(owner) ?: return
        for (en in list) dispose(en)
    }

    fun removeAll() {
        for (list in ArrayList(byOwner.values)) for (en in list) dispose(en)
        byOwner.clear()
        byEntity.clear()
    }

    private fun dispose(en: Entry) {
        val e = en.entity ?: return
        byEntity.remove(e.uniqueId)
        try {
            e.remove()
        } catch (ignored: Throwable) {
            // already gone
        }
        en.entity = null
    }

    /** Remove every ghost now (a world is about to be saved) and bring them back a moment later. */
    fun removeForSave() {
        val any = byOwner.isNotEmpty()
        for (list in byOwner.values) for (en in list) dispose(en)
        // Entries stay; they are respawned from their anchors below.
        if (rebuildQueued || !any) return
        rebuildQueued = true
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            rebuildQueued = false
            for (list in byOwner.values) for (en in list) if (en.entity == null && en.failures < MAX_FAILURES) materialise(en)
        }, 10L)
    }

    fun isGhost(e: Entity?): Boolean = e != null && (byEntity.containsKey(e.uniqueId) || tagged(e))

    /** The recorded element behind a ghost entity, or null. */
    internal fun entryOf(e: Entity?): Entry? = if (e == null) null else byEntity[e.uniqueId]

    private fun tagged(e: Entity): Boolean = isTagged(e)

    // ---- leftovers ---------------------------------------------------------------------------

    fun sweep(w: World) {
        for (c in w.loadedChunks) sweepEntities(c.entities.toList())
    }

    fun sweepEntities(entities: Collection<Entity>) {
        for (e in ArrayList(entities)) {
            if (tagged(e) && !byEntity.containsKey(e.uniqueId)) e.remove()
        }
    }

    /** Remove ghosts in a chunk that is about to unload (their entries respawn them if it loads again). */
    fun chunkUnloading(c: Chunk) {
        for (list in byOwner.values) {
            for (en in list) {
                val e = en.entity
                if (e == null) continue
                val l = e.location
                // Compare coordinates: chunk objects are not guaranteed to be equal-comparable across versions.
                if (l.world == c.world && (l.blockX shr 4) == c.x && (l.blockZ shr 4) == c.z) dispose(en)
            }
        }
        sweepEntities(c.entities.toList())
    }

    private fun keep() {
        // While a world save is in progress the markers are meant to be gone; the rebuild task brings them back.
        if (rebuildQueued) return
        for (list in byOwner.values) {
            for (en in list) {
                val e = en.entity
                if (e == null || !e.isValid) {
                    if (e != null) byEntity.remove(e.uniqueId)
                    en.entity = null
                    if (en.failures < MAX_FAILURES) materialise(en)
                } else if (e.world != en.anchor.world || e.location.distanceSquared(en.anchor) > 0.09) {
                    e.teleport(en.anchor)
                }
            }
        }
    }

    companion object {
        /** Invisible prefix on every ghost's name; how leftovers are recognised. */
        internal const val TAG = "§r§b§w§a§g"

        private const val MAX_FAILURES = 3

        /** True for any marker entity this plugin spawned (recognised by its hidden name prefix). */
        @JvmStatic
        fun isTagged(e: Entity): Boolean {
            if (e !is LivingEntity) return false
            val n = e.customName
            return n != null && n.startsWith(TAG)
        }
    }
}
