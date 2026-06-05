package com.demo.agent;

import com.demo.model.ToolResult;

/**
 * Console-backed observer used by the CLI entry points.
 */
public class ConsoleAgentObserver implements AgentObserver {

    @Override
    public void onLlmClientSelected(String message) {
        System.out.println(message);
    }

    @Override
    public void onAgentStarted(String message) {
        System.out.println(message);
    }

    @Override
    public void onAgentCompleted(String message) {
        System.out.println(message);
    }

    @Override
    public void onIterationStarted(int iteration) {
        System.out.println("\n=== Iteration " + iteration + " ===");
    }

    @Override
    public void onLlmError(String message) {
        System.out.println(message);
    }

    @Override
    public void onFinalResponse(String response) {
        System.out.println("Final response: " + response);
    }

    @Override
    public void onToolResult(String toolName, ToolResult result) {
        System.out.println("Tool '" + toolName + "' result: " + result.getOutput());
    }

    @Override
    public void onMaxIterations(String message) {
        System.out.println(message);
    }
}
