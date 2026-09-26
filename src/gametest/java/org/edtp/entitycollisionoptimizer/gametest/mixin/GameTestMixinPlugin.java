package org.edtp.entitycollisionoptimizer.gametest.mixin;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/** Diagnostic wrappers must not alter ordinary, non-diagnostic benchmark profiles. */
public final class GameTestMixinPlugin implements IMixinConfigPlugin {
    @Override public void onLoad(String mixinPackage) {}
    @Override public String getRefMapperConfig() { return null; }
    @Override public void acceptTargets(Set<String> mine, Set<String> other) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String target, ClassNode node, String mixin, IMixinInfo info) {}

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.endsWith("ScanMixin")) {
            return "true".equalsIgnoreCase(System.getenv("ECO_SCAN_DIAGNOSTICS"));
        }
        return true;
    }

    @Override public void postApply(String target, ClassNode node, String mixin, IMixinInfo info) {
        if (mixin.endsWith("TestBodyFieldConsumersMixin")) {
            org.edtp.entitycollisionoptimizer.collision.bytecode.BodyFieldAccess.rewrite(node);
        }
    }
}
