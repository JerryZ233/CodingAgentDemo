package com.demo.llm.impl;

import com.demo.llm.LLMClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LLMClientImplTest {

    @Test
    @DisplayName("Parses OpenAI-compatible tool calls")
    void parsesToolCalls() {
        LLMClientImpl client = new LLMClientImpl("http://localhost", "key", "model", 100, 0.0);
        String responseJson = """
            {
              "choices": [
                {
                  "message": {
                    "content": "",
                    "tool_calls": [
                      {
                        "id": "call_1",
                        "type": "function",
                        "function": {
                          "name": "read_file",
                          "arguments": "{\\"path\\":\\"README.md\\"}"
                        }
                      }
                    ]
                  }
                }
              ]
            }
            """;

        LLMClient.LLMResponse response = client.parseResponse(responseJson);

        assertTrue(response.hasToolCalls());
        assertEquals("call_1", response.getToolCalls().get(0).getId());
        assertEquals("read_file", response.getToolCalls().get(0).getToolName());
        assertEquals("{\"path\":\"README.md\"}", response.getToolCalls().get(0).getArguments());
    }

    @Test
    @DisplayName("Invalid LLM JSON becomes an error response")
    void invalidJsonIsErrorResponse() {
        LLMClientImpl client = new LLMClientImpl("http://localhost", "key", "model", 100, 0.0);

        LLMClient.LLMResponse response = client.parseResponse("not json");

        assertTrue(response.isError());
        assertFalse(response.hasToolCalls());
    }
}
