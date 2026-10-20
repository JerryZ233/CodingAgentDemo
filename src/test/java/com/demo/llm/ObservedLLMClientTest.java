package com.demo.llm;

import com.demo.execution.*;
import com.demo.memory.MemoryTelemetry;
import com.demo.model.Message;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ObservedLLMClientTest {
    @Test void compatibleScriptedClientsReportUsageAndExceptionsAndPrecancelledCallsAreNotAttempts() {
        var telemetry=new MemoryTelemetry(Clock.systemUTC());
        var client=new ObservedLLMClient((m,t)->new LLMClient.LLMResponse("ok",null,new TokenUsage(4,2,6,null)),telemetry::record);
        client.sendMessage(List.of(Message.user("test")),null,CancellationToken.none(),ignored->{},RequestPurpose.CONSOLIDATION);
        assertEquals(6L,telemetry.usage().get(RequestPurpose.CONSOLIDATION).totalTokens());
        var failed=new ObservedLLMClient((m,t)->{throw new OperationCancelledException();},telemetry::record);
        assertThrows(OperationCancelledException.class,()->failed.sendMessage(List.of(),null));
        assertEquals(1,telemetry.usage().get(RequestPurpose.AGENT).attempts());assertNull(telemetry.usage().get(RequestPurpose.AGENT).inputTokens());
        var source=new CancellationSource();source.cancel();
        assertThrows(OperationCancelledException.class,()->client.sendMessage(List.of(),null,source.token()));
        assertEquals(1,telemetry.usage().get(RequestPurpose.AGENT).attempts());
    }
}
