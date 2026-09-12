
package com.x4yi.automata.navigation;

public interface INavigationWorldView {

    byte getCellType(int x, int y, int z);

    default boolean isPassable(int x, int y, int z) {
        byte cell = getCellType(x, y, z);
        return cell == BlockCellType.AIR || cell == BlockCellType.WATER;
    }

    default boolean isSolidSupport(int x, int y, int z) {
        return getCellType(x, y, z) == BlockCellType.SOLID;
    }

    default boolean isHazard(int x, int y, int z) {
        return getCellType(x, y, z) == BlockCellType.HAZARD;
    }

    default boolean isWater(int x, int y, int z) {
        return getCellType(x, y, z) == BlockCellType.WATER;
    }

    default boolean isTallObstacle(int x, int y, int z) {
        return getCellType(x, y, z) == BlockCellType.TALL_OBSTACLE;
    }
}

