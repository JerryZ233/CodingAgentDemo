package com.demo.tools.process;

/** A terminal tool error: retrying via the model cannot supply authorization or more budget. */
public final class ShellExecutionException extends RuntimeException {
    private final boolean started;
    public ShellExecutionException(String code) { this(code,false); }
    public ShellExecutionException(String code, boolean started) { super(code); this.started=started; }
    public boolean started() { return started; }
}
