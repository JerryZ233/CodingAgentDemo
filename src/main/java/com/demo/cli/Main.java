package com.demo.cli;

public final class Main {
    private Main() {}
    public static void main(String[] args) {
        System.exit(new CliApplication().run(args));
    }
}
