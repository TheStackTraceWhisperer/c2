package com.example.asset.table;

public enum ItemModelSlot {
    Face(0),
    Head(1),
    Body(2),
    Hands(3),
    Legs(4),
    Feet(5),
    Main(6),
    Sub(7),
    Range(8);

    public final int index;

    ItemModelSlot(int index) {
        this.index = index;
    }
}
