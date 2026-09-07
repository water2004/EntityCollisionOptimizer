package org.edtp.entitycollisionoptimizer.gametest;

import org.edtp.entitycollisionoptimizer.EntityCollisionOptimizer;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.List;

/** Load every declared consumer without initialization so the final Mixin output can be audited. */
final class BodyFieldConsumerCoverage {
    static void verify() {
        ClassLoader loader = BodyFieldConsumerCoverage.class.getClassLoader();
        List<String> targets = new ArrayList<>();
        for (String marker : List.of("BodyFieldConsumersMixin", "InterfaceBodyFieldConsumersMixin")) {
            String resource = "org/edtp/entitycollisionoptimizer/mixin/" + marker + ".class";
            try (var input = loader.getResourceAsStream(resource)) {
                if (input == null) throw new AssertionError("Missing consumer inventory " + resource);
                new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                        if (!descriptor.equals("Lorg/spongepowered/asm/mixin/Mixin;")) return null;
                        return new AnnotationVisitor(Opcodes.ASM9) {
                            @Override public AnnotationVisitor visitArray(String name) {
                                if (!name.equals("targets")) return null;
                                return new AnnotationVisitor(Opcodes.ASM9) {
                                    @Override public void visit(String unused, Object value) { targets.add((String) value); }
                                };
                            }
                        };
                    }
                }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            } catch (java.io.IOException failure) { throw new AssertionError(failure); }
        }
        int loaded = 0, absent = 0;
        for (String target : targets) {
            try { Class.forName(target, false, loader); loaded++; }
            catch (ClassNotFoundException failure) {
                if (!target.startsWith("carpet.")
                        || net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("carpet")) {
                    throw new AssertionError("Declared field consumer missing: " + target, failure);
                }
                absent++;
            }
        }
        if (loaded == 0) throw new AssertionError("Empty field consumer inventory");
        EntityCollisionOptimizer.LOGGER.info("ECO_BODY_FIELD_CONSUMERS loaded={} optional_absent={} result=passed", loaded, absent);
    }
}
