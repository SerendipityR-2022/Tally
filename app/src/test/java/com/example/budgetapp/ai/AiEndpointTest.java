package com.example.budgetapp.ai;

import org.junit.Test;
import java.io.IOException;
import static org.junit.Assert.*;

public class AiEndpointTest {
    @Test public void normalizesRootsVersionsPrefixesAndFullEndpointsConsistently() throws Exception {
        String[][] paths = {{"api.example.test", "/v1"}, {"https://api.example.test/", "/v1"},
                {"https://api.example.test/v1/", "/v1"}, {"https://api.example.test/proxy", "/proxy/v1"},
                {"https://api.example.test/proxy/v1/", "/proxy/v1"}, {"https://api.example.test/api/v3", "/api/v3"},
                {"https://api.example.test/proxy/v1/chat/completions", "/proxy/v1"},
                {"https://api.example.test/audio/transcriptions/", ""},
                {"https://api.example.test/prefix/models/", "/prefix"},
                {"https://api.example.test/v1/responses", "/v1"}};
        for (String[] test : paths) {
            assertEquals(test[0], test[1] + "/models", AiEndpoint.resolve(test[0], "/models").getPath());
            assertEquals(test[0], test[1] + "/chat/completions", AiEndpoint.resolve(test[0], "/chat/completions").getPath());
        }
    }
    @Test public void retainsExplicitSchemeHostAndPort() throws Exception {
        assertEquals("http://127.0.0.1:8877/gateway/v1/models",
                AiEndpoint.resolve("http://127.0.0.1:8877/gateway/v1/", "/models").toString());
    }
    @Test public void rejectsCredentialsQueriesFragmentsAndInvalidSchemesWithoutEchoingInput() {
        for (String value : new String[]{"", "https://user:secret@example.test", "https://example.test?key=secret",
                "https://example.test/#secret", "file:///secret", "ftp://example.test", "https://example.test:99999", "https://example.test/a/../b"}) {
            IOException error = assertThrows(IOException.class, () -> AiEndpoint.resolve(value, "/models"));
            assertFalse(error.getMessage().contains("secret"));
        }
    }
}
