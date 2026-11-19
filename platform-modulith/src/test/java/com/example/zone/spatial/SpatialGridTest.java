package com.example.zone.spatial;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SpatialGridTest {

  @Test
  @DisplayName("Empty cells are pruned on cell-change and removeEntity while active cells retain remaining entities")
  void testEmptyCellPruningOnMoveAndRemove() {
    SpatialGrid grid = new SpatialGrid();

    // Place two entities in cell (0, 0)
    grid.updatePosition(1L, 10.0f, 10.0f);
    grid.updatePosition(2L, 15.0f, 15.0f);
    assertThat(grid.getCellCount()).isEqualTo(1);
    assertThat(grid.getNearbyEntities(10.0f, 10.0f)).containsExactlyInAnyOrder(1L, 2L);

    // Move entity 1 far away to cell (4, 4) -> old cell (0, 0) still has entity 2, so 2 cells exist
    grid.updatePosition(1L, 210.0f, 210.0f);
    assertThat(grid.getCellCount()).isEqualTo(2);
    assertThat(grid.getNearbyEntities(10.0f, 10.0f)).containsExactly(2L);
    assertThat(grid.getNearbyEntities(210.0f, 210.0f)).containsExactly(1L);

    // Move entity 2 out of cell (0, 0) to cell (4, 4) -> empty cell (0, 0) MUST be pruned!
    grid.updatePosition(2L, 215.0f, 215.0f);
    assertThat(grid.getCellCount()).isEqualTo(1);
    assertThat(grid.getNearbyEntities(10.0f, 10.0f)).isEmpty();
    assertThat(grid.getNearbyEntities(210.0f, 210.0f)).containsExactlyInAnyOrder(1L, 2L);

    // Remove entity 1 -> cell (4, 4) still has entity 2
    grid.removeEntity(1L);
    assertThat(grid.getCellCount()).isEqualTo(1);
    assertThat(grid.getNearbyEntities(210.0f, 210.0f)).containsExactly(2L);

    // Remove entity 2 -> cell (4, 4) is empty and MUST be pruned!
    grid.removeEntity(2L);
    assertThat(grid.getCellCount()).isEqualTo(0);
    assertThat(grid.getNearbyEntities(210.0f, 210.0f)).isEmpty();
  }
}
