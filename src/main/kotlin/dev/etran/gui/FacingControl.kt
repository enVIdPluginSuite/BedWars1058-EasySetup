package dev.etran.gui

import dev.etran.EasySetupPlugin
import dev.etran.model.Facing
import dev.etran.model.SetupSession
import dev.etran.place.FacingPolicy
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.ItemStack
import java.util.Locale

/** The shop/upgrade facing button: left-click cycles the facing mode, right-click the snap step, shift resets. */
internal object FacingControl {
    private val STEPS = doubleArrayOf(45.0, 22.5, 15.0, 90.0, 0.0)

    fun item(plugin: EasySetupPlugin, s: SetupSession): ItemStack {
        val m = FacingPolicy.mode(plugin.settings(), s)
        val step = FacingPolicy.step(plugin.settings(), s)
        val mode = when (m) {
            Facing.Mode.TOWARD_PLAYER -> "looks back toward you"
            Facing.Mode.SAME_AS_PLAYER -> "same way you face"
            Facing.Mode.FIXED -> "fixed " + FacingPolicy.fixedYaw(plugin.settings(), s).toInt() + "°"
        }
        return Items.of(
            "COMPASS", "&eShop & upgrade facing",
            "&7Direction: &f$mode",
            "&7Snap: &f" + (if (step <= 0) "off" else trim(step) + "°") + "&7   Pitch: &f" +
                trim(FacingPolicy.pitch(plugin.settings(), s)) + "°",
            "&eLeft-click: change direction",
            "&eRight-click: change snap",
            "&eShift-click: back to config.yml values",
            "&8Also: /bwsetup facing, snap, pitch"
        )
    }

    /** Lore lines describing the current facing settings (shared by the menu button and the hotbar tool). */
    fun lines(plugin: EasySetupPlugin, s: SetupSession): List<String> {
        val m = FacingPolicy.mode(plugin.settings(), s)
        val step = FacingPolicy.step(plugin.settings(), s)
        val mode = when (m) {
            Facing.Mode.TOWARD_PLAYER -> "looks back toward you"
            Facing.Mode.SAME_AS_PLAYER -> "same way you face"
            Facing.Mode.FIXED -> "fixed " + FacingPolicy.fixedYaw(plugin.settings(), s).toInt() + "°"
        }
        return listOf(
            "&7Direction: &f$mode",
            "&7Snap: &f" + (if (step <= 0) "off" else trim(step) + "°") + "&7   Pitch: &f" + trim(FacingPolicy.pitch(plugin.settings(), s)) + "°",
            "&7Right-click: change direction",
            "&7Left-click: change snap",
            "&7Shift-click: back to config values"
        )
    }

    fun cycleMode(plugin: EasySetupPlugin, s: SetupSession) {
        // Cycle from what is actually in effect (config included), so the first click always visibly changes it.
        val cur = FacingPolicy.mode(plugin.settings(), s)
        val next = Facing.Mode.entries[(cur.ordinal + 1) % Facing.Mode.entries.size]
        if (next == Facing.Mode.FIXED && s.facingMode != Facing.Mode.FIXED) s.facingFixed = plugin.settings().shopFixedYaw()
        s.facingMode = next
    }

    fun cycleSnap(plugin: EasySetupPlugin, s: SetupSession) {
        val cur = FacingPolicy.step(plugin.settings(), s)
        var idx = 0
        for (i in STEPS.indices) if (Math.abs(STEPS[i] - cur) < 1e-9) idx = i
        s.snapOverride = STEPS[(idx + 1) % STEPS.size]
    }

    fun reset(s: SetupSession) {
        s.facingMode = null
        s.snapOverride = null
        s.pitchOverride = null
    }

    fun click(plugin: EasySetupPlugin, s: SetupSession, e: InventoryClickEvent) {
        if (e.isShiftClick) reset(s) else if (e.isRightClick) cycleSnap(plugin, s) else cycleMode(plugin, s)
    }

    private fun trim(d: Double): String =
        if (d == Math.rint(d)) d.toLong().toString() else String.format(Locale.ROOT, "%.1f", d)
}
