package org.edtp.entitycollisionoptimizer.collision.bytecode;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.service.MixinService;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Uniform field access boundary; consumer mixins declare coverage, not per-consumer behavior.
 *
 * <p>The rewrite below compares field references taken from already-transformed game classes, so
 * the names must be the <em>runtime</em> names. Loom remaps class references, mixin annotations
 * and {@code @Shadow} member names, but not string constants: on a Minecraft version whose runtime
 * namespace is intermediary (1.21.11 and earlier) literal Mojang names such as
 * {@code net.minecraft.world.entity.Entity.position} never match, and the off-heap body would
 * silently stop being synchronised with the entity fields.
 *
 * <p>Rather than restating names, the identities are read back from this mod's own
 * {@link org.edtp.entitycollisionoptimizer.mixin.EntityBodyMixin} accessors. Those method names are
 * ours (never remapped), while the field references inside them are the remapped runtime ones, so
 * the rewrite is namespace independent by construction. A failure to locate them, or a rewrite
 * that matches nothing in the entity class, aborts loudly instead of degrading into stale
 * collision state.
 */
public final class BodyFieldAccess {
    private static final String MIXIN = "org/edtp/entitycollisionoptimizer/mixin/EntityBodyMixin";
    private static final String ACCESS = "org/edtp/entitycollisionoptimizer/collision/CollisionBodyAccess";
    private static final Map<String, Boolean> ENTITY_TYPES = new ConcurrentHashMap<>();

    private static volatile FieldId position, velocity, bounds, sync, physics;

    private record FieldId(String owner, String name, String desc) {}

