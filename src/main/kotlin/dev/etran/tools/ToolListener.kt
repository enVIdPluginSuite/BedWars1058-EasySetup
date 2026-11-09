package dev.etran.tools

import dev.etran.EasySetupPlugin
import dev.etran.Msg
import dev.etran.bw.ActionQueue
import dev.etran.bw.ElementPlacer
import dev.etran.compat.BedCompat
import dev.etran.gui.FacingControl
import dev.etran.gui.GamemodeMenu
import dev.etran.gui.MainMenu
import dev.etran.gui.TeamMenu
import dev.etran.model.Element
import dev.etran.model.Frame
import dev.etran.model.SetupMode
import dev.etran.model.SetupSession
import dev.etran.place.AutoRunner
import dev.etran.place.FacingPolicy
import dev.etran.place.TeamManager
import dev.etran.scan.BedScanner
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.event.Cancellable
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.event.server.ServerCommandEvent
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.event.world.ChunkUnloadEvent
import org.bukkit.event.world.WorldSaveEvent
import java.util.Locale
import java.util.UUID

/**
 * Everything the hotbar tools do: place where you click, delete by hitting, and keep the tools (and the
 * ghosts) from leaking into the player's real inventory or the saved world.
 */
@Suppress("DEPRECATION")
class ToolListener(private val plugin: EasySetupPlugin) : Listener {
    private val last = HashMap<UUID, Long>()
    private val lastEntity = HashMap<UUID, Long>()
    private val entityHit = HashMap<UUID, Long>()

    /** One action per click: 1.9+ fires interact events for both hands, and entity events overlap. */
    private fun debounce(map: MutableMap<UUID, Long>, p: Player): Boolean {
        val now = System.currentTimeMillis()
        val prev = map[p.uniqueId]
        if (prev != null && now - prev < 120) return false
        map[p.uniqueId] = now
        return true
    }

    private fun active(p: Player): SetupSession? {
        val s = plugin.sessions().peek(p)
        return if (s != null && s.toolsActive) s else null
    }

    // ------------------------------------------------------------------------------------------
    // clicks with a tool
    // ------------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH)
    fun onInteract(e: PlayerInteractEvent) {
        val p = e.player
        val s = active(p) ?: return
        val t = Tool.fromItem(p.itemInHand) ?: return
        e.isCancelled = true
        if (!debounce(last, p)) return
        val a = e.action
        val left = a == Action.LEFT_CLICK_BLOCK || a == Action.LEFT_CLICK_AIR
        if (t.element == null) {
            // Page buttons and utility tools work the same wherever you click.
            Bukkit.getScheduler().runTask(plugin, Runnable { utility(p, s, t, left) })
            return
        }
        if (a == Action.RIGHT_CLICK_BLOCK || a == Action.RIGHT_CLICK_AIR) {
            use(p, s, t, if (a == Action.RIGHT_CLICK_BLOCK) Aim.fromBlock(e.clickedBlock, e.blockFace) else Aim.raycast(p, 60.0))
        } else if (a == Action.LEFT_CLICK_BLOCK || a == Action.LEFT_CLICK_AIR) {
            val hit = if (a == Action.LEFT_CLICK_BLOCK) Aim.fromBlock(e.clickedBlock, e.blockFace) else Aim.raycast(p, 60.0)
            // Swinging at an entity also fires a block/air click first; give the entity hit a tick to claim it.
            Bukkit.getScheduler().runTask(plugin, Runnable {
                val eh = entityHit[p.uniqueId]
                if (eh != null && System.currentTimeMillis() - eh < 300) return@Runnable
                val again = active(p)
                if (again != null) punch(p, again, t, hit)
            })
        }
    }

