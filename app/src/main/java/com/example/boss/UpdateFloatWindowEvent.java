package com.example.boss;

public class UpdateFloatWindowEvent {
    private String name;
    private String spwan;
    public RowData data;
    public int position;
    public int type;
    public UpdateFloatWindowEvent(int type, RowData data) {
//        this.name = name;
//        this.spwan = spwan;
        this.type = type;
        this.data = data;
    }

    public UpdateFloatWindowEvent(int type, int position) {
//        this.name = name;
//        this.spwan = spwan;
        this.type = type;
        this.position = position;
    }
}
