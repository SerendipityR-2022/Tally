package com.example.budgetapp.ai;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/** One API root for inference and discovery, including custom reverse-proxy prefixes. */
public final class AiEndpoint {
    private AiEndpoint() { }

    public static URI resolve(String baseUrl, String endpoint) throws IOException {
        String raw = baseUrl == null ? "" : baseUrl.trim();
        if (raw.isEmpty()) throw new IOException("请先填写 Base URL。");
        if (!raw.contains("://")) raw = "https://" + raw;
        try {
            URI uri = new URI(raw);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!("https".equals(scheme) || "http".equals(scheme)) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || uri.getPort() > 65535 || (uri.getPath() != null && uri.getPath().contains(".."))) {
                throw new IOException("Base URL 必须是有效的 HTTP(S) 地址，且不包含账号、查询参数或片段。");
            }
            String path = uri.getPath() == null ? "" : uri.getPath().replaceAll("/+$", "");
            boolean fullEndpoint = false;
            for (String suffix : new String[]{"/chat/completions", "/audio/transcriptions", "/audio/translations",
                    "/responses", "/embeddings", "/models"}) {
                if (path.endsWith(suffix)) {
                    path = path.substring(0, path.length() - suffix.length());
                    fullEndpoint = true;
                    break;
                }
            }
            if (!fullEndpoint && !path.matches(".*/v[0-9]+[a-zA-Z0-9._-]*$")) path += "/v1";
            return new URI(scheme, null, uri.getHost(), uri.getPort(), path + endpoint, null, null);
        } catch (URISyntaxException e) {
            // Never echo raw URL/key data from parser exceptions.
            throw new IOException("Base URL 格式无效。");
        }
    }
}
