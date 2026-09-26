package org.edtp.entitycollisionoptimizer.collision.bytecode;

import org.edtp.entitycollisionoptimizer.collision.RuntimeMappings;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.spongepowered.asm.service.MixinService;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Uniform field access boundary; consumer mixins declare coverage, not per-consumer behavior. */
public final class BodyFieldAccess {
    private static final String ENTITY = RuntimeMappings.type("net.minecraft.class_1297");
    private static final String BOUNDS = "L" + RuntimeMappings.type("net.minecraft.class_238") + ";";
    private static final String VELOCITY_FIELD = RuntimeMappings.field(
            "net.minecraft.class_1297", "field_18276", "Lnet/minecraft/class_243;");
    private static final String POSITION_FIELD = RuntimeMappings.field(
            "net.minecraft.class_1297", "field_22467", "Lnet/minecraft/class_243;");
    private static final String BOUNDS_FIELD = RuntimeMappings.field(
            "net.minecraft.class_1297", "field_6005", "Lnet/minecraft/class_238;");
    private static final String SYNC_FIELD = RuntimeMappings.field("net.minecraft.class_1297", "field_6007", "Z");
    private static final String PHYSICS_FIELD = RuntimeMappings.field("net.minecraft.class_1297", "field_5960", "Z");
    private static final String VECTOR = "L" + RuntimeMappings.type("net.minecraft.class_243") + ";";
    private static final String ACCESS = "org/edtp/entitycollisionoptimizer/collision/CollisionBodyAccess";
    private static final Map<String, Boolean> ENTITY_TYPES = new ConcurrentHashMap<>();

    public static void rewrite(ClassNode node) {
        boolean entityClass = node.name.equals(ENTITY);
        if (entityClass && node.fields.stream().noneMatch(field ->
                field.name.equals(VELOCITY_FIELD) && field.desc.equals(VECTOR)
                        && (field.access & Opcodes.ACC_PRIVATE) != 0)) {
            throw new IllegalStateException("Expected Minecraft 1.21.1's private Entity velocity field");
        }
        if (entityClass && node.fields.stream().noneMatch(field ->
                field.name.equals(POSITION_FIELD) && field.desc.equals(VECTOR)
                        && (field.access & Opcodes.ACC_PRIVATE) != 0)) {
            throw new IllegalStateException("Expected Minecraft 1.21.1's private Entity position field");
        }
        int reads = 0, writes = 0, positionWrites = 0, boundsWrites = 0;
        for (var method : node.methods) {
            // Only these storage primitives may physically touch the unbound field.
            if (entityClass && (method.name.equals("eco$readVelocity") || method.name.equals("eco$writeVelocity")
                    || method.name.equals("eco$readNeedsSync") || method.name.equals("eco$writeNeedsSync")
                    || method.name.equals("eco$writeNoPhysics") || method.name.equals("eco$writePosition")
                    || method.name.equals("eco$readPosition")
                    || method.name.equals("eco$readBounds") || method.name.equals("eco$writeBounds")
                    || method.name.equals("eco$detachBody"))) continue;
            for (var instruction : method.instructions.toArray()) {
                if (!(instruction instanceof FieldInsnNode field)) continue;
                boolean read = field.getOpcode() == Opcodes.GETFIELD;
                boolean velocity = field.owner.equals(ENTITY) && field.name.equals(VELOCITY_FIELD) && field.desc.equals(VECTOR);
                boolean position = field.owner.equals(ENTITY) && field.name.equals(POSITION_FIELD) && field.desc.equals(VECTOR);
                boolean bounds = field.owner.equals(ENTITY) && field.name.equals(BOUNDS_FIELD)
                        && field.desc.equals(BOUNDS);
                boolean sync = field.name.equals(SYNC_FIELD) && field.desc.equals("Z");
                boolean physics = !read && field.name.equals(PHYSICS_FIELD) && field.desc.equals("Z");
                if (!velocity && !position && !bounds && !((sync || physics) && isEntity(field.owner, node))) continue;
                if (!read && field.getOpcode() != Opcodes.PUTFIELD) {
                    throw new IllegalStateException("Unexpected collision body field opcode");
                }
                String name = velocity ? (read ? "eco$readVelocity" : "eco$writeVelocity")
                        : position ? (read ? "eco$readPosition" : "eco$writePosition")
                        : bounds ? (read ? "eco$readBounds" : "eco$writeBounds")
                        : sync ? (read ? "eco$readNeedsSync" : "eco$writeNeedsSync") : "eco$writeNoPhysics";
                method.instructions.set(field, new MethodInsnNode(Opcodes.INVOKEINTERFACE, ACCESS,
                        name, read ? "()" + field.desc : "(" + field.desc + ")V", true));
                if (velocity) { if (read) reads++; else writes++; }
                if (position && !read) positionWrites++;
                if (bounds && !read) boundsWrites++;
            }
        }
        // Getter, setter and constructor must all be covered; missing coverage is not a fallback.
        if (entityClass && (reads < 1 || writes < 2)) throw new IllegalStateException("Incomplete Entity velocity access rewrite");
        if (entityClass && positionWrites < 2) throw new IllegalStateException("Incomplete Entity position write rewrite");
        if (entityClass && boundsWrites < 2) throw new IllegalStateException("Incomplete Entity bounding box write rewrite");
    }

    private static boolean isEntity(String type, ClassNode current) {
        if (type.equals(ENTITY)) return true;
        if (type.equals("java/lang/Object")) return false;
        Boolean cached = ENTITY_TYPES.get(type);
        if (cached != null) return cached;
        try {
            ClassNode owner = type.equals(current.name) ? current : MixinService.getService()
                    .getBytecodeProvider().getClassNode(type, false);
            boolean result = owner.superName != null && isEntity(owner.superName, current);
            ENTITY_TYPES.put(type, result);
            return result;
        } catch (java.io.IOException | ClassNotFoundException failure) {
            throw new IllegalStateException("Cannot resolve collision field owner " + type, failure);
        }
    }

    private BodyFieldAccess() {}
}
