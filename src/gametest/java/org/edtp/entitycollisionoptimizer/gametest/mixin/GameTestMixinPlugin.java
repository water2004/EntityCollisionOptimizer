package org.edtp.entitycollisionoptimizer.gametest.mixin;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/** Diagnostic wrappers must not alter ordinary, non-diagnostic benchmark profiles. */
public final class GameTestMixinPlugin implements IMixinConfigPlugin {
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
