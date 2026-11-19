package com.x4yi.automata.compat;

import java.util.Locale;

import javax.annotation.Nullable;

import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.attributes.AttributeModifier;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.EnumAction;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.items.IItemHandlerModifiable;

import com.x4yi.automata.entity.EntityAutomaton;

public final class ModdedWeaponCompat {

    public static final String BETTER_SURVIVAL_MOD_ID = "mujmajnkraftsbettersurvival";
    public static final String NOCUBES_SRP_MOD_ID = "nocubessrparmory";

    private static volatile boolean reflectionInit;
    @Nullable
    private static Class<?> bsWeaponClass;
    @Nullable
    private static Class<?> bsNunchakuClass;
    @Nullable
    private static Class<?> bsDaggerClass;
    @Nullable
    private static Class<?> bsSpearClass;
    @Nullable
    private static Class<?> bsHammerClass;
    @Nullable
    private static Class<?> bsBattleAxeClass;
    @Nullable
    private static Class<?> bsCrossbowClass;

    private ModdedWeaponCompat() {}

    private static boolean isModLoadedSafe(String modId) {
        try {
            return Loader.isModLoaded(modId);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void ensureReflection() {
        if (reflectionInit) {
            return;
        }
        synchronized (ModdedWeaponCompat.class) {
            if (reflectionInit) {
                return;
            }
            if (isModLoadedSafe(BETTER_SURVIVAL_MOD_ID)) {
                bsWeaponClass = tryLoad("com.mujmajnkraft.bettersurvival.items.ItemCustomWeapon");
                bsNunchakuClass = tryLoad("com.mujmajnkraft.bettersurvival.items.ItemNunchaku");
                bsDaggerClass = tryLoad("com.mujmajnkraft.bettersurvival.items.ItemDagger");
                bsSpearClass = tryLoad("com.mujmajnkraft.bettersurvival.items.ItemSpear");
                bsHammerClass = tryLoad("com.mujmajnkraft.bettersurvival.items.ItemHammer");
                bsBattleAxeClass = tryLoad("com.mujmajnkraft.bettersurvival.items.ItemBattleAxe");
                bsCrossbowClass = tryLoad("com.mujmajnkraft.bettersurvival.items.ItemCrossbow");
            }
            reflectionInit = true;
        }
    }

    @Nullable
    private static Class<?> tryLoad(String name) {
        try {
            return Class.forName(name, false, ModdedWeaponCompat.class.getClassLoader());
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Nullable
    private static String getNamespace(ItemStack stack) {
        ResourceLocation key = stack.isEmpty() ? null : stack.getItem().getRegistryName();
        return key == null ? null : key.getNamespace();
    }

    private static String getPath(ItemStack stack) {
        ResourceLocation key = stack.isEmpty() ? null : stack.getItem().getRegistryName();
        return key == null ? "" : key.getPath().toLowerCase(Locale.ROOT);
    }

    public static boolean isBetterSurvivalWeapon(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        ensureReflection();
        Item item = stack.getItem();
        if ((bsWeaponClass != null && bsWeaponClass.isInstance(item))
                || (bsNunchakuClass != null && bsNunchakuClass.isInstance(item))
                || (bsCrossbowClass != null && bsCrossbowClass.isInstance(item))) {
            return true;
        }
        return BETTER_SURVIVAL_MOD_ID.equals(getNamespace(stack));
    }

    public static boolean isBetterSurvivalNunchaku(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        ensureReflection();
        if (bsNunchakuClass != null && bsNunchakuClass.isInstance(stack.getItem())) {
            return true;
        }
        return BETTER_SURVIVAL_MOD_ID.equals(getNamespace(stack)) && getPath(stack).contains("nunchaku");
    }

    public static boolean isBetterSurvivalDagger(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        ensureReflection();
        if (bsDaggerClass != null && bsDaggerClass.isInstance(stack.getItem())) {
            return true;
        }
        return BETTER_SURVIVAL_MOD_ID.equals(getNamespace(stack)) && getPath(stack).contains("dagger");
    }

    public static boolean isBetterSurvivalSpear(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        ensureReflection();
        if (bsSpearClass != null && bsSpearClass.isInstance(stack.getItem())) {
            return true;
        }
        return BETTER_SURVIVAL_MOD_ID.equals(getNamespace(stack)) && getPath(stack).contains("spear");
    }

    public static boolean isBetterSurvivalCrossbow(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        ensureReflection();
        if (bsCrossbowClass != null && bsCrossbowClass.isInstance(stack.getItem())) {
            return true;
        }
        return BETTER_SURVIVAL_MOD_ID.equals(getNamespace(stack)) && getPath(stack).contains("crossbow");
    }

    public static boolean isBetterSurvivalTwoHanded(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        ensureReflection();
        Item item = stack.getItem();
        if ((bsSpearClass != null && bsSpearClass.isInstance(item))
                || (bsHammerClass != null && bsHammerClass.isInstance(item))
                || (bsBattleAxeClass != null && bsBattleAxeClass.isInstance(item))) {
            return true;
        }
        if (!BETTER_SURVIVAL_MOD_ID.equals(getNamespace(stack))) {
            return false;
        }
        String p = getPath(stack);
        return p.contains("spear") || p.contains("hammer") || p.contains("battleaxe");
    }

    public static boolean isNoCubesSrpWeapon(ItemStack stack) {
        if (stack.isEmpty() || !NOCUBES_SRP_MOD_ID.equals(getNamespace(stack))) {
            return false;
        }
        if (stack.getItemUseAction() == EnumAction.BOW) {
            return true;
        }
        for (AttributeModifier mod : stack.getAttributeModifiers(EntityEquipmentSlot.MAINHAND)
                .get(SharedMonsterAttributes.ATTACK_DAMAGE.getName())) {
            if (mod.getAmount() > 0.0D) {
                return true;
            }
        }
        String path = getPath(stack);
        return path.contains("claw")
                || path.contains("sword")
                || path.contains("knife")
                || path.contains("sickle")
                || path.contains("rapier")
                || path.contains("scythe")
                || path.contains("reaper")
                || path.contains("dagger")
                || path.contains("mallet")
                || path.contains("axe")
                || path.contains("hatchet")
                || path.contains("shellbreaker")
                || path.contains("blade")
                || path.contains("bow")
                || path.contains("flamethrower")
                || path.contains("incinerator")
                || path.contains("plasmatorch")
                || path.contains("shuriken")
                || path.contains("bomb")
                || path.contains("tentacle")
                || path.contains("miasm");
    }

    public static boolean isNoCubesTickRangedWeapon(ItemStack stack) {
        if (stack.isEmpty() || !NOCUBES_SRP_MOD_ID.equals(getNamespace(stack))) {
            return false;
        }
        String path = getPath(stack);
        return path.equals("flamethrower")
                || path.equals("incinerator")
                || path.equals("plasmatorch")
                || path.equals("pestilentshuriken")
                || path.equals("twistedbomb")
                || path.equals("overlordblade");
    }

    public static boolean isNoCubesSelfAmmo(ItemStack stack) {
        if (stack.isEmpty() || !NOCUBES_SRP_MOD_ID.equals(getNamespace(stack))) {
            return false;
        }
        String path = getPath(stack);
        return path.equals("pestilentshuriken")
                || path.equals("twistedbomb")
                || path.equals("hostbomb")
                || path.equals("headbomb");
    }

    public static boolean isNoCubesFuelAmmo(ItemStack stack) {
        if (stack.isEmpty() || !NOCUBES_SRP_MOD_ID.equals(getNamespace(stack))) {
            return false;
        }
        return getPath(stack).equals("capsulefuel");
    }

    public static boolean isNoCubesTentacleAmmo(ItemStack stack) {
        if (stack.isEmpty() || !NOCUBES_SRP_MOD_ID.equals(getNamespace(stack))) {
            return false;
        }
        return getPath(stack).equals("hostbomb");
    }

    public static boolean isNoCubesZeroAmmoWeapon(ItemStack stack) {
        if (stack.isEmpty() || !NOCUBES_SRP_MOD_ID.equals(getNamespace(stack))) {
            return false;
        }
        return getPath(stack).equals("overlordblade");
    }

    public static boolean isNoCubesRangedItem(ItemStack stack) {
        if (stack.isEmpty() || !NOCUBES_SRP_MOD_ID.equals(getNamespace(stack))) {
            return false;
        }
        return stack.getItemUseAction() == EnumAction.BOW
                && !getPath(stack).equals("overlordblade");
    }

    public static int findNoCubesAmmoSlot(EntityAutomaton bot, ItemStack weapon) {
        if (weapon.isEmpty() || !NOCUBES_SRP_MOD_ID.equals(getNamespace(weapon))) {
            return -1;
        }
        String path = getPath(weapon);
        if (isNoCubesZeroAmmoWeapon(weapon) || isNoCubesSelfAmmo(weapon)) {
            return -1;
        }
        IItemHandlerModifiable inv = bot.getInventory().getCombinedInventory();
        if (path.equals("flamethrower") || path.equals("incinerator") || path.equals("plasmatorch")) {
            for (int i = 0; i < inv.getSlots(); i++) {
                if (isNoCubesFuelAmmo(inv.getStackInSlot(i))) {
                    return i;
                }
            }
            return -2;
        }
        if (path.equals("hosttentacle")) {
            for (int i = 0; i < inv.getSlots(); i++) {
                if (isNoCubesTentacleAmmo(inv.getStackInSlot(i))) {
                    return i;
                }
            }
            return -2;
        }
        return -3;
    }

    public static boolean isRapidSpamWeapon(ItemStack stack) {
        return SpartanWeaponryCompat.isQuickStrikeWeapon(stack)
                || isBetterSurvivalNunchaku(stack)
                || isBetterSurvivalDagger(stack)
                || (NOCUBES_SRP_MOD_ID.equals(getNamespace(stack))
                        && (getPath(stack).contains("knife") || getPath(stack).contains("dagger")));
    }

    public static double getExtraReachBonus(ItemStack stack) {
        if (stack.isEmpty()) {
            return 0.0D;
        }
        double srpReach = SrpCompat.getSrpWeaponReachBonus(stack);
        if (srpReach > 0.0D) {
            return srpReach;
        }
        if (isBetterSurvivalSpear(stack)) {
            return 1.5D;
        }
        return 0.0D;
    }
}

