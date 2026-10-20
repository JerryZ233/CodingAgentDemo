package com.demo.agent;

import com.demo.config.Config;
import com.demo.llm.LLMClient;
import com.demo.model.*;
import com.demo.tools.ToolRegistry;
import com.demo.tools.support.ToolLimits;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class FileEditingWorkflowTest {
    @TempDir Path root;

    @Test void agentFindsReadsEditsAndVerifiesFileLargerThanMessageBudget() throws Exception {
        String original = "// unchanged filler\r\n".repeat(500) + "return timeout * 1000;\r\n";
        assertTrue(original.length() > ToolLimits.MESSAGE_CHARS);
        Path file = Files.writeString(root.resolve("Example.java"), original);
        AtomicInteger step = new AtomicInteger();
        AtomicReference<String> sha = new AtomicReference<>();
        LLMClient model = (messages, descriptions) -> {
            int current = step.getAndIncrement();
            JsonObject arguments = new JsonObject();
            arguments.addProperty("path", "Example.java");
            if (current > 0) {
                Message result = messages.get(messages.size() - 1);
                assertEquals("tool", result.getRole()); assertTrue(result.getToolSuccess(), result.getContent());
                assertTrue(result.getContent().length() <= ToolLimits.MESSAGE_CHARS);
                JsonObject data = JsonParser.parseString(result.getContent()).getAsJsonObject();
                switch (current) {
                    case 1 -> assertEquals(501, data.getAsJsonArray("matches").get(0).getAsJsonObject().get("line").getAsInt());
                    case 2 -> {
                        assertEquals("return timeout * 1000;\r\n", data.get("content").getAsString());
                        assertFalse(data.get("complete").getAsBoolean()); sha.set(data.get("sha256").getAsString());
                    }
                    case 3 -> { assertEquals("replace", data.get("mode").getAsString()); sha.set(data.get("sha256").getAsString()); }
                    case 4 -> {
                        assertEquals("return Math.multiplyExact(timeout, 1000);\r\n", data.get("content").getAsString());
                        return new LLMClient.LLMResponse("修改完成并已复查。", null);
                    }
                    default -> fail("Unexpected model request");
                }
            }
            String tool;
            if (current == 0) { tool = "search_text"; arguments.addProperty("text", "timeout"); }
            else if (current == 1 || current == 3) {
                tool = "read_file"; arguments.addProperty("start_line", 501); arguments.addProperty("max_lines", 1);
                if (current == 3) arguments.addProperty("expected_sha256", sha.get());
            } else {
                tool = "write_file"; arguments.addProperty("mode", "replace");
                arguments.addProperty("old_text", "return timeout * 1000;");
                arguments.addProperty("new_text", "return Math.multiplyExact(timeout, 1000);");
                arguments.addProperty("expected_sha256", sha.get());
            }
            return new LLMClient.LLMResponse("", List.of(new ToolCall(tool, arguments.toString())));
        };
        Conversation context = new Conversation(); context.addUserMessage("修复 timeout 乘法溢出");
        var agent = new CodingAgent(model, ToolRegistry.fromConfig(Config.defaults(), root), 8);
        assertTrue(agent.runTurn(context, AgentCheckpoint.noop()).isCompleted());
        assertEquals(5, step.get());
        assertEquals(original.replace("return timeout * 1000;", "return Math.multiplyExact(timeout, 1000);"), Files.readString(file));
    }
}
