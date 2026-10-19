package com.demo.cli;

/** A deliberate Chinese validation message, safe to show without backend diagnostics. */
final class CliInputException extends IllegalArgumentException {
    CliInputException(String message) { super(message); }
}
