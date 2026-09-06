package com.wiyuka.acceleratedrecoiling.ffm;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

public final class FFM {
    private FFM() {
    }

    public static MemorySegment allocateArray(Arena arena, double[] array) {
        return arena.allocateFrom(ValueLayout.JAVA_DOUBLE, array);
    }

    public static MemorySegment allocateArray(Arena arena, int[] array) {
        return arena.allocateFrom(ValueLayout.JAVA_INT, array);
    }
}
