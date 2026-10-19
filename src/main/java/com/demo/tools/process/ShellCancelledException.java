package com.demo.tools.process;
/** Cancellation with truthful process admission information for the checkpoint. */
public final class ShellCancelledException extends com.demo.execution.OperationCancelledException {
 private final boolean started;
 private final String output;
 public ShellCancelledException(boolean started){this(started,null);}
 public ShellCancelledException(boolean started,String output){this.started=started;this.output=output;}
 public boolean started(){return started;}
 public String output(){return output;}
}
