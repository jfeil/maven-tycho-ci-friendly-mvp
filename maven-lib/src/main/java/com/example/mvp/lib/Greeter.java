package com.example.mvp.lib;

public final class Greeter {

    private Greeter() {
    }

    public static String greet(String name) {
        return "Hello, " + name + "!";
    }
}
