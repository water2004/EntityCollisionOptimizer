package org.edtp.entitycollisionoptimizer.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/** Field-consumer inventory from the 26.3 bytecode audit. Every target uses the same transformer. */
@Pseudo
@Mixin(targets = {
        "net.minecraft.server.level.ServerEntity", "net.minecraft.server.level.ChunkMap",
        "net.minecraft.world.entity.ExperienceOrb", "net.minecraft.world.entity.LivingEntity",
        "net.minecraft.world.entity.animal.camel.Camel", "net.minecraft.world.entity.animal.dolphin.Dolphin",
        "net.minecraft.world.entity.animal.fish.AbstractFish", "net.minecraft.world.entity.animal.equine.AbstractHorse",
        "net.minecraft.world.entity.animal.nautilus.AbstractNautilus", "net.minecraft.world.entity.item.ItemEntity",
        "net.minecraft.world.entity.decoration.BlockAttachedEntity", "net.minecraft.world.entity.monster.Blaze",
        "net.minecraft.world.entity.monster.Guardian$GuardianAttackGoal", "net.minecraft.world.entity.monster.Guardian",
        "net.minecraft.world.entity.monster.Shulker", "net.minecraft.world.entity.monster.cubemob.MagmaCube",
        "net.minecraft.world.entity.monster.cubemob.AbstractCubeMob", "net.minecraft.world.entity.monster.cubemob.SulfurCube",
        "net.minecraft.world.entity.projectile.FireworkRocketEntity", "net.minecraft.world.entity.projectile.ShulkerBullet",
        "net.minecraft.world.entity.projectile.Projectile", "net.minecraft.world.entity.projectile.hurtingprojectile.AbstractHurtingProjectile",
        "net.minecraft.world.entity.projectile.arrow.AbstractArrow",
        "net.minecraft.world.entity.AreaEffectCloud", "net.minecraft.world.entity.Display",
        "net.minecraft.world.entity.Interaction", "net.minecraft.world.entity.Marker", "net.minecraft.world.entity.OminousItemSpawner",
        "net.minecraft.world.entity.boss.enderdragon.EnderDragon", "net.minecraft.world.entity.decoration.ArmorStand",
        "net.minecraft.world.entity.monster.Vex", "net.minecraft.world.entity.player.Player",
        "carpet.script.utils.EntityTools", "carpet.script.value.EntityValue$2",
        // Fuji widens Entity.position and reads it directly here.
        "mod.fuji.core.auxiliary.minecraft.EntityHelper"
})
public abstract class BodyFieldConsumersMixin {}
