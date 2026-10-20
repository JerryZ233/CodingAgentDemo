package com.demo.memory;

import com.demo.model.Message;
import com.demo.model.ToolCall;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TokenEstimatorTest {
    private final TokenEstimator estimator = new TokenEstimator();

    @Test void estimatesAsciiAndUnicodeCodePointsAndRoundsUp() {
        assertEquals(0, estimator.text(null));
        assertEquals(0, estimator.text(""));
        assertEquals(1, estimator.text("abc"));
        assertEquals(2, estimator.text("abcd"));
        assertEquals(3, estimator.text("中文😀"));
        assertEquals(3, estimator.text("ab中😀"));
    }

    @Test void includesArgumentsResultsAndSchemasButExcludesInternalIdentityAndDiagnosticFields() {
        var call = Message.assistantToolCalls(null, List.of(new ToolCall("call", "read_file", "{\"path\":\"a\"}")));
        var largeCall = Message.assistantToolCalls(null, List.of(new ToolCall("call", "read_file", "x".repeat(3000))));
        assertTrue(estimator.message(largeCall) > estimator.message(call) + 900);
        assertEquals(estimator.message(Message.user("hi")), estimator.message(Message.user("hi")));
        assertEquals(estimator.message(Message.tool("call", "read_file", "result", false, "private diagnostic")),
                estimator.message(Message.tool("call", "another_name", "result", true, null)));
        assertTrue(estimator.message(Message.tool("call", "read_file", "x".repeat(300))) > 100);
        assertEquals(0, estimator.tools("[]"));
        assertEquals(0, estimator.tools(null));
        assertTrue(estimator.tools("[{\"function\":{\"description\":\"" + "x".repeat(300) + "\"}}]") > 100);
    }
}
