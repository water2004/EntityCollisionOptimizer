import org.objectweb.asm.*;

import java.io.ByteArrayInputStream;
import java.nio.file.*;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipInputStream;

/** Read-only audit of vanilla/mod bytecode, including nested Fabric jars. */
public final class BodyFieldAudit {
    private static final String FIELD = System.getProperty("eco.audit.field", "deltaMovement");
    private static final String DESCRIPTOR = Set.of("deltaMovement", "position").contains(FIELD)
            ? "Lnet/minecraft/world/phys/Vec3;" : FIELD.equals("bb") ? "Lnet/minecraft/world/phys/AABB;" : "Z";
    private static final boolean STRICT = Boolean.getBoolean("eco.audit.requireRouted");
    private static final String ENTITY = "net/minecraft/world/entity/Entity";
    private static final String ACCESS = "org/edtp/entitycollisionoptimizer/collision/CollisionBodyAccess";
    private static int classes, fields, routed;
    private static final Set<String> INVENTORY = new HashSet<>();
    public static void main(String[] args) throws Exception {
        String inventory = System.getProperty("eco.audit.inventory", "");
        if (!inventory.isEmpty()) {
            INVENTORY.add(ENTITY);
            for (String marker : inventory.split(",")) INVENTORY.addAll(mixinTargets(Files.readAllBytes(Path.of(marker))));
        }
        for (String arg : args) {
            Path path = Path.of(arg);
            if (Files.isDirectory(path)) {
                try (var files = Files.walk(path)) {
                    for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                        if (file.toString().endsWith(".class")) scanClass(file.toString(), Files.readAllBytes(file));
                        else if (file.toString().endsWith(".jar")) scan(file.toString(), Files.readAllBytes(file));
                    }
                }
            } else if (arg.endsWith(".class")) scanClass(path.toString(), Files.readAllBytes(path));
            else scan(path.toString(), Files.readAllBytes(path));
        }
        System.out.printf("AUDIT field=%s classes=%d raw=%d routed=%d strict=%s inventory=%d%n",
                FIELD, classes, fields, routed, STRICT, INVENTORY.size());
    }

    private static void scan(String archive, byte[] bytes) throws Exception {
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (entry.isDirectory()) continue;
                if (entry.getName().endsWith(".jar")) scan(archive + "!" + entry.getName(), zip.readAllBytes());
                else if (entry.getName().endsWith(".class")) scanClass(archive, zip.readAllBytes());
            }
        }
    }

    private static void scanClass(String archive, byte[] bytes) {
        classes++;
        String typeName = new ClassReader(bytes).getClassName();
        var escapedReads = new java.util.ArrayList<String>();
        boolean[] transformed = {false};
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            private String type;
            @Override public void visit(int v, int a, String n, String s, String p, String[] i) { type = n; }
            @Override public MethodVisitor visitMethod(int a, String method, String d, String s, String[] e) {
                if (method.equals("eco$readVelocity")) transformed[0] = true;
                String location = archive + " " + type + "." + method + d;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override public void visitFieldInsn(int opcode, String owner, String name, String desc) {
                        if (!name.equals(FIELD) || !desc.equals(DESCRIPTOR)) return;
                        // These fields are private to Entity; unrelated records may use the same names.
                        if (Set.of("deltaMovement", "position", "bb").contains(FIELD) && !owner.equals(ENTITY)) return;
                        fields++;
                        System.out.println(location + " " + (opcode == Opcodes.GETFIELD ? "READ" : "WRITE") + " " + owner + "." + name);
                        boolean storage = type.equals(ENTITY) && owner.equals(ENTITY) && switch (FIELD) {
                            case "deltaMovement" -> java.util.Set.of("eco$readVelocity", "eco$writeVelocity", "eco$detachBody").contains(method);
                            case "hasImpulse" -> java.util.Set.of("eco$readNeedsSync", "eco$writeNeedsSync", "eco$detachBody").contains(method);
                            case "noPhysics" -> method.equals("eco$writeNoPhysics");
                            case "position" -> Set.of("eco$readPosition", "eco$writePosition", "eco$detachBody").contains(method);
                            case "bb" -> Set.of("eco$readBounds", "eco$writeBounds", "eco$detachBody").contains(method);
                            default -> false;
                        };
                        if (!storage && !(FIELD.equals("noPhysics") && opcode == Opcodes.GETFIELD)) {
                            escapedReads.add(location);
                        }
                    }
                    @Override public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
                        String suffix = switch (FIELD) {
                            case "deltaMovement" -> "Velocity";
                            case "hasImpulse" -> "NeedsSync";
                            case "noPhysics" -> "NoPhysics";
                            case "position" -> "Position";
                            case "bb" -> "Bounds";
                            default -> "";
                        };
                        if ((owner.equals(ENTITY) || owner.equals(ACCESS))
                                && (name.equals("eco$read" + suffix) || name.equals("eco$write" + suffix))) {
                            routed++;
                            System.out.println(location + " ROUTED " + name);
                        }
                    }
                    @Override public void visitLdcInsn(Object value) {
                        if (FIELD.equals(value)) System.out.println(location + " FIELD_NAME_LITERAL");
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        if ((STRICT || transformed[0]) && !escapedReads.isEmpty()) throw new AssertionError("Unrouted " + FIELD + " access: " + escapedReads);
        if (!INVENTORY.isEmpty() && !escapedReads.isEmpty() && !typeName.startsWith("net/minecraft/client/")) {
            Set<String> targets = mixinTargets(bytes);
            if (targets.isEmpty()) targets.add(typeName);
            targets.removeIf(type -> type.startsWith("net/minecraft/client/") || INVENTORY.contains(type));
            if (!targets.isEmpty()) throw new AssertionError("Consumer missing from server field inventory: " + targets);
        }
    }

    private static Set<String> mixinTargets(byte[] bytes) {
        Set<String> targets = new HashSet<>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                if (!descriptor.equals("Lorg/spongepowered/asm/mixin/Mixin;")) return null;
                return new AnnotationVisitor(Opcodes.ASM9) {
                    @Override public AnnotationVisitor visitArray(String name) {
                        if (!name.equals("targets") && !name.equals("value")) return null;
                        return new AnnotationVisitor(Opcodes.ASM9) {
                            @Override public void visit(String unused, Object value) {
                                targets.add(value instanceof Type type ? type.getInternalName()
                                        : ((String) value).replace('.', '/'));
                            }
                        };
                    }
                };
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return targets;
    }
}
