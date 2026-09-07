# Contract: In-World 2D Spatial Radar UI

**Module**: `client-desktop`  
**Package**: `com.example.client.desktop.ui`  
**Component**: `WorldRadarPanel`  

## 1. Overview

Specifies the visual presentation, coordinate transformations, graphical symbols, color palette, telemetry overlay, and user interaction contract for the real-time 2D tactical radar viewport rendered via Dear ImGui `ImDrawList`.

---

## 2. Coordinate Transformation Contract

- **Projection Mode**: Top-down 2D orthographic projection centered on the local player.
- **World Coordinate Convention (FFXI)**:
  - `+X` = East
  - `-X` = West
  - `+Z` = North
  - `-Z` = South
  - `+Y` = Altitude (rendered as elevation indicator $\Delta Y$ when non-zero)
- **Screen Coordinate Convention (ImGui)**:
  - Canvas Center: $(C_x, C_y)$
  - Scale Factor: $S = R_{pixels} / R_{meters}$ (where $R_{pixels}$ is canvas radius in pixels, $R_{meters}$ is current zoom radius).
  - Target Screen X: $P_x = C_x + (X_{target} - X_{local}) \times S$
  - Target Screen Y: $P_y = C_y - (Z_{target} - Z_{local}) \times S$ (inverted so $+Z$ North points up)

---

## 3. Visual Symbol & Style Contract

| Visual Element | Symbol / Geometry | Color (RGBA) | Description |
|---|---|---|---|
| **Canvas Background** | Circular or rounded rectangle | `0x15, 0x18, 0x22, 0xF0` | Deep dark blue-gray backdrop. |
| **Canvas Border** | Circle / Outline | `0x45, 0x50, 0x65, 0xFF` | Viewport outer boundary ring. |
| **Coordinate Grid** | Thin dashed / solid lines | `0x2A, 0x33, 0x45, 0x60` | 10m grid lines across radar area. |
| **Cardinal Axis (N/S/E/W)** | Crosshair lines + Labels | `0x55, 0x65, 0x80, 0x80` | Centered crosshair with 'N', 'S', 'E', 'W' letters. |
| **Range Rings** | Concentric circles | `0x38, 0x44, 0x5E, 0x70` | Rings at 25m, 50m, 100m, 200m with distance text. |
| **AoI Boundary** | Faint dashed cyan ring | `0x00, 0xAA, 0xCC, 0x50` | 50m radius Area of Interest limit. |
| **Local Player** | Bright green filled circle + chevron | `0x22, 0xC5, 0x5E, 0xFF` | 6px radius circle at center $(C_x, C_y)$. |
| **Local Player Yaw** | 12px directional arrow/line | `0x86, 0xEF, 0xAC, 0xFF` | Line pointing from center along player's yaw vector. |
| **Remote Player** | Cyan filled circle | `0x38, 0xBD, 0xF8, 0xFF` | 5px radius marker at relative position. |
| **Remote Player Yaw** | 8px directional tick | `0x7D, 0xD3, 0xFC, 0xFF` | Heading indicator line for remote character. |
| **Remote Player Nameplate** | Small text above marker | `0xF8, 0xFA, 0xFC, 0xE6` | Text: `Name [WAR Lv.1]` positioned above entity blip. |

---

## 4. Telemetry Overlay Layout

Positioned in the corner of the radar viewport or header panel:
```text
┌────────────────────────────────────────────────────────┐
│ [Zone] West Ronfaure (ID: 100)  [World] 1  [FPS] 60.0  │
│ [Pos] X: 66.50  Y: -1.00  Z: -40.00  Yaw: 180.0°       │
│ [Radar] Radius: 50m | Players in AoI: 3 | Ping: 42ms   │
└────────────────────────────────────────────────────────┘
```

---

## 5. Controls & Interaction Contract

- **Zoom Buttons**: `[ - ]` (Zoom Out: 25m -> 50m -> 100m -> 200m) and `[ + ]` (Zoom In).
- **Grid Toggle**: Checkbox `Show Grid`.
- **AoI Ring Toggle**: Checkbox `Show AoI (50m)`.
- **Names Toggle**: Checkbox `Show Nameplates`.
- **Mouse Hover on Marker**: Tooltip displaying full entity details:
  `Character: <name>`, `ID: <id>`, `Job: <job> Lv.<level>`, `Distance: <distance>m`, `Coords: (X, Y, Z)`.
