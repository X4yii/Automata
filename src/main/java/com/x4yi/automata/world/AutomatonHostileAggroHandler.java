package com.x4yi.automata.world;

import net.minecraft.entity.EntityCreature;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.ai.EntityAINearestAttackableTarget;
import net.minecraft.entity.monster.IMob;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.entity.ProjectileImpactEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingSetAttackTargetEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import com.x4yi.automata.AutomataMod;
import com.x4yi.automata.compat.SpartanWeaponryCompat;
import com.x4yi.automata.entity.EntityAutomaton;

/**
 * Registers hostile mobs to target active automata just like vanilla players.
 */
@Mod.EventBusSubscriber(modid = AutomataMod.MOD_ID)
public final class AutomatonHostileAggroHandler {

    private AutomatonHostileAggroHandler() {}

    @SubscribeEvent
    public static void onEntityJoinWorld(EntityJoinWorldEvent event) {
        if (event.getWorld().isRemote) {
            return;
        }
        if (!(event.getEntity() instanceof EntityCreature creature) || !(creature instanceof IMob)) {
            return;
        }
        boolean alreadyAdded = creature.targetTasks.taskEntries.stream()
                .anyMatch(entry -> entry.action instanceof AutomatonTargetAI);
        if (!alreadyAdded) {
            creature.targetTasks.addTask(2, new AutomatonTargetAI(creature));
        }
    }

    @SubscribeEvent
    public static void onSetAttackTarget(LivingSetAttackTargetEvent event) {
        if (event.getEntityLiving().world.isRemote) {
            return;
        }
        if (event.getTarget() instanceof EntityAutomaton bot && bot.isDowned()
                && event.getEntityLiving() instanceof EntityLiving living) {
            living.setAttackTarget(null);
            living.setRevengeTarget(null);
        }
    }

    @SubscribeEvent
    public static void onLivingAttack(LivingAttackEvent event) {
        if (event.getEntityLiving().world.isRemote) {
            return;
        }
        if (event.getEntityLiving() instanceof EntityAutomaton bot && bot.isDowned()) {
            if (event.getSource().getTrueSource() instanceof EntityLiving living) {
                living.setAttackTarget(null);
                living.setRevengeTarget(null);
            }
            event.setCanceled(true);
            return;
        }
        if (event.getEntityLiving() instanceof EntityAutomaton
                && SpartanWeaponryCompat.isBoomerangEntity(event.getSource().getImmediateSource())
                && event.getSource().getTrueSource() instanceof EntityAutomaton) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onProjectileImpact(ProjectileImpactEvent event) {
        if (event.getEntity().world.isRemote) {
            return;
        }
        if (event.getRayTraceResult() != null
                && event.getRayTraceResult().entityHit instanceof EntityAutomaton
                && event.getEntity() instanceof EntityArrow arrow
                && arrow.shootingEntity instanceof EntityAutomaton
                && SpartanWeaponryCompat.isBoomerangEntity(arrow)) {
            event.setCanceled(true);
        }
    }

    private static final class AutomatonTargetAI extends EntityAINearestAttackableTarget<EntityAutomaton> {

        AutomatonTargetAI(EntityCreature creature) {
            super(creature, EntityAutomaton.class, 10, true, false,
                    bot -> bot != null && bot.isEntityAlive() && !bot.isDowned());
        }

        @Override
        public boolean shouldContinueExecuting() {
            if (this.targetEntity instanceof EntityAutomaton bot && bot.isDowned()) {
                return false;
            }
            return super.shouldContinueExecuting();
        }
    }
}

