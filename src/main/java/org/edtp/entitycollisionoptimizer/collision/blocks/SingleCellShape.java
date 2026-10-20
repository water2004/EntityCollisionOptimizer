package org.edtp.entitycollisionoptimizer.collision.blocks;

/** Recognizes known one-cell implementations without flattening arbitrary voxel grids. */
public interface SingleCellShape {
    boolean eco$isSingleCell();
}
