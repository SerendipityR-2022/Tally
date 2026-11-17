package com.example.budgetapp.ai;

import java.net.ServerSocket;
import java.net.Socket;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import static com.example.budgetapp.ai.AiModelCatalog.*;
import static org.junit.Assert.*;

public class AiModelCatalogTest {
    private static final String BODY = "{\"data\":[{\"id\":\"z-model\"},{\"id\":\"a-model\"},{\"id\":\"z-model\"}]}";

    @Test public void parsesDeduplicatesSortsAndRetainsAllModalities() throws Exception {
        assertEquals(Arrays.asList("a-model", "z-model"), parse(BODY));
        assertEquals(Arrays.asList("audio-only", "vision-only"), parse("{\"data\":[\"vision-only\",{\"id\":\"audio-only\"}]}"));
        assertThrows(UnsupportedOperationException.class, () -> parse(BODY).clear());
    }
    @Test public void skipsInvalidIdsButDoesNotGuessCapabilities() throws Exception {
        assertEquals(Arrays.asList("custom:2026/model"), parse("{\"data\":[null,7,{}, {\"id\":42},{\"id\":\"\"},{\"id\":\"custom:2026/model\"}]}"));
    }
    @Test public void rejectsMalformedEmptyAndOversizedResponses() {
        for (String body : new String[]{"<html>secret</html>", "{}", "[]", "{\"data\":{}}", "{bad"}) {
            assertEquals(Failure.INVALID_RESPONSE, assertThrows(CatalogException.class, () -> parse(body)).failure);
        }
        assertEquals(Failure.EMPTY, assertThrows(CatalogException.class, () -> parse("{\"data\":[]}")).failure);
        assertEquals(Failure.TOO_LARGE, assertThrows(CatalogException.class, () -> parse(new String(new char[2097153]))).failure);
    }
    @Test public void invalidCredentialsNeverReachTransport() {
        AiModelCatalog client = new AiModelCatalog((uri, key) -> { fail("must not request"); return null; });
        assertEquals(Failure.CONFIG, assertThrows(CatalogException.class, () -> client.list("", "secret")).failure);
        assertEquals(Failure.CONFIG, assertThrows(CatalogException.class, () -> client.list("example.test", "")).failure);
        assertEquals(Failure.CONFIG, assertThrows(CatalogException.class, () -> client.list("example.test", "secret\r\nheader")).failure);
    }
    @Test public void sendsOnlyTheTypedServiceEndpointAndTrimmedKey() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AiModelCatalog client = new AiModelCatalog((uri, key) -> {
            assertEquals("https://private.example.test/prefix/v1/models", uri.toString());
            assertEquals("test-key-not-real", key);
            calls.incrementAndGet(); return new Response(200, BODY);
        });
        assertEquals(2, client.list("private.example.test/prefix/v1/", " test-key-not-real ").size());
        assertEquals(1, calls.get());
    }
    @Test public void mapsStatusErrorsWithoutShowingServerSecrets() {
        int[] statuses = {302, 401, 403, 404, 405, 429, 500};
        Failure[] failures = {Failure.REDIRECT, Failure.AUTH, Failure.AUTH, Failure.UNSUPPORTED, Failure.UNSUPPORTED, Failure.RATE_LIMIT, Failure.SERVER};
        for (int i = 0; i < statuses.length; i++) {
            final int status = statuses[i];
            AiModelCatalog client = new AiModelCatalog((uri, key) -> new Response(status, "SECRET_KEY_ECHO"));
            CatalogException error = assertThrows(CatalogException.class, () -> client.list("example.test", "not-real"));
            assertEquals(failures[i], error.failure);
            assertFalse(error.getMessage().contains("SECRET"));
        }
    }
    @Test public void realHttpGetCarriesAuthAndNeverFollowsRedirects() throws Exception {
        ServerSocket server = new ServerSocket();
        server.bind(new InetSocketAddress("127.0.0.1", 0));
        AtomicInteger received = new AtomicInteger();
        AtomicReference<Throwable> serverError = new AtomicReference<>();
        Thread responder = new Thread(() -> {
            while (!server.isClosed()) {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(4000);
                    BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                    String firstLine = input.readLine();
                    StringBuilder headers = new StringBuilder();
                    String line;
                    while ((line = input.readLine()) != null && !line.isEmpty()) headers.append(line).append('\n');
                    assertTrue(firstLine.startsWith("GET "));
                    assertTrue(headers.toString().contains("Authorization: Bearer fixture-key"));
                    received.incrementAndGet();
                    String response;
                    if (firstLine.contains("/redirect/")) response = "HTTP/1.1 302 Found\r\nLocation: /v1/models\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
                    else response = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                            + BODY.getBytes(StandardCharsets.UTF_8).length + "\r\nConnection: close\r\n\r\n" + BODY;
                    socket.getOutputStream().write(response.getBytes(StandardCharsets.UTF_8));
                    socket.getOutputStream().flush();
                } catch (Throwable e) {
                    if (!server.isClosed()) serverError.compareAndSet(null, e);
                    break;
                }
            }
        }, "fixture-model-server");
        responder.setDaemon(true); responder.start();
        try {
            String origin = "http://127.0.0.1:" + server.getLocalPort();
            assertEquals(2, new AiModelCatalog().list(origin, "fixture-key").size());
            assertEquals(Failure.REDIRECT, assertThrows(CatalogException.class,
                    () -> new AiModelCatalog().list(origin + "/redirect", "fixture-key")).failure);
            assertEquals(2, received.get());
            assertNull(serverError.get());
        } finally { server.close(); responder.join(1000); }

    }
}
