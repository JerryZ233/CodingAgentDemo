package com.demo.tools.process;

import com.demo.execution.CancellationToken;
import java.util.*;
import java.util.function.Consumer;

/** One pending approval; grants belong solely to this user turn, never to a session. */
public final class ApprovalCoordinator implements AutoCloseable {
    public enum Decision { ONCE, THIS_TURN, DENY }
    public record PendingApproval(long generation, ExecutionPlan plan) { }
    public record Event(String kind, PendingApproval approval) { }
    private final boolean available;
    private static final java.util.concurrent.atomic.AtomicLong GENERATIONS=new java.util.concurrent.atomic.AtomicLong();
    private final Consumer<Event> events;
    private final Set<String> grants = new HashSet<>(), denied = new HashSet<>();
    private PendingApproval pending;
    private Decision decision;
    private String launchKey;
    private CancellationToken launchToken;
    private int requests, executions, artifactBytes;
    private boolean closed;
    public ApprovalCoordinator(boolean available, Consumer<Event> events) {
        this.available = available; this.events = Objects.requireNonNull(events);
    }
    public synchronized Optional<PendingApproval> pending() { return Optional.ofNullable(pending); }
    public synchronized boolean submit(long generation, Decision decision) {
        if (closed || pending == null || pending.generation() != generation || this.decision != null) return false;
        this.decision = Objects.requireNonNull(decision); notifyAll(); return true;
    }
    public synchronized void revoke() { grants.clear(); }
    public synchronized void invalidate(ExecutionPlan plan) {
        launchKey=null;launchToken=null;
        grants.remove(plan.authorizationKey()); denied.remove(plan.authorizationKey()); events.accept(new Event("invalidated", null));
    }
    public boolean authorize(ExecutionPlan plan, CancellationToken token) {
        String key=plan.authorizationKey();
        try (var registration = token.onCancellation(this::wake)) {
            synchronized (this) {
                launchKey=null;launchToken=null;
                token.throwIfCancellationRequested();
                if (closed) throw new ShellExecutionException("APPROVAL_CHANNEL_UNAVAILABLE");
                if (denied.contains(key)) return false;
                if (grants.contains(key)) { launchKey=key;launchToken=token;return true; }
                if (!available) throw new ShellExecutionException("APPROVAL_CHANNEL_UNAVAILABLE");
                if (pending != null) throw new ShellExecutionException("APPROVAL_ALREADY_PENDING");
                if (++requests > 20) throw new ShellExecutionException("APPROVAL_LIMIT_EXCEEDED");
                pending = new PendingApproval(GENERATIONS.incrementAndGet(), plan); decision = null;
                try {
                    events.accept(new Event("requested", pending));
                    while (decision == null && !closed) {
                        token.throwIfCancellationRequested();
                        try { wait(); } catch (InterruptedException e) {
                            Thread.currentThread().interrupt(); throw new com.demo.execution.OperationCancelledException();
                        }
                    }
                    token.throwIfCancellationRequested();
                    if (closed) throw new ShellExecutionException("APPROVAL_CHANNEL_UNAVAILABLE");
                    if (decision == Decision.THIS_TURN) grants.add(key);
                    if (decision == Decision.DENY) denied.add(key);
                    if(decision!=Decision.DENY){launchKey=key;launchToken=token;}
                    events.accept(new Event("decided", pending));
                    return decision != Decision.DENY;
                } finally { pending = null; decision = null; notifyAll(); }
            }
        }
    }
    private synchronized void wake() { notifyAll(); }
    public synchronized void admitExecution(ExecutionPlan plan,CancellationToken token) {
        if(closed || launchKey==null || !launchKey.equals(plan.authorizationKey()) || launchToken!=token)
            throw new ShellExecutionException("EXECUTION_NOT_AUTHORIZED");
        launchKey=null;launchToken=null;
        if (++executions > 64) throw new ShellExecutionException("EXECUTION_LIMIT_EXCEEDED");
    }
    public synchronized void reserveArtifact(int size) {
        if (artifactBytes + size > 16 * 1024 * 1024) throw new ShellExecutionException("ARTIFACT_LIMIT_EXCEEDED");
        artifactBytes += size;
    }
    @Override public synchronized void close() { closed = true; launchKey=null;launchToken=null;grants.clear(); denied.clear(); notifyAll(); }
}
