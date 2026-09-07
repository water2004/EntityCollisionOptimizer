package org.edtp.entitycollisionoptimizer.gametest.mixin;

import org.spongepowered.asm.mixin.Mixin;

/** Keep independent test code's ordinary field expressions; apply the same public-field boundary. */
@Mixin(targets = {
        "org.edtp.entitycollisionoptimizer.gametest.CollisionImpulseParity",
        "org.edtp.entitycollisionoptimizer.gametest.CollisionDispatchParity",
        "org.edtp.entitycollisionoptimizer.gametest.AuthoritativeVelocityParity",
        "org.edtp.entitycollisionoptimizer.gametest.SyncStateParity",
        "org.edtp.entitycollisionoptimizer.gametest.PushStateParity",
        "org.edtp.entitycollisionoptimizer.natives.CollisionStateTableChecks",
        "org.edtp.entitycollisionoptimizer.natives.MovementPublicationChecks",
        "org.edtp.entitycollisionoptimizer.gametest.InteractionScene$State",
        "org.edtp.entitycollisionoptimizer.gametest.MixedEntityInteractionParity",
        "org.edtp.entitycollisionoptimizer.gametest.NativePushRunParity",
        "org.edtp.entitycollisionoptimizer.gametest.NativeImpulseParity",
        "org.edtp.entitycollisionoptimizer.gametest.PersistentBodyParity",
        "org.edtp.entitycollisionoptimizer.gametest.PushBatchParity",
        "org.edtp.entitycollisionoptimizer.gametest.PushRunBoundaryParity"
})
public abstract class TestBodyFieldConsumersMixin {}
