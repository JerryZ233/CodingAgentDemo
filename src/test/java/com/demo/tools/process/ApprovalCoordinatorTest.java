package com.demo.tools.process;

import com.demo.execution.*;
import java.nio.file.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ApprovalCoordinatorTest {
    @TempDir Path root;
    ExecutionPlan plan(String script)throws Exception {
        return new CommandPlanner(root,ShellPolicyConfig.defaults()).prepare("{\"mode\":\"script\",\"script\":\""+script+"\"}");
    }
    ApprovalCoordinator.PendingApproval pending(ApprovalCoordinator c)throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(c.pending().isEmpty() && System.nanoTime()<end)Thread.sleep(5);
        return c.pending().orElseThrow();
    }
    @Test void onlyExactTurnGrantsSurviveCodeChangesAndRevokeClearsThem()throws Exception {
        var p=plan("echo hello");var c=new ApprovalCoordinator(true,e->{});var pool=Executors.newSingleThreadExecutor();
        try {
            var wait=pool.submit(()->c.authorize(p,CancellationToken.none()));
            var first=pending(c);assertFalse(c.submit(first.generation()+1,ApprovalCoordinator.Decision.THIS_TURN));
            assertTrue(c.submit(first.generation(),ApprovalCoordinator.Decision.THIS_TURN));assertTrue(wait.get(3,TimeUnit.SECONDS));
            assertFalse(c.submit(first.generation(),ApprovalCoordinator.Decision.ONCE));
            Files.writeString(root.resolve("source.txt"),"changed");
            assertTrue(c.authorize(plan("echo hello"),CancellationToken.none()));
            c.revoke();
            var again=pool.submit(()->c.authorize(p,CancellationToken.none()));var second=pending(c);
            assertNotEquals(first.generation(),second.generation());assertFalse(c.submit(first.generation(),ApprovalCoordinator.Decision.ONCE));
            assertTrue(c.submit(second.generation(),ApprovalCoordinator.Decision.DENY));assertFalse(again.get(3,TimeUnit.SECONDS));
            assertFalse(c.authorize(p,CancellationToken.none()));assertTrue(c.pending().isEmpty());
        } finally {c.close();pool.shutdownNow();}
        assertThrows(ShellExecutionException.class,()->new ApprovalCoordinator(false,e->{}).authorize(p,CancellationToken.none()));
    }
    @Test void cancellationWakesWaiterAndLateApprovalNeverAuthorizes()throws Exception {
        var source=new CancellationSource();var c=new ApprovalCoordinator(true,e->{});var p=plan("echo never");var pool=Executors.newSingleThreadExecutor();
        try {
            var wait=pool.submit(()->c.authorize(p,source.token()));var pending=pending(c);source.cancel();
            assertInstanceOf(OperationCancelledException.class,assertThrows(ExecutionException.class,()->wait.get(3,TimeUnit.SECONDS)).getCause());
            assertTrue(c.pending().isEmpty());assertFalse(c.submit(pending.generation(),ApprovalCoordinator.Decision.ONCE));
        } finally {c.close();pool.shutdownNow();}
    }
    @Test void approvalAndExecutionBudgetsDoNotExpand()throws Exception {
        var c=new ApprovalCoordinator(true,e->{});var p=plan("echo hello");var pool=Executors.newSingleThreadExecutor();
        try {
            for(int i=0;i<20;i++) {
                var wait=pool.submit(()->c.authorize(p,CancellationToken.none()));c.submit(pending(c).generation(),ApprovalCoordinator.Decision.ONCE);assertTrue(wait.get(3,TimeUnit.SECONDS));
            }
            assertEquals("APPROVAL_LIMIT_EXCEEDED",assertThrows(ShellExecutionException.class,()->c.authorize(p,CancellationToken.none())).getMessage());
            var launches=new ApprovalCoordinator(true,e->{});
            try {
                var token=CancellationToken.none();
                var first=pool.submit(()->launches.authorize(p,token));
                launches.submit(pending(launches).generation(),ApprovalCoordinator.Decision.THIS_TURN);assertTrue(first.get(3,TimeUnit.SECONDS));
                launches.admitExecution(p,token);
                for(int i=1;i<64;i++){assertTrue(launches.authorize(p,token));launches.admitExecution(p,token);}
                assertTrue(launches.authorize(p,token));
                assertEquals("EXECUTION_LIMIT_EXCEEDED",assertThrows(ShellExecutionException.class,()->launches.admitExecution(p,token)).getMessage());
            }finally{launches.close();}
        } finally {c.close();pool.shutdownNow();}
    }
    @Test void concurrentSecondRequestCannotQueue()throws Exception {
        var c=new ApprovalCoordinator(true,e->{});var p=plan("echo hello");var pool=Executors.newSingleThreadExecutor();
        try {
            var wait=pool.submit(()->c.authorize(p,CancellationToken.none()));pending(c);
            assertEquals("APPROVAL_ALREADY_PENDING",assertThrows(ShellExecutionException.class,()->c.authorize(p,CancellationToken.none())).getMessage());
            c.close();assertThrows(ExecutionException.class,()->wait.get(3,TimeUnit.SECONDS));
        }finally{c.close();pool.shutdownNow();}
    }
}
