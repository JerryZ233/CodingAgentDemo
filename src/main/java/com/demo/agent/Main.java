package com.demo.agent;

import java.util.Scanner;

/**
 * Entry point for the AI Coding Agent demo.
 * 
 * This class demonstrates how to use the coding agent to accomplish
 * tasks like "write a fibonacci program" or "create a hello world file".
 * 
 * Supports both single-task execution (via command line args) and
 * interactive REPL mode for multi-turn conversations.
 */
public class Main {

    private static volatile boolean running = true;

    public static void main(String[] args) {
        // Create a new coding agent instance
        CodingAgent agent = new CodingAgent();
        
        // Single-task mode: if args provided, execute and exit
        if (args.length > 0) {
            String task = args[0];
            System.out.println("=== AI Coding Agent Demo ===");
            System.out.println("Task: " + task);
            System.out.println();
            agent.execute(task);
            return;
        }
        
        // Interactive REPL mode
        setupShutdownHook();
        runRepl(agent);
    }
    
    /**
     * Sets up a shutdown hook for graceful exit on Ctrl+C.
     */
    private static void setupShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (running) {
                System.out.println("\n\nGoodbye!");
            }
        }));
    }
    
    /**
     * Runs the interactive REPL loop.
     */
    private static void runRepl(CodingAgent agent) {
        Scanner scanner = new Scanner(System.in);
        
        printWelcomeMessage();
        
        while (running) {
            System.out.print(" > ");
            String input = scanner.nextLine().trim();
            
            if (input.isEmpty()) {
                continue;
            }
            
            // Handle special commands (case-insensitive)
            String inputLower = input.toLowerCase();
            
            if (inputLower.equals("exit") || inputLower.equals("quit")) {
                System.out.println("Goodbye!");
                running = false;
                break;
            }
            
            if (inputLower.equals("help")) {
                printHelpMessage();
                continue;
            }
            
            if (inputLower.equals("clear")) {
                agent.getConversation().clear();
                System.out.println("Conversation history cleared.");
                continue;
            }
            
            if (inputLower.startsWith("save ")) {
                String filePath = input.substring(5).trim();
                if (filePath.isEmpty()) {
                    System.out.println("Usage: save <file>");
                } else {
                    agent.getConversation().saveToFile(filePath);
                    System.out.println("Conversation saved to: " + filePath);
                }
                continue;
            }
            
            if (inputLower.startsWith("load ")) {
                String filePath = input.substring(5).trim();
                if (filePath.isEmpty()) {
                    System.out.println("Usage: load <file>");
                } else {
                    agent.getConversation().loadFromFile(filePath);
                    System.out.println("Conversation loaded from: " + filePath);
                }
                continue;
            }
            
            // Normal input: pass to agent
            agent.executeWithHistory(input);
        }
        
        scanner.close();
    }
    
    /**
     * Prints the welcome message with instructions.
     */
    private static void printWelcomeMessage() {
        System.out.println("=== AI Coding Agent Demo ===");
        System.out.println("Interactive REPL Mode");
        System.out.println();
        System.out.println("Type your coding tasks and press Enter.");
        System.out.println("Type 'help' for available commands.");
        System.out.println("Press Ctrl+C to exit at any time.");
        System.out.println();
    }
    
    /**
     * Prints the help message with available commands.
     */
    private static void printHelpMessage() {
        System.out.println();
        System.out.println("Available Commands:");
        System.out.println("  exit, quit  - Exit the REPL");
        System.out.println("  save <file> - Save conversation to JSON file");
        System.out.println("  load <file> - Load conversation from JSON file");
        System.out.println("  clear       - Clear conversation history");
        System.out.println("  help        - Show this help message");
        System.out.println();
        System.out.println("Or type any coding task to execute.");
        System.out.println();
    }
}