    /** Page buttons and tools without an element: right-click is the main action, left-click the secondary. */
    private fun utility(p: Player, s: SetupSession, t: Tool, left: Boolean) {
        if (!p.isOnline || !s.toolsActive) return
        when (t) {
            Tool.PREV -> Hotbar.setPage(plugin, p, s, s.hotbarPage - 1)
            Tool.NEXT -> Hotbar.setPage(plugin, p, s, s.hotbarPage + 1)
            Tool.TEAM -> if (left) {
                val req = s.requiredTeams()
                if (req.isEmpty()) {
                    Msg.err(p, "Pick a gamemode first (page 2: nether star).")
                } else {
                    val i = req.indexOf(s.selected)
                    s.selected = req[(i + 1) % req.size]
                    Hotbar.render(plugin, p, s)
                    Msg.info(p, "Placing for " + s.selected!!.chat + s.selected!!.display + (if (s.hasTeam(s.selected!!)) "" else " (team not created yet)"))
                }
            } else TeamMenu(plugin, p).open()
            Tool.MENU -> MainMenu(plugin, p).open()
            Tool.GAMEMODE -> GamemodeMenu(plugin, p).open()
            Tool.DETECT -> TeamManager.detectWoolAt(plugin, p, s) { Hotbar.render(plugin, p, s) }
            Tool.FACING -> {
                if (p.isSneaking) FacingControl.reset(s) else if (left) FacingControl.cycleSnap(plugin, s) else FacingControl.cycleMode(plugin, s)
                Hotbar.render(plugin, p, s)
                Msg.info(p, FacingControl.lines(plugin, s).take(2).joinToString("  ").replace(Regex("&."), ""))
            }
            Tool.AUTO -> if (left) {
                s.mode = s.mode.next()
                Hotbar.render(plugin, p, s)
                Msg.info(p, "Setup mode: " + s.mode.label)
            } else if (s.mode == SetupMode.MANUAL) {
                Msg.err(p, "Automation is off in Manual mode. Left-click this tool to change the mode.")
            } else {
                AutoRunner.request(plugin, p, s.mode == SetupMode.SEMI)
            }
            Tool.EXIT -> {
                Hotbar.deactivate(plugin, p, s)
                Msg.ok(p, "Setup tools removed; your hotbar is back.")
            }
            else -> {}
        }
    }

    private fun use(p: Player, s: SetupSession, t: Tool, hit: Aim.Hit?) {
        val el = t.element ?: return
        if (hit == null) {
            Msg.err(p, "Nothing to aim at - point at a block.")
            return
        }
        if (el.perTeam) {
            if (s.gamemode == null) {
                Msg.err(p, "Pick a gamemode first (nether star -> Gamemode).")
                return
            }
            if (s.selected == null && s.requiredTeams().isNotEmpty()) {
                s.selected = s.requiredTeams()[0]
                Hotbar.render(plugin, p, s)
            }
        }
        val team = if (el.perTeam) s.selected else null
        val w = p.world
        val at: Location
        if (el == Element.BED) {
            val bed = BedCompat.resolve(hit.block) ?: BedScanner.nearest(w, hit.block.location, 2)
            if (bed == null) {
                Msg.err(p, "Click on the bed itself.")
                return
            }
            at = bed.footCenter(w)
        } else {
            at = hit.cellCenter()
        }

        // Clicking an element of the same kind that is already there is the start of deleting it.
        val here = Remover.find(s, at, el)
        if (here.isNotEmpty()) {
            Remover.hit(plugin, p, s, here, false, el)
            return
        }

        val playerYaw = p.location.yaw.toDouble()
        val oriented = FacingPolicy.orient(plugin.settings(), s, el, at, playerYaw)
        ElementPlacer.placeAt(plugin, p, s, team, listOf(el), at, playerYaw) {
            plugin.ghosts().refresh(p, s)
            Msg.ok(
                p,
                "Placed " + (if (team == null) "" else team.display + " ") + el.label +
                    (if (Frame.locksFacing(el)) String.format(" facing %.0f°", oriented.yaw) else "") + "."
            )
            Guide.hint(plugin, p, s)
        }
    }

    private fun punch(p: Player, s: SetupSession, t: Tool, hit: Aim.Hit?) {
        if (hit == null) return
        val w = p.world
        val found = ArrayList<Remover.Target>()
        val centers = ArrayList<Location>()
        val bed = BedCompat.resolve(hit.block)
        if (bed != null) centers.add(bed.footCenter(w))
        centers.add(hit.cellCenter())
        centers.add(Location(w, hit.block.x + 0.5, (hit.block.y + 1).toDouble(), hit.block.z + 0.5))
        for (c in centers) {
            for (tg in Remover.find(s, c, null)) {
                if (found.none { it.loc === tg.loc }) found.add(tg)
            }
        }
        if (found.isEmpty()) return
        Remover.hit(plugin, p, s, found, false, t.element)
    }

    // ------------------------------------------------------------------------------------------
    // hitting entities: ghost villagers and generator holograms
    // ------------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH)
    fun onDamage(e: EntityDamageEvent) {
        val victim = e.entity
        val ghost = plugin.ghosts().isGhost(victim)
        if (ghost) e.isCancelled = true
        if (e !is EntityDamageByEntityEvent) return
        val d = e.damager
        if (d !is Player) return
        if (ghost || victim is ArmorStand) entityHit(d, victim, e)
    }

