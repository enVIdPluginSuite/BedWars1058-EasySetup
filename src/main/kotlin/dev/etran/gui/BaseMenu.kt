package dev.etran.gui

import dev.etran.EasySetupPlugin
import dev.etran.bw.ElementPlacer
import dev.etran.model.Element
import dev.etran.model.SetupMode
import dev.etran.model.TeamColor
import dev.etran.place.TeamManager
import dev.etran.place.Validator
import dev.etran.tools.Hotbar
import org.bukkit.entity.Player

/** Team & base setup: pick a team, place its elements, and (semi/auto) copy the base elsewhere. */
class BaseMenu(plugin: EasySetupPlugin, player: Player) : Menu(plugin, player) {
    override fun title(): String = "Team & Base Setup"

    override fun rows(): Int = 6

    private fun icon(e: Element, team: TeamColor?): String = when (e) {
        Element.SPAWN -> "ENDER_PEARL"
        Element.BED -> team?.bedSpec() ?: "RED_BED|BED"
        Element.SHOP -> "EMERALD"
        Element.UPGRADE -> "BEACON"
        Element.GEN_IRON -> "IRON_INGOT"
        Element.GEN_GOLD -> "GOLD_INGOT"
        Element.KILL_DROPS -> "DROPPER"
        else -> "PAPER"
    }

    private fun hint(e: Element): String = when (e) {
        Element.SPAWN -> "Team spawn point."
        Element.BED -> "Origin of the base: every other position is saved relative to it."
        Element.SHOP -> "Item shop NPC."
        Element.UPGRADE -> "Team upgrades NPC."
        Element.GEN_IRON -> "Iron generator (replaces the one placed before)."
        Element.GEN_GOLD -> "Gold generator (replaces the one placed before)."
        Element.KILL_DROPS -> "Where a dead player's items drop."
        else -> ""
    }

    override fun render() {
        fill(Items.GRAY_PANE)
        val req = session.requiredTeams()
        val sel = session.selected

        // Team tabs
        val tabBase = if (req.size > 4) 1 else 2 // 8-team modes use slots 1-8
        for (i in 0 until minOf(req.size, 8)) {
            val c = req[i]
            val isSel = c == sel
            val exists = session.hasTeam(c)
            val missing = Validator.missingForTeam(session, c).size
            set(
                tabBase + i,
                Items.of(
                    c.woolSpec(), (if (isSel) "&a▶ " else "") + c.chat + c.display,
                    if (exists) "&7Elements missing: &f$missing" else "&cTeam not created yet",
                    if (isSel) "&aSelected" else "&eClick to select"
                )
            ) {
                session.selected = c
                Hotbar.render(plugin, player, session)
                refresh()
            }
        }

        // Element buttons
        for (i in ELEMENTS.indices) {
            val el = ELEMENTS[i]
            val placed = sel != null && session.teamPlaced(sel, el)
            set(
                SLOTS[i],
                Items.of(
                    icon(el, sel), (if (placed) "&a✔ " else "&c✘ ") + el.label,
                    "&7" + hint(el), "&eClick to place at your position",
                    "&8(The hotbar tool places where you click.)"
                )
            ) { place(listOf(el)) }
        }
        set(
            29,
            Items.of(
                Items.WHITE_WOOL, "&6Detect team colour from wool",
                "&7Compares wool around every bed, ignoring",
                "&7wool shared by all bases, and selects the",
                "&7colour of the base you are standing at."
            )
        ) { detectWool() }
        set(
            31,
            Items.of(
                "FURNACE", "&6Forge combo",
                "&7Iron generator, gold generator and drop",
                "&7area at your position, in one click."
            )
        ) { place(listOf(Element.GEN_IRON, Element.GEN_GOLD, Element.KILL_DROPS)) }
        MainMenu.automationButton(this, 33, plugin, player, session.mode)
        set(38, FacingControl.item(plugin, session)) {
            FacingControl.click(plugin, session, it)
            refresh()
        }
        set(40, Items.of("BOOK", "&eBase template", templateLore()))

        // Navigation. The "finish" button moves while anything is missing so it cannot be hit by habit.
        set(45, Items.of(Items.RED_CONCRETE, "&c&lBack")) { go(MainMenu(plugin, player)) }
        val missing = Validator.missingAll(session, plugin.settings())
        val complete = missing.isEmpty()
        val lore = ArrayList<String>()
        if (complete) {
            lore.add("&7Everything is placed.")
        } else {
            lore.add("&cNot everything is placed yet:")
            for (i in 0 until minOf(missing.size, 6)) lore.add("&7- " + missing[i])
            if (missing.size > 6) lore.add("&7... and " + (missing.size - 6) + " more")
            lore.add("&7(Button moved to avoid a misclick.)")
        }
        set(
            if (complete) NEXT_SLOT_COMPLETE else NEXT_SLOT_INCOMPLETE,
            Items.of(Items.LIME_CONCRETE, "&a&lFinish & Validate ➜", lore)
        ) { go(ValidationMenu(plugin, player)) }
    }

    private fun place(elems: List<Element>) {
        ElementPlacer.placeMany(plugin, player, session, session.selected, elems) { refresh() }
    }

    private fun templateLore(): List<String> {
        val t = session.template
        val l = ArrayList<String>()
        if (session.mode == SetupMode.MANUAL) {
            l.add("&7Manual mode does not save deltas.")
            return l
        }
        l.add("&7Team: &f" + (t.team?.display ?: "none yet"))
        val b = t.bed
        l.add(
            if (b == null) "&cNo origin bed yet"
            else "&7Origin bed: &f${b.x}, ${b.y}, ${b.z} &7facing &f${b.facingYaw.toInt()}°"
        )
        for (e in ELEMENTS) {
            if (!e.capturable()) continue
            val o = t.offset(e)
            if (o != null) {
                l.add(
                    String.format(
                        "&a✔ &7%s: right %.1f up %.1f fwd %.1f yaw %+d°",
                        e.label, o.right, o.up, o.fwd, Math.round(o.dyaw).toInt()
                    )
                )
            } else if (t.captured(e)) {
                l.add("&e• &7" + e.label + ": captured, waiting for bed")
            } else {
                l.add("&c✘ &7" + e.label)
            }
        }
        return l
    }

    private fun detectWool() {
        TeamManager.detectWoolAt(plugin, player, session) {
            Hotbar.render(plugin, player, session)
            refresh()
        }
    }

    private companion object {
        val ELEMENTS = arrayOf(
            Element.SPAWN, Element.BED, Element.SHOP, Element.UPGRADE,
            Element.GEN_IRON, Element.GEN_GOLD, Element.KILL_DROPS
        )
        val SLOTS = intArrayOf(19, 20, 21, 22, 23, 24, 25)

        /** Where the "finish" button sits normally, and where it is moved to while elements are missing. */
        const val NEXT_SLOT_COMPLETE = 52
        const val NEXT_SLOT_INCOMPLETE = 50
    }
}