    public static void rewrite(ClassNode node) {
        FieldId entity = entity();
        boolean entityClass = node.name.equals(entity.owner());
        if (entityClass) {
            verifyEntityAccessors(node, entity);
        }
        int rewrites = 0;
        for (MethodNode method : node.methods) {
            // Only these storage primitives may physically touch the unbound field.
            if (entityClass && (method.name.equals("eco$readVelocity") || method.name.equals("eco$writeVelocity")
                    || method.name.equals("eco$readNeedsSync") || method.name.equals("eco$writeNeedsSync")
                    || method.name.equals("eco$writeNoPhysics") || method.name.equals("eco$writePosition")
                    || method.name.equals("eco$readPosition")
                    || method.name.equals("eco$readBounds") || method.name.equals("eco$writeBounds")
                    || method.name.equals("eco$detachBody"))) continue;
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (!(instruction instanceof FieldInsnNode field)) continue;
                boolean read = field.getOpcode() == Opcodes.GETFIELD;
                boolean positionField = matches(field, position());
                boolean velocityField = matches(field, velocity());
                boolean boundsField = matches(field, bounds());
                // The two boolean flags are public fields of Entity, so a reference resolves through
                // the *static type of the receiver*: `this.noPhysics = false` inside ItemEntity is a
                // PUTFIELD owned by ItemEntity, not by Entity. Only the private Vec3/AABB fields are
                // always owned by Entity. Requiring an exact Entity owner here silently skipped every
                // flag write in the game (and in the contract tests), leaving the native row's guard
                // bits stale. Any Entity subtype owner is the same field, so match the member and
                // prove the owner is an Entity.
                boolean syncField = matchesMember(field, sync());
                boolean physicsField = !read && matchesMember(field, physics());
                if (!velocityField && !positionField && !boundsField
                        && !((syncField || physicsField) && isEntity(field.owner, node))) continue;
                if (!read && field.getOpcode() != Opcodes.PUTFIELD) {
                    throw new IllegalStateException("Unexpected collision body field opcode");
                }
                String name = velocityField ? (read ? "eco$readVelocity" : "eco$writeVelocity")
                        : positionField ? (read ? "eco$readPosition" : "eco$writePosition")
                        : boundsField ? (read ? "eco$readBounds" : "eco$writeBounds")
                        : syncField ? (read ? "eco$readNeedsSync" : "eco$writeNeedsSync") : "eco$writeNoPhysics";
                method.instructions.set(field, new MethodInsnNode(Opcodes.INVOKEINTERFACE, ACCESS,
                        name, read ? "()" + field.desc : "(" + field.desc + ")V", true));
                rewrites++;
            }
        }
        // The entity class always accesses its own body fields; zero rewrites means the
        // identities resolved to nothing and the off-heap body would run desynchronised.
        if (entityClass && rewrites == 0) {
            throw new IllegalStateException("Collision body field rewrite matched nothing in " + entity.owner()
                    + "; refusing to run with unsynchronised collision state");
        }
    }

    /** The remapped entity class name, which is also the owner of every body field. */
    public static String entityName() {
        return entity().owner();
    }

    private static boolean matches(FieldInsnNode field, FieldId id) {
        return field.owner.equals(id.owner()) && field.name.equals(id.name()) && field.desc.equals(id.desc());
    }

    /** Member-only match; the owner is validated separately through {@link #isEntity}. */
    private static boolean matchesMember(FieldInsnNode field, FieldId id) {
        return field.name.equals(id.name()) && field.desc.equals(id.desc());
    }

    private static FieldId entity() {
        return position();
    }

    private static FieldId position() {
        return position != null ? position : resolve();
    }

    private static FieldId velocity() {
        position();
        return velocity;
    }

    private static FieldId bounds() {
        position();
        return bounds;
    }

    private static FieldId sync() {
        position();
        return sync;
    }

    private static FieldId physics() {
        position();
        return physics;
    }

    private static synchronized FieldId resolve() {
        if (position != null) return position;
        ClassNode mixin = classNode(MIXIN);
        // The field owner is the mixin's target class: inside the mixin, the shadow field
        // reference is owned by the mixin itself, but Loom remaps @Mixin's value.
        String owner = mixinTarget(mixin);
        position = fieldOf(mixin, owner, "eco$readPosition", Opcodes.GETFIELD);
        velocity = fieldOf(mixin, owner, "eco$readVelocity", Opcodes.GETFIELD);
        bounds = fieldOf(mixin, owner, "eco$readBounds", Opcodes.GETFIELD);
        sync = fieldOf(mixin, owner, "eco$readNeedsSync", Opcodes.GETFIELD);
        physics = fieldOf(mixin, owner, "eco$writeNoPhysics", Opcodes.PUTFIELD);
        if (!physics.desc().equals("Z") || !sync.desc().equals("Z")) {
            throw new IllegalStateException("Collision body flag fields resolved to unexpected descriptors "
                    + sync + " / " + physics);
        }
        return position;
    }

    private static String mixinTarget(ClassNode mixin) {
        for (var annotations : new java.util.List[]{mixin.visibleAnnotations, mixin.invisibleAnnotations}) {
            if (annotations == null) continue;
            for (org.objectweb.asm.tree.AnnotationNode annotation : (java.util.List<org.objectweb.asm.tree.AnnotationNode>) annotations) {
                if (!"Lorg/spongepowered/asm/mixin/Mixin;".equals(annotation.desc) || annotation.values == null) continue;
                for (int index = 0; index + 1 < annotation.values.size(); index += 2) {
                    if (!"value".equals(annotation.values.get(index))) continue;
                    Object value = annotation.values.get(index + 1);
                    if (value instanceof org.objectweb.asm.Type type) return type.getInternalName();
                    if (value instanceof java.util.List<?> list && !list.isEmpty()
                            && list.get(0) instanceof org.objectweb.asm.Type first) {
                        return first.getInternalName();
                    }
                }
            }
        }
        throw new IllegalStateException("Cannot read the @Mixin target of " + MIXIN);
    }

    private static FieldId fieldOf(ClassNode mixin, String owner, String accessor, int opcode) {
        for (MethodNode method : mixin.methods) {
            if (!method.name.equals(accessor)) continue;
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof FieldInsnNode field && field.getOpcode() == opcode
                        && !field.name.startsWith("eco$")) {
                    return new FieldId(owner, field.name, field.desc);
                }
            }
        }
        throw new IllegalStateException("Cannot read the collision body field identity from " + MIXIN + "." + accessor
                + "; the off-heap body would not stay in sync");
    }

    private static ClassNode classNode(String name) {
        try {
            return MixinService.getService().getBytecodeProvider().getClassNode(name, false);
        } catch (java.io.IOException | ClassNotFoundException failure) {
            throw new IllegalStateException("Cannot read " + name, failure);
        }
    }

    /** A missing accessor means the rewrite would run desynchronised; fail instead. */
    private static void verifyEntityAccessors(ClassNode entity, FieldId id) {
        for (String required : new String[]{"eco$readPosition", "eco$writePosition", "eco$readVelocity",
                "eco$writeVelocity", "eco$readBounds", "eco$writeBounds", "eco$readNeedsSync",
                "eco$writeNeedsSync", "eco$writeNoPhysics"}) {
            boolean present = false;
            for (MethodNode method : entity.methods) {
                if (method.name.equals(required)) {
                    present = true;
                    break;
                }
            }
            if (!present) {
                throw new IllegalStateException("Collision body accessor " + required + " missing from "
                        + id.owner() + "; refusing to run with unsynchronised collision state");
            }
        }
    }

    private static boolean isEntity(String type, ClassNode current) {
        String entity = entityName();
        if (type.equals(entity)) return true;
        if (type.equals("java/lang/Object")) return false;
        Boolean cached = ENTITY_TYPES.get(type);
        if (cached != null) return cached;
        ClassNode owner;
        try {
            owner = type.equals(current.name) ? current : MixinService.getService()
                    .getBytecodeProvider().getClassNode(type, false);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Cannot resolve collision field owner " + type, failure);
        } catch (ClassNotFoundException absent) {
            // An unresolvable owner cannot be proven to be an Entity; leave the access raw
            // instead of rewriting a field that may belong to an unrelated foreign class.
            ENTITY_TYPES.put(type, false);
            return false;
        }
        boolean result = owner.superName != null && isEntity(owner.superName, current);
        ENTITY_TYPES.put(type, result);
        return result;
    }

    private BodyFieldAccess() {}
}
