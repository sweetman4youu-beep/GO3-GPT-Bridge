package com.niaman.go3bridge;

import android.app.Activity;
import java.util.function.Consumer;

public class Go3Ble {
    private final Consumer<String> state;
    private final Consumer<String> log;

    Go3Ble(Activity activity, Consumer<String> log, Consumer<String> state) {
        this.log = log;
        this.state = state;
    }

    void start() {
        state.accept("GO3: בדיקת BLE תופעל בגרסה הבאה");
        log.accept("APK test build: GO3 BLE layer is temporarily disabled");
    }
}
