package com.demo.agent;

/**
 * Entry point for the AI Coding Agent demo.
 * 
 * This class demonstrates how to use the coding agent to accomplish
 * tasks like "write a fibonacci program" or "create a hello world file".
 */
public class Main {

    public static void main(String[] args) {
        // Create a new coding agent instance
        CodingAgent agent = new CodingAgent();
        
        // Example: Accept a task from command line or use default
        String task = args.length > 0 ? args[0] : "write a fibonacci program";
        
        System.out.println("=== AI Coding Agent Demo ===");
        System.out.println("Task: " + task);
        System.out.println();
        
        // Execute the task using the agent
        agent.execute(task);
    }
}
