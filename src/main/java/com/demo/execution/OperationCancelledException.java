package com.demo.execution;

public class OperationCancelledException extends RuntimeException {
    public OperationCancelledException() { super("Operation cancelled"); }
}