    /** Right-clicking a marker never trades or deletes: only a punch deletes. Tell the player, once per click. */
    @EventHandler(priority = EventPriority.HIGH)
    fun onInteractEntity(e: PlayerInteractEntityEvent) {
        val ent = e.rightClicked
        if (!plugin.ghosts().isGhost(ent)) return
        e.isCancelled = true
        val p = e.player
        if (active(p) != null && debounce(lastEntity, p)) {
            Msg.info(p, "That is a setup marker. Left-click (punch) it to delete it.")
        }
    }

    private fun entityHit(p: Player, ent: Entity, ev: Cancellable) {
        val s = active(p) ?: return
        val held = Tool.fromItem(p.itemInHand) ?: return
        entityHit[p.uniqueId] = System.currentTimeMillis()
        if (!debounce(lastEntity, p)) {
            ev.isCancelled = true
            return
        }
        val g = plugin.ghosts().entryOf(ent)
        if (g != null) {
            ev.isCancelled = true
            if (g.owner != p.uniqueId) return
            val oneHit = g.element == Element.SHOP || g.element == Element.UPGRADE || g.element.isGenerator()
            Remover.hit(plugin, p, s, Remover.find(s, g.anchor, g.element), oneHit, g.element)
        } else if (ent is ArmorStand) {
            val gens = Remover.find(s, ent.location, null).filter { it.element.isGenerator() }
            if (gens.isEmpty()) return
            ev.isCancelled = true
            Remover.hit(plugin, p, s, gens, true, held.element)
        }
    }

    // ------------------------------------------------------------------------------------------
    // keeping the tools safe
    // ------------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH)
    fun onBreak(e: BlockBreakEvent) {
        val s = active(e.player)
        if (s != null && Tool.fromItem(e.player.itemInHand) != null) e.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onDrop(e: PlayerDropItemEvent) {
        if (Hotbar.isTool(e.itemDrop.itemStack)) e.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onInventoryClick(e: InventoryClickEvent) {
        val p = e.whoClicked as? Player ?: return
        if (active(p) == null) return
        var tool = Hotbar.isTool(e.currentItem) || Hotbar.isTool(e.cursor)
        val hb = e.hotbarButton
        if (!tool && hb >= 0) tool = Hotbar.isTool(p.inventory.getItem(hb))
        if (tool) e.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onInventoryDrag(e: InventoryDragEvent) {
        val p = e.whoClicked as? Player ?: return
        if (active(p) != null && Hotbar.isTool(e.oldCursor)) e.isCancelled = true
    }

    @EventHandler
    fun onDeath(e: PlayerDeathEvent) {
        if (active(e.entity) == null) return
        e.drops.removeAll { Hotbar.isTool(it) }
    }

    @EventHandler
    fun onRespawn(e: PlayerRespawnEvent) {
        val p = e.player
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            val s = active(p)
            if (s != null) Hotbar.render(plugin, p, s)
        }, 2L)
    }

    // ------------------------------------------------------------------------------------------
    // leaving, saving, loading
    // ------------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOWEST)
    fun onQuit(e: PlayerQuitEvent) {
        val p = e.player
        val s = plugin.sessions().peek(p)
        // Stop a half-finished placement first, so the player is not left standing inside a base next login.
        ActionQueue.abort(p)
        if (s != null) s.busy = false
        if (s != null && s.toolsActive) Hotbar.deactivate(plugin, p, s)
        plugin.ghosts().removeOwner(p.uniqueId)
        last.remove(p.uniqueId)
        lastEntity.remove(p.uniqueId)
        entityHit.remove(p.uniqueId)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(e: PlayerJoinEvent) {
        Hotbar.restoreLeftovers(plugin, e.player)
    }

    @EventHandler
    fun onWorldSave(e: WorldSaveEvent) {
        plugin.ghosts().removeForSave()
    }

    @EventHandler
    fun onChunkUnload(e: ChunkUnloadEvent) {
        plugin.ghosts().chunkUnloading(e.chunk)
    }

    @EventHandler
    fun onChunkLoad(e: ChunkLoadEvent) {
        plugin.ghosts().sweepEntities(e.chunk.entities.toList())
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onPlayerCommand(e: PlayerCommandPreprocessEvent) {
        if (isSaveCommand(e.message)) plugin.ghosts().removeForSave()
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onServerCommand(e: ServerCommandEvent) {
        if (isSaveCommand(e.command)) plugin.ghosts().removeForSave()
    }

    private fun isSaveCommand(raw: String): Boolean {
        var m = raw.trim().lowercase(Locale.ROOT)
        if (m.startsWith("/")) m = m.substring(1)
        val a = m.split(Regex("\\s+"))
        if (a.isEmpty()) return false
        if (a[0] == "save-all" || a[0] == "minecraft:save-all") return true
        return a.size > 1 && plugin.settings().commandRoots().contains(a[0]) && a[1] == "save"
    }
}
