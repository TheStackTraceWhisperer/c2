package com.ffxi.tables;

import com.ffxi.io.ByteReader;
import com.ffxi.parser.StringTableParser;
import java.util.List;

public final class ZoneNameTable {
    public static final String RESOURCE_PATH = "ROM/165/84.DAT";
    public static final byte BIT_MASK = (byte) 0xFF;

    private final List<StringTableParser.StringTableBlock> blocks;

    private ZoneNameTable(List<StringTableParser.StringTableBlock> blocks) {
        this.blocks = List.copyOf(blocks);
    }

    public static ZoneNameTable load(byte[] data) {
        ByteReader reader = new ByteReader(data, RESOURCE_PATH);
        List<StringTableParser.StringTableBlock> blocks = StringTableParser.read(reader, BIT_MASK);
        return new ZoneNameTable(blocks);
    }

    public String getName(ZoneConfig zone) {
        return getName(zone.zoneId());
    }

    public String getName(int zoneId) {
        if (zoneId < 0 || zoneId >= blocks.size()) {
            return "";
        }
        return blocks.get(zoneId).getFirst();
    }

    public List<String> getAllNames() {
        return blocks.stream().map(StringTableParser.StringTableBlock::getFirst).toList();
    }

    public int size() {
        return blocks.size();
    }
}
