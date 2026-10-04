/*
 * Static mixin target check.
 *
 * Usage (ASM 9.x on the classpath, same requirement as BodyFieldAudit.java):
 *   javac -cp asm-9.10.1.jar;asm-tree-9.10.1.jar -d <out-dir> tools/MixinTargetCheck.java
 *   java -cp asm-9.10.1.jar;asm-tree-9.10.1.jar;<out-dir> MixinTargetCheck `
 *       <mapped minecraft jar> build/classes/java/main
 *
 * Exits non-zero when any target cannot be resolved.
 */
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.io.InputStream;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Statically resolves every mixin target of the compiled mod against a mapped
 * Minecraft jar, mirroring Mixin's own target resolution closely enough to catch
 * "target method not found" failures before launch.
 */
public final class MixinTargetCheck {
    static final Map<String, ClassNode> MC = new HashMap<>();
    static int problems = 0;
    static int checks = 0;

    public static void main(String[] args) throws Exception {
        String jar = args[0];
        String classDir = args[1];
        try (ZipFile zf = new ZipFile(jar)) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (!e.getName().endsWith(".class")) continue;
                try (InputStream in = zf.getInputStream(e)) {
                    ClassNode cn = new ClassNode();
                    new ClassReader(in).accept(cn, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                    MC.put(cn.name, cn);
                }
            }
        }
        List<ClassNode> mixins = new ArrayList<>();
        try (var walk = Files.walk(Path.of(classDir))) {
            for (Path p : walk.filter(f -> f.toString().endsWith(".class")).sorted().toList()) {
                ClassNode cn = new ClassNode();
                new ClassReader(Files.readAllBytes(p)).accept(cn, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                mixins.add(cn);
            }
        }
        System.out.println("mc classes=" + MC.size() + " mod classes=" + mixins.size());
        for (ClassNode mixin : mixins) checkMixin(mixin);
        System.out.println("\nchecks=" + checks + " problems=" + problems);
        if (problems > 0) System.exit(1);
    }

    static void checkMixin(ClassNode mixin) {
        AnnotationNode mixinAnn = findAnnotation(mixin.visibleAnnotations, mixin.invisibleAnnotations, "Lorg/spongepowered/asm/mixin/Mixin;");
        if (mixinAnn == null) return;
        boolean pseudo = findAnnotation(mixin.visibleAnnotations, mixin.invisibleAnnotations, "Lorg/spongepowered/asm/mixin/Pseudo;") != null;
        List<String> targets = new ArrayList<>();
        if (mixinAnn.values != null) {
            for (int i = 0; i + 1 < mixinAnn.values.size(); i += 2) {
                String key = (String) mixinAnn.values.get(i);
                Object val = mixinAnn.values.get(i + 1);
                if (key.equals("value") && val instanceof List<?> list) {
                    for (Object o : list) if (o instanceof Type t) targets.add(t.getInternalName());
                } else if (key.equals("targets") && val instanceof List<?> list) {
                    for (Object o : list) targets.add(((String) o).replace('.', '/'));
                } else if (key.equals("value") && val instanceof Type t) {
                    targets.add(t.getInternalName());
                }
            }
        }
        if (targets.isEmpty()) return;
        for (String target : targets) {
            ClassNode tcn = MC.get(target);
            if (tcn == null) {
                if (!pseudo) report(mixin.name, target, "target class MISSING from Minecraft jar", true);
                continue;
            }
            for (MethodNode m : mixin.methods) {
                checkInjectors(mixin, target, tcn, m);
                checkAccessor(mixin, target, tcn, m);
                checkShadowMethod(mixin, target, tcn, m);
            }
            for (FieldNode f : mixin.fields) checkShadowField(mixin, target, tcn, f);
        }
    }

    static void checkInjectors(ClassNode mixin, String target, ClassNode tcn, MethodNode m) {
        var anns = new ArrayList<AnnotationNode>();
        if (m.visibleAnnotations != null) anns.addAll(m.visibleAnnotations);
        if (m.invisibleAnnotations != null) anns.addAll(m.invisibleAnnotations);
        for (AnnotationNode a : anns) {
            if (a.desc == null) continue;
            boolean injector = a.desc.endsWith("/Inject;") || a.desc.endsWith("/Redirect;")
                    || a.desc.endsWith("/WrapOperation;") || a.desc.endsWith("/WrapMethod;")
                    || a.desc.endsWith("/ModifyArg;") || a.desc.endsWith("/ModifyArgs;")
                    || a.desc.endsWith("/ModifyVariable;") || a.desc.endsWith("/ModifyConstant;")
                    || a.desc.endsWith("/ModifyExpressionValue;") || a.desc.endsWith("/ModifyReturnValue;")
                    || a.desc.endsWith("/Accessor;") || a.desc.endsWith("/Invoker;");
            if (!injector) continue;
            Object methodValue = annotationValue(a, "method");
            if (methodValue == null) continue;
            List<String> specs = new ArrayList<>();
            if (methodValue instanceof String s) specs.add(s);
            else if (methodValue instanceof List<?> l) for (Object o : l) specs.add((String) o);
            String shortName = a.desc.substring(a.desc.lastIndexOf('/') + 1, a.desc.length() - 1);
            for (String spec : specs) {
                checks++;
                int paren = spec.indexOf('(');
                String name = paren < 0 ? spec : spec.substring(0, paren);
                String desc = paren < 0 ? null : spec.substring(paren);
                List<String> matches = resolveMethods(target, tcn, name, desc);
                if (matches.isEmpty()) {
                    report(mixin.name, target, shortName + " -> " + spec + " NOT FOUND", true);
                } else if (desc == null) {
                    // Overrides of one signature in several hierarchy levels are a single
                    // logical method; only genuinely different descriptors are ambiguous.
                    Set<String> distinct = new TreeSet<>();
                    for (String match : matches) distinct.add(match.substring(match.indexOf('(')));
                    if (distinct.size() > 1) {
                        report(mixin.name, target, shortName + " -> " + spec + " AMBIGUOUS overloads: " + distinct, true);
                    }
                }
            }
        }
    }

