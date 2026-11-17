package com.example.budgetapp.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;

/** Read-only model discovery. No configuration writes, inference, or credential-bearing redirects. */
public final class AiModelCatalog {
    public enum Failure { CONFIG, AUTH, UNSUPPORTED, RATE_LIMIT, SERVER, REDIRECT, INVALID_RESPONSE, EMPTY, NETWORK, TOO_LARGE }

    public static final class CatalogException extends IOException {
        public final Failure failure;
        public CatalogException(Failure failure) { super(failure.name()); this.failure = failure; }
    }

    public static final class Response {
        public final int status;
        public final String body;
        public Response(int status, String body) { this.status = status; this.body = body; }
    }

    public interface Transport { Response get(URI endpoint, String apiKey) throws IOException; }
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private final Transport transport;

    public AiModelCatalog() { this(AiModelCatalog::httpGet); }
    public AiModelCatalog(Transport transport) { this.transport = transport; }

    public List<String> list(String baseUrl, String apiKey) throws IOException {
        URI endpoint;
        try { endpoint = AiEndpoint.resolve(baseUrl, "/models"); }
        catch (IOException e) { throw new CatalogException(Failure.CONFIG); }
        String key = apiKey == null ? "" : apiKey.trim();
        if (key.isEmpty() || key.matches("(?s).*[\\p{Cntrl}].*")) throw new CatalogException(Failure.CONFIG);
        Response response = transport.get(endpoint, key);
        if (response.status >= 300 && response.status < 400) throw new CatalogException(Failure.REDIRECT);
        if (response.status == 401 || response.status == 403) throw new CatalogException(Failure.AUTH);
        if (response.status == 404 || response.status == 405) throw new CatalogException(Failure.UNSUPPORTED);
        if (response.status == 429) throw new CatalogException(Failure.RATE_LIMIT);
        if (response.status < 200 || response.status >= 300) throw new CatalogException(Failure.SERVER);
        return parse(response.body);
    }

    public static List<String> parse(String body) throws CatalogException {
        if (body == null || body.length() > MAX_BYTES) throw new CatalogException(Failure.TOO_LARGE);
        try {
            JsonElement root = JsonParser.parseString(body);
            if (!root.isJsonObject()) throw new CatalogException(Failure.INVALID_RESPONSE);
            JsonObject object = root.getAsJsonObject();
            JsonElement data = object.get("data");
            if (data == null || !data.isJsonArray()) throw new CatalogException(Failure.INVALID_RESPONSE);
            JsonArray array = data.getAsJsonArray();
            if (array.size() > 10000) throw new CatalogException(Failure.TOO_LARGE);
            TreeSet<String> ids = new TreeSet<>();
            for (JsonElement entry : array) {
                JsonElement id = entry.isJsonObject() ? entry.getAsJsonObject().get("id") : entry;
                if (id == null || !id.isJsonPrimitive() || !id.getAsJsonPrimitive().isString()) continue;
                String value = id.getAsString().trim();
                if (!value.isEmpty() && value.length() <= 512 && !value.matches("(?s).*[\\p{Cntrl}].*")) ids.add(value);
            }
            if (ids.isEmpty()) throw new CatalogException(Failure.EMPTY);
            return Collections.unmodifiableList(new ArrayList<>(ids));
        } catch (CatalogException e) { throw e; }
        catch (RuntimeException e) { throw new CatalogException(Failure.INVALID_RESPONSE); }
    }

    private static Response httpGet(URI endpoint, String apiKey) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) endpoint.toURL().openConnection();
        try {
            connection.setRequestMethod("GET");
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(8000);
            connection.setUseCaches(false);
            connection.setRequestProperty("Authorization", "Bearer " + apiKey);
            connection.setRequestProperty("Accept", "application/json");
            int status = connection.getResponseCode();
            // Never display or log server error bodies, which may echo credentials.
            if (status < 200 || status >= 300) return new Response(status, "");
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int size;
                while ((size = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new IOException("cancelled");
                    if (output.size() + size > MAX_BYTES) throw new CatalogException(Failure.TOO_LARGE);
                    output.write(buffer, 0, size);
                }
                return new Response(status, new String(output.toByteArray(), StandardCharsets.UTF_8));
            }
        } finally { connection.disconnect(); }
    }
}
