package com.demo.cli;

public sealed interface CliCommand {
    record Task(String text) implements CliCommand {}
    record NewSession(String directory) implements CliCommand {}
    record Resume(String id) implements CliCommand {}
    record RetrySave(String id) implements CliCommand {}
    record Sessions() implements CliCommand {}
    record History() implements CliCommand {}
    record MemoryHelp() implements CliCommand {}
    record MemorySearch(String query) implements CliCommand {}
    record MemoryRead(int index) implements CliCommand {}
    record Status() implements CliCommand {}
    record Revoke() implements CliCommand {}
    record Cancel() implements CliCommand {}
    record Help() implements CliCommand {}
    record Exit() implements CliCommand {}
    record Empty() implements CliCommand {}
}