    static void checkAccessor(ClassNode mixin, String target, ClassNode tcn, MethodNode m) {
        AnnotationNode a = findAnnotation(m.visibleAnnotations, m.invisibleAnnotations, "Lorg/spongepowered/asm/mixin/gen/Accessor;");
        if (a == null) return;
        checks++;
        Object v = annotationValue(a, "value");
        String fieldName = v instanceof String s && !s.isEmpty() ? s : stripPrefix(m.name);
        FieldNode fn = resolveField(target, tcn, fieldName);
        if (fn == null) {
            report(mixin.name, target, "@Accessor field '" + fieldName + "' NOT FOUND", true);
        } else if (!descriptorCompatible(fn.desc, Type.getMethodType(m.desc).getReturnType().getDescriptor())) {
            report(mixin.name, target, "@Accessor field '" + fieldName + "' desc " + fn.desc
                    + " != accessor return " + Type.getMethodType(m.desc).getReturnType().getDescriptor(), true);
        }
    }

    static void checkShadowMethod(ClassNode mixin, String target, ClassNode tcn, MethodNode m) {
        if (findAnnotation(m.visibleAnnotations, m.invisibleAnnotations, "Lorg/spongepowered/asm/mixin/Shadow;") == null) return;
        checks++;
        if (resolveMethods(target, tcn, m.name, m.desc).isEmpty()) {
            report(mixin.name, target, "@Shadow method " + m.name + m.desc + " NOT FOUND", true);
        }
    }

    static void checkShadowField(ClassNode mixin, String target, ClassNode tcn, FieldNode f) {
        if (findAnnotation(f.visibleAnnotations, f.invisibleAnnotations, "Lorg/spongepowered/asm/mixin/Shadow;") == null) return;
        checks++;
        FieldNode fn = resolveField(target, tcn, f.name);
        if (fn == null) {
            report(mixin.name, target, "@Shadow field " + f.name + " NOT FOUND", true);
        } else if (!descriptorCompatible(fn.desc, f.desc)) {
            report(mixin.name, target, "@Shadow field " + f.name + " desc " + f.desc + " != target " + fn.desc, true);
        }
    }

    static boolean descriptorCompatible(String target, String mixin) {
        if (target.equals(mixin)) return true;
        // Generic erasure differences (e.g. T -> Object) are equivalent at the JVM level.
        return target.replace("Ljava/lang/Object;", "T").equals(mixin.replace("Ljava/lang/Object;", "T"));
    }

    static String stripPrefix(String name) {
        int i = name.indexOf('$');
        return i < 0 ? name : name.substring(i + 1);
    }

    static List<String> resolveMethods(String target, ClassNode tcn, String name, String desc) {
        List<String> out = new ArrayList<>();
        collect(target, tcn, name, desc, out, new HashSet<>());
        return out;
    }

    static void collect(String target, ClassNode cn, String name, String desc, List<String> out, Set<String> seen) {
        if (cn == null || !seen.add(cn.name)) return;
        for (MethodNode m : cn.methods) {
            if (!m.name.equals(name)) continue;
            if (desc != null && !descriptorCompatible(m.desc, desc)) continue;
            out.add(cn.name + "." + m.name + m.desc);
        }
        // Constructors and static initialisers are never inherited.
        if (name.equals("<init>") || name.equals("<clinit>")) return;
        if (cn.superName != null) collect(target, MC.get(cn.superName), name, desc, out, seen);
        if (cn.interfaces != null) for (String itf : cn.interfaces) collect(target, MC.get(itf), name, desc, out, seen);
    }

    static FieldNode resolveField(String target, ClassNode cn, String name) {
        Set<String> seen = new HashSet<>();
        while (cn != null && seen.add(cn.name)) {
            for (FieldNode f : cn.fields) if (f.name.equals(name)) return f;
            cn = cn.superName == null ? null : MC.get(cn.superName);
        }
        return null;
    }

    static AnnotationNode findAnnotation(List<AnnotationNode> visible, List<AnnotationNode> invisible, String desc) {
        if (visible != null) for (AnnotationNode a : visible) if (desc.equals(a.desc)) return a;
        if (invisible != null) for (AnnotationNode a : invisible) if (desc.equals(a.desc)) return a;
        return null;
    }

    static Object annotationValue(AnnotationNode a, String key) {
        if (a.values == null) return null;
        for (int i = 0; i + 1 < a.values.size(); i += 2) {
            if (key.equals(a.values.get(i))) return a.values.get(i + 1);
        }
        return null;
    }

    static void report(String mixin, String target, String message, boolean problem) {
        if (problem) problems++;
        System.out.println((problem ? "[FAIL] " : "[ok]   ") + mixin + " => " + target + " : " + message);
    }
}
