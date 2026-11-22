-- ============================================================================
-- V5: CORRECT RETAIL FFXI ZONE MAIN-AREA DAT PATHS (MATCHING VTABLE/FTABLE)
-- ============================================================================

UPDATE game.zones SET dat_path = 'ROM/0/120.DAT' WHERE id = 100; -- West Ronfaure (was ROM/1/30.DAT = Ship bound for Mhaura)
UPDATE game.zones SET dat_path = 'ROM/0/115.DAT' WHERE id = 102; -- La Theine Plateau (was ROM/1/2.DAT = Sauromugue Champaign)
UPDATE game.zones SET dat_path = 'ROM/0/113.DAT' WHERE id = 232; -- Port San d'Oria (was ROM/1/33.DAT = Chateau d'Oraguille)
UPDATE game.zones SET dat_path = 'ROM/1/33.DAT'  WHERE id = 233; -- Chateau d'Oraguille (was ROM/1/38.DAT = Heavens Tower)
UPDATE game.zones SET dat_path = 'ROM/1/43.DAT'  WHERE id = 248; -- Selbina (was ROM/1/44.DAT = Mhaura)
UPDATE game.zones SET dat_path = 'ROM/1/44.DAT'  WHERE id = 249; -- Mhaura (was ROM/1/45.DAT)
