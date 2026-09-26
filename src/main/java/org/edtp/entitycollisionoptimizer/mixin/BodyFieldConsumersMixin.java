package org.edtp.entitycollisionoptimizer.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/** Field-consumer adapters for Minecraft 1.21.1. Every target uses the same transformer. */
@Pseudo
@Mixin(targets = {
        "net.minecraft.server.level.ServerEntity", "net.minecraft.server.level.ChunkMap",
        "net.minecraft.world.entity.ExperienceOrb", "net.minecraft.world.entity.LivingEntity",
        "net.minecraft.world.entity.animal.camel.Camel", "net.minecraft.world.entity.animal.Dolphin",
        "net.minecraft.world.entity.animal.AbstractFish", "net.minecraft.world.entity.animal.horse.AbstractHorse",
        "net.minecraft.world.entity.item.ItemEntity",
        "net.minecraft.world.entity.decoration.BlockAttachedEntity", "net.minecraft.world.entity.monster.Blaze",
        "net.minecraft.world.entity.monster.Guardian$GuardianAttackGoal", "net.minecraft.world.entity.monster.Guardian",
        "net.minecraft.world.entity.monster.Shulker", "net.minecraft.world.entity.monster.MagmaCube",
        "net.minecraft.world.entity.monster.Slime",
        "net.minecraft.world.entity.projectile.FireworkRocketEntity", "net.minecraft.world.entity.projectile.ShulkerBullet",
        "net.minecraft.world.entity.projectile.Projectile", "net.minecraft.world.entity.projectile.AbstractHurtingProjectile",
        "net.minecraft.world.entity.projectile.AbstractArrow",
        "net.minecraft.world.entity.AreaEffectCloud", "net.minecraft.world.entity.Display",
        "net.minecraft.world.entity.Interaction", "net.minecraft.world.entity.Marker", "net.minecraft.world.entity.OminousItemSpawner",
        "net.minecraft.world.entity.boss.enderdragon.EnderDragon", "net.minecraft.world.entity.decoration.ArmorStand",
        "net.minecraft.world.entity.monster.Vex", "net.minecraft.world.entity.player.Player",
        "carpet.script.utils.EntityTools", "carpet.script.value.EntityValue$2"
})
public abstract class BodyFieldConsumersMixin {}
