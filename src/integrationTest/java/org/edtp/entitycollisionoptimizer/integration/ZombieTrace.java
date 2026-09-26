package org.edtp.entitycollisionoptimizer.integration;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;

/** Stable physical and lifecycle state recorded for zombies in integration scenarios. */
final class ZombieTrace {
    private ZombieTrace() {
    }

    static void normalize(Zombie zombie, long seed) {
        zombie.setOldPosAndRot();
        zombie.setDeltaMovement(Vec3.ZERO);
        zombie.setOnGround(true);
        zombie.setNoAi(true);
        zombie.setNoGravity(false);
        zombie.setSilent(false);
        zombie.setInvulnerable(false);
        zombie.setPersistenceRequired();
        zombie.setCanPickUpLoot(false);
        zombie.setBaby(false);
        zombie.setHealth(zombie.getMaxHealth());
        zombie.setAbsorptionAmount(0.0F);
        zombie.clearFire();
        zombie.setAirSupply(zombie.getMaxAirSupply());
        zombie.setYRot(0.0F);
        zombie.setXRot(0.0F);
        zombie.setYHeadRot(0.0F);
        zombie.setYBodyRot(0.0F);
        zombie.yRotO = 0.0F;
        zombie.xRotO = 0.0F;
        zombie.yHeadRotO = 0.0F;
        zombie.yBodyRotO = 0.0F;
        zombie.hurtTime = 0;
        zombie.hurtDuration = 0;
        zombie.deathTime = 0;
        zombie.invulnerableTime = 0;
        zombie.fallDistance = 0.0F;
        zombie.tickCount = 0;
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            zombie.setItemSlot(slot, ItemStack.EMPTY);
        }
        zombie.getRandom().setSeed(seed);
    }

    static void writeFrame(
            DataOutputStream output,
            List<Zombie> zombies,
            Vec3 origin,
            int tick
    ) throws IOException {
        output.writeInt(tick);
        for (int index = 0; index < zombies.size(); index++) {
            writeState(output, zombies.get(index), origin, index);
        }
    }

    static void writeState(
            DataOutputStream output,
            Zombie zombie,
            Vec3 origin,
            int index
    ) throws IOException {
        output.writeInt(index);
        writeVec(output, zombie.position().subtract(origin));
        writeVec(output, zombie.getDeltaMovement());

        AABB bounds = zombie.getBoundingBox();
        writeDouble(output, bounds.minX - origin.x);
        writeDouble(output, bounds.minY - origin.y);
        writeDouble(output, bounds.minZ - origin.z);
        writeDouble(output, bounds.maxX - origin.x);
        writeDouble(output, bounds.maxY - origin.y);
        writeDouble(output, bounds.maxZ - origin.z);

        writeDouble(output, zombie.xo - origin.x);
        writeDouble(output, zombie.yo - origin.y);
        writeDouble(output, zombie.zo - origin.z);
        writeDouble(output, zombie.xOld - origin.x);
        writeDouble(output, zombie.yOld - origin.y);
        writeDouble(output, zombie.zOld - origin.z);
        writeFloat(output, zombie.getHealth());
        writeFloat(output, zombie.getAbsorptionAmount());
        writeDouble(output, zombie.fallDistance);
        writeFloat(output, zombie.getYRot());
        writeFloat(output, zombie.getXRot());
        writeFloat(output, zombie.getYHeadRot());
        writeFloat(output, zombie.yBodyRot);
        writeFloat(output, zombie.yRotO);
        writeFloat(output, zombie.xRotO);
        writeFloat(output, zombie.yHeadRotO);
        writeFloat(output, zombie.yBodyRotO);

        output.writeInt(zombie.tickCount);
        output.writeInt(zombie.hurtTime);
        output.writeInt(zombie.hurtDuration);
        output.writeInt(zombie.deathTime);
        output.writeInt(zombie.invulnerableTime);
        output.writeInt(zombie.getAirSupply());
        output.writeInt(zombie.getRemainingFireTicks());
        output.writeInt(zombie.getTicksFrozen());
        output.writeInt(zombie.getNoActionTime());
        output.writeInt(zombie.getPose().ordinal());
        output.writeInt(flags(zombie));
    }

    static void writeDouble(DataOutputStream output, double value) throws IOException {
        output.writeLong(Double.doubleToRawLongBits(value));
    }

    private static void writeVec(DataOutputStream output, Vec3 value) throws IOException {
        writeDouble(output, value.x);
        writeDouble(output, value.y);
        writeDouble(output, value.z);
    }

    private static void writeFloat(DataOutputStream output, float value) throws IOException {
        output.writeInt(Float.floatToRawIntBits(value));
    }

    private static int flags(Zombie zombie) {
        int flags = 0;
        if (zombie.onGround()) flags |= 1;
        if (zombie.horizontalCollision) flags |= 1 << 1;
        if (zombie.verticalCollision) flags |= 1 << 2;
        if (zombie.verticalCollisionBelow) flags |= 1 << 3;
        if (zombie.minorHorizontalCollision) flags |= 1 << 4;
        if (zombie.hurtMarked) flags |= 1 << 5;
        if (zombie.isAlive()) flags |= 1 << 7;
        if (zombie.isDeadOrDying()) flags |= 1 << 8;
        if (zombie.isRemoved()) flags |= 1 << 9;
        if (zombie.isBaby()) flags |= 1 << 10;
        if (zombie.isNoGravity()) flags |= 1 << 11;
        if (zombie.isSilent()) flags |= 1 << 12;
        if (zombie.noPhysics) flags |= 1 << 13;
        if (zombie.isInWater()) flags |= 1 << 14;
        if (zombie.isInLava()) flags |= 1 << 15;
        return flags;
    }
}
