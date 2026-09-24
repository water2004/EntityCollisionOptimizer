package org.edtp.entitycollisionoptimizer.collision.bytecode;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.spongepowered.asm.service.MixinService;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Uniform field access boundary; consumer mixins declare coverage, not per-consumer behavior. */
public final class BodyFieldAccess {
    private static final String ENTITY = "net/minecraft/world/entity/Entity";
    private static final String VECTOR = "Lnet/minecraft/world/phys/Vec3;";
    private static final String ACCESS = "org/edtp/entitycollisionoptimizer/collision/CollisionBodyAccess";
    private static final Map<String, Boolean> ENTITY_TYPES = new ConcurrentHashMap<>();

    public static void rewrite(ClassNode node) {
        boolean entityClass = node.name.equals(ENTITY);
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
                boolean velocity = field.owner.equals(ENTITY) && field.name.equals("deltaMovement") && field.desc.equals(VECTOR);
                boolean position = field.owner.equals(ENTITY) && field.name.equals("position") && field.desc.equals(VECTOR);
                boolean bounds = field.owner.equals(ENTITY) && field.name.equals("bb")
                        && field.desc.equals("Lnet/minecraft/world/phys/AABB;");
                boolean sync = field.name.equals("needsSync") && field.desc.equals("Z");
                boolean physics = !read && field.name.equals("noPhysics") && field.desc.equals("Z");
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
            }
        }
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
