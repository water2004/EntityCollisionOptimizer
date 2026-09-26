package org.edtp.entitycollisionoptimizer.collision;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.MappingResolver;

/** Names used by reflection and ASM must also work in intermediary production jars. */
public final class RuntimeMappings {
    private static final MappingResolver MAPPINGS = FabricLoader.getInstance().getMappingResolver();

    public static String type(String intermediary) {
        return MAPPINGS.mapClassName("intermediary", intermediary).replace('.', '/');
    }

    public static String field(String owner, String name, String descriptor) {
        return MAPPINGS.mapFieldName("intermediary", owner, name, descriptor);
    }

    public static String method(String owner, String name, String descriptor) {
        return MAPPINGS.mapMethodName("intermediary", owner, name, descriptor);
    }

    private RuntimeMappings() {}
}
