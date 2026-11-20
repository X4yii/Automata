package com.x4yi.automata.ai;

import java.util.WeakHashMap;

import javax.annotation.Nullable;

import net.minecraft.block.Block;
import net.minecraft.block.BlockContainer;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumHand;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.items.IItemHandlerModifiable;
import net.minecraftforge.items.ItemHandlerHelper;

import com.x4yi.automata.compat.ModCompatBridge;
import com.x4yi.automata.config.AutomataConfig;
import com.x4yi.automata.entity.EntityAutomaton;
import com.x4yi.automata.world.OwnerCapabilityRegistry;

/**
 * Centralized offhand selection policy for automata:
 * 1) Two-handed weapon -> empty offhand
 * 2) Active combat / recent threat -> shield
 * 3) Outside combat (after hysteresis) -> keep totem if held, else torch if owner has dynamic lights
 */
public final class OffhandPolicy {

    public enum DesiredOffhand {
        EMPTY_TWO_HANDED,
        SHIELD,
        TORCH,
        KEEP_CURRENT
    }

    public static final long COMBAT_TO_TORCH_HYSTERESIS_TICKS = 60L;
    private static final WeakHashMap<EntityAutomaton, Long> LAST_THREAT_TICK = new WeakHashMap<>();

    private OffhandPolicy() {}

    public static DesiredOffhand evaluateDesired(
            boolean twoHandedMainhand,
            boolean inCombatOrHysteresis,
            boolean hasShield,
            boolean hasTotemInOffhand,
            boolean torchEnabledForOwner,
            boolean hasTorch) {
        if (twoHandedMainhand) {
            return DesiredOffhand.EMPTY_TWO_HANDED;
        }
        if (inCombatOrHysteresis) {
            return hasShield ? DesiredOffhand.SHIELD : DesiredOffhand.KEEP_CURRENT;
        }
        if (hasTotemInOffhand) {
            return DesiredOffhand.KEEP_CURRENT;
        }
        if (torchEnabledForOwner && hasTorch) {
            return DesiredOffhand.TORCH;
        }
        return DesiredOffhand.KEEP_CURRENT;
    }

    public static void noteCombatThreat(EntityAutomaton bot, long worldTime) {
        LAST_THREAT_TICK.put(bot, worldTime);
    }

    public static boolean isInCombatOrHysteresis(EntityAutomaton bot, boolean activeThreat, long worldTime) {
        if (activeThreat) {
            LAST_THREAT_TICK.put(bot, worldTime);
            return true;
        }
        Long last = LAST_THREAT_TICK.get(bot);
        return last != null && (worldTime - last) < COMBAT_TO_TORCH_HYSTERESIS_TICKS;
    }

    public static void clearBotState(EntityAutomaton bot) {
        LAST_THREAT_TICK.remove(bot);
    }

    public static void applyOffhand(EntityAutomaton bot, boolean activeThreat, long worldTime) {
        boolean twoHanded = ModCompatBridge.isTwoHandedWeapon(bot.getHeldItemMainhand());
        boolean inCombat = isInCombatOrHysteresis(bot, activeThreat, worldTime);
        ItemStack currentOff = bot.getHeldItemOffhand();
        boolean hasTotemOff = !currentOff.isEmpty() && currentOff.getItem() == Items.TOTEM_OF_UNDYING;
        boolean torchForOwner = AutomataConfig.preferTorchOffhandForDynamicLightOwners
                && OwnerCapabilityRegistry.hasDynamicLights(bot.getOwnerUuid());

        int shieldSlot = EquipmentSelector.isShieldItem(currentOff) ? -2 : findShieldSlot(bot);
        int torchSlot = isTorchItem(currentOff) ? -2 : (torchForOwner ? findTorchSlot(bot) : -1);

        DesiredOffhand desired = evaluateDesired(
                twoHanded,
                inCombat,
                shieldSlot != -1,
                hasTotemOff,
                torchForOwner,
                torchSlot != -1);

        switch (desired) {
            case EMPTY_TWO_HANDED:
                unequipOffhand(bot);
                break;
            case SHIELD:
                if (shieldSlot >= 0) {
                    swapOffhandWithSlot(bot, shieldSlot);
                }
                break;
            case TORCH:
                if (torchSlot >= 0) {
                    swapOffhandWithSlot(bot, torchSlot);
                }
                break;
            case KEEP_CURRENT:
            default:
                break;
        }
    }

    public static void unequipOffhand(EntityAutomaton bot) {
        ItemStack off = bot.getHeldItemOffhand();
        if (off.isEmpty()) {
            return;
        }
        if (bot.isHandActive() && bot.getActiveHand() == EnumHand.OFF_HAND) {
            bot.resetActiveHand();
        }
        ItemStack remainder = ItemHandlerHelper.insertItemStacked(
                bot.getInventory().getCombinedInventory(), off.copy(), false);
        bot.setHeldItem(EnumHand.OFF_HAND, ItemStack.EMPTY);
        if (!remainder.isEmpty()) {
            bot.entityDropItem(remainder, 0.0F);
        }
    }

    public static boolean isTorchItem(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        Item item = stack.getItem();
        if (item == Item.getItemFromBlock(Blocks.TORCH)) {
            return true;
        }
        ResourceLocation id = item.getRegistryName();
        if (id != null && AutomataConfig.offhandTorchItems != null) {
            String idStr = id.toString();
            for (String extra : AutomataConfig.offhandTorchItems) {
                if (extra != null && extra.equalsIgnoreCase(idStr)) {
                    return true;
                }
            }
        }
        if (item instanceof ItemBlock itemBlock) {
            Block block = itemBlock.getBlock();
            if (block instanceof BlockContainer || AutomataConfig.isBlockBlacklisted(block)) {
                return false;
            }
            return block.getDefaultState().getLightValue() > 0 && !block.getDefaultState().isFullCube();
        }
        return false;
    }

    private static int findShieldSlot(EntityAutomaton bot) {
        IItemHandlerModifiable inv = bot.getInventory().getCombinedInventory();
        for (int i = 0; i < inv.getSlots(); i++) {
            if (EquipmentSelector.isShieldItem(inv.getStackInSlot(i))) {
                return i;
            }
        }
        return -1;
    }

    private static int findTorchSlot(EntityAutomaton bot) {
        IItemHandlerModifiable inv = bot.getInventory().getCombinedInventory();
        for (int i = 0; i < inv.getSlots(); i++) {
            if (isTorchItem(inv.getStackInSlot(i))) {
                return i;
            }
        }
        return -1;
    }

    private static void swapOffhandWithSlot(EntityAutomaton bot, int slot) {
        IItemHandlerModifiable inv = bot.getInventory().getCombinedInventory();
        ItemStack prevOff = bot.getHeldItemOffhand().copy();
        ItemStack extracted = inv.extractItem(slot, 1, false);
        if (extracted.isEmpty()) {
            return;
        }
        if (bot.isHandActive() && bot.getActiveHand() == EnumHand.OFF_HAND) {
            bot.resetActiveHand();
        }
        bot.setHeldItem(EnumHand.OFF_HAND, extracted);
        if (!prevOff.isEmpty()) {
            ItemStack rem = inv.insertItem(slot, prevOff, false);
            if (!rem.isEmpty()) {
                rem = ItemHandlerHelper.insertItemStacked(inv, rem, false);
                if (!rem.isEmpty()) {
                    bot.entityDropItem(rem, 0.0F);
                }
            }
        }
    }
}
