package com.example.budgetapp.ai;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Base64;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.budgetapp.BackupData;
import com.example.budgetapp.BackupManager;
import com.example.budgetapp.util.CategoryTestContext;
import com.google.gson.Gson;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class AiVisionRestoreInstrumentedTest {
    private final CategoryTestContext context = new CategoryTestContext(
            InstrumentationRegistry.getInstrumentation().getTargetContext());

    @After public void cleanup() { context.clear(); }

    @Test public void legacyZipUsesRestoredConnectionForTheFirstVisionRequest() throws Exception {
        try (AiEndpointFixture endpoint = new AiEndpointFixture(200,
                reply("[{\"type\":0,\"amount\":37.42,\"category\":\"餐饮\",\"note\":\"示例咖啡\"}]"))) {
            AiConfig stale = new AiConfig();
            stale.textModel = "stale-text";
            stale.visionModel = "stale-vision";
            stale.visionBaseUrl = "http://127.0.0.1:1/v1";
            stale.visionApiKey = "stale-key";
            stale.visionTestOk = true;
            stale.save(context);

            BackupData oldBackup = new BackupData();
            Map<String, BackupData.PrefItem> prefs = new HashMap<>();
            prefs.put(AiConfig.KEY_ENABLED, new BackupData.PrefItem("Boolean", "true"));
            prefs.put(AiConfig.KEY_BASE_URL, new BackupData.PrefItem("String", endpoint.baseUrl()));
            prefs.put(AiConfig.KEY_API_KEY, new BackupData.PrefItem("String", "restored-fixture-key"));
            prefs.put("ai_model", new BackupData.PrefItem("String", "legacy-model"));
            prefs.put("ai_supports_vision", new BackupData.PrefItem("Boolean", "true"));
            prefs.put(AiConfig.KEY_VISION_MODEL, new BackupData.PrefItem("String", "restored-vision"));
            oldBackup.appPreferences = prefs;
            File file = File.createTempFile("legacy-ai-backup-", ".zip", context.getCacheDir());
            try {
                try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(file))) {
                    zip.putNextEntry(new ZipEntry("backup_data.json"));
                    zip.write(new Gson().toJson(oldBackup).getBytes(StandardCharsets.UTF_8));
                    zip.closeEntry();
                }
                BackupManager.importFromZip(context, Uri.fromFile(file));
            } finally { file.delete(); }

            AiConfig restored = AiConfig.load(context);
            assertTrue(restored.enabled && restored.isVisionReady());
            assertEquals("legacy-model", restored.textModel);
            assertFalse(restored.visionTestOk);
            Bitmap screenshot = Bitmap.createBitmap(240, 360, Bitmap.Config.ARGB_8888);
            ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
            try { assertTrue(screenshot.compress(Bitmap.CompressFormat.JPEG, 85, jpeg)); }
            finally { screenshot.recycle(); }
            AiAccountingClient client = new AiAccountingClient();
            client.setConfig(restored);
            assertEquals(37.42, client.parseVisionImage(context, "测试账单截图", jpeg.toByteArray(), "image/jpeg")
                    .get(0).amount, 0.001);
            JSONObject payload = endpoint.payload();
            assertEquals("restored-vision", payload.getString("model"));
            assertJpeg(payload, 240, 360);
        }
    }

    @Test public void zipAndCsvCarryActiveVisionSettingsAndSavedProfiles() throws Exception {
        for (boolean zip : new boolean[]{true, false}) {
            AiConfig config = new AiConfig();
            config.enabled = true;
            config.textModel = "fixture-text";
            config.visionModel = "active-vision";
            config.visionBaseUrl = "https://fixture.invalid/vision/v1";
            config.visionApiKey = "restored-fixture-key";
            config.save(context);
            AiModelProfile.saveProfiles(context, Collections.singletonList(new AiModelProfile(
                    "其他视觉配置", "saved-vision", "https://fixture.invalid/v1", "fixture-only", "vision")));
            File file = File.createTempFile("ai-settings-", zip ? ".zip" : ".csv", context.getCacheDir());
            try {
                Uri uri = Uri.fromFile(file);
                if (zip) BackupManager.exportToZip(context, uri, null, null, null);
                else BackupManager.exportToExcel(context, uri, null, null);
                context.clear();
                if (zip) BackupManager.importFromZip(context, uri);
                else BackupManager.importFromExcel(context, uri);
                AiConfig restored = AiConfig.load(context);
                assertTrue(restored.enabled && restored.isVisionReady());
                assertEquals("active-vision", restored.visionModel);
                assertEquals(config.visionBaseUrl, restored.getEffectiveVisionBaseUrl());
                assertEquals("saved-vision", AiModelProfile.loadProfilesByType(context, "vision").get(0).modelName);
            } finally { file.delete(); }
        }
    }

    @Test public void probeSendsDecodableJpegAndDoesNotTreatHttpOrIncorrectAnswersAsSuccess() throws Exception {
        for (int outcome : new int[]{0, 1, 2}) {
            int status = outcome == 2 ? 400 : 200;
            String body = status == 400 ? "{\"error\":{\"message\":\"do not echo this body\"}}"
                    : reply(outcome == 0 ? "4729" : "ok");
            try (AiEndpointFixture endpoint = new AiEndpointFixture(status, body)) {
                AiConfig config = new AiConfig();
                config.visionModel = "fixture-vision";
                config.visionBaseUrl = endpoint.baseUrl();
                config.visionApiKey = "restored-fixture-key";
                AiAccountingClient client = new AiAccountingClient();
                client.setConfig(config);
                AiAccountingClient.TestResult result = client.testConfiguration(context);
                assertEquals(outcome == 0, result.visionOk);
                assertEquals(outcome == 0, AiConfig.load(context).visionTestOk);
                assertFalse(result.visionMessage.contains("do not echo"));
                if (status == 400) assertTrue(result.visionMessage.contains("400"));
                assertJpeg(endpoint.payload(), 256, 256);
            }
        }
    }

    private static void assertJpeg(JSONObject payload, int width, int height) throws Exception {
        JSONArray content = payload.getJSONArray("messages").getJSONObject(1).getJSONArray("content");
        String data = content.getJSONObject(1).getJSONObject("image_url").getString("url");
        assertTrue(data.startsWith("data:image/jpeg;base64,"));
        byte[] bytes = Base64.decode(data.substring(data.indexOf(',') + 1), Base64.NO_WRAP);
        assertEquals(0xffd8, ((bytes[0] & 255) << 8) | (bytes[1] & 255));
        assertEquals(0xffd9, ((bytes[bytes.length - 2] & 255) << 8) | (bytes[bytes.length - 1] & 255));
        Bitmap decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        assertNotNull(decoded);
        try {
            assertEquals(width, decoded.getWidth());
            assertEquals(height, decoded.getHeight());
        } finally { decoded.recycle(); }
    }

    private static String reply(String content) throws Exception {
        return new JSONObject().put("choices", new JSONArray().put(new JSONObject()
                .put("message", new JSONObject().put("content", content)))).toString();
    }

    private static final class AiEndpointFixture implements AutoCloseable {
        private final ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        private final FutureTask<JSONObject> request;

        AiEndpointFixture(int status, String response) throws Exception {
            server.setSoTimeout(5000);
            request = new FutureTask<>(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(5000);
                    DataInputStream in = new DataInputStream(socket.getInputStream());
                    ByteArrayOutputStream headers = new ByteArrayOutputStream();
                    int end = 0;
                    while (end != 0x0d0a0d0a && headers.size() < 16000) {
                        int next = in.readUnsignedByte();
                        headers.write(next);
                        end = (end << 8) | next;
                    }
                    String requestHeaders = headers.toString("US-ASCII");
                    assertTrue(requestHeaders.startsWith("POST /v1/chat/completions "));
                    assertTrue(requestHeaders.toLowerCase(java.util.Locale.ROOT)
                            .contains("authorization: bearer restored-fixture-key"));
                    Matcher count = Pattern.compile("(?im)^Content-Length: (\\d+)").matcher(requestHeaders);
                    assertTrue(count.find());
                    byte[] payload = new byte[Integer.parseInt(count.group(1))];
                    in.readFully(payload);
                    byte[] body = response.getBytes(StandardCharsets.UTF_8);
                    socket.getOutputStream().write(("HTTP/1.1 " + status + " Fixture\r\nConnection: close\r\n"
                            + "Content-Type: application/json\r\nContent-Length: " + body.length + "\r\n\r\n")
                            .getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().write(body);
                    return new JSONObject(new String(payload, StandardCharsets.UTF_8));
                }
            });
            Thread worker = new Thread(request, "vision-fixture");
            worker.setDaemon(true);
            worker.start();
        }

        String baseUrl() { return "http://127.0.0.1:" + server.getLocalPort() + "/v1"; }
        JSONObject payload() throws Exception { return request.get(5, TimeUnit.SECONDS); }
        @Override public void close() throws Exception { server.close(); }
    }
}
