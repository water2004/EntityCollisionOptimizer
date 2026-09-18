package org.edtp.entitycollisionoptimizer.mixin;

import org.edtp.entitycollisionoptimizer.collision.bytecode.BodyFieldAccess;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/** Runs after Mixin's accessor/injector phases, so merged field consumers share the same boundary. */
public final class BodyMixinPlugin implements IMixinConfigPlugin {
    @Override public void postApply(String target, ClassNode node, String mixin, IMixinInfo info) {
        if (mixin.equals("org.edtp.entitycollisionoptimizer.mixin.EntityBodyMixin")
                || mixin.endsWith("BodyFieldConsumersMixin")) {
            BodyFieldAccess.rewrite(node);
        }
    }
}
