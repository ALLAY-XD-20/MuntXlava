package dev.pawan.muntxlava;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/** Small shared HTTP helper. */
final class Http {

    static final ObjectMapper JSON = new ObjectMapper();
    static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private Http() {}

    record Res(int status, String body, HttpHeaders headers) {
        JsonNode json() {
            try {
                return body == null || body.isBlank() ? JSON.createObjectNode() : JSON.readTree(body);
            } catch (Exception e) {
                return JSON.createObjectNode();
            }
        }

        String header(String name) { return headers.firstValue(name).orElse(null); }
    }

    static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    /** Builds base?k=v&k=v from alternating key/value args. */
    static String url(String base, Object... kv) {
        StringBuilder sb = new StringBuilder(base);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (kv[i + 1] == null) continue;
            sb.append(sb.indexOf("?") < 0 ? '?' : '&').append(enc(String.valueOf(kv[i])))
                    .append('=').append(enc(String.valueOf(kv[i + 1])));
        }
        return sb.toString();
    }

    static Res get(String url, Map<String, String> headers) throws Exception {
        return send("GET", url, headers, null);
    }

    static Res post(String url, Map<String, String> headers, String body) throws Exception {
        return send("POST", url, headers, body);
    }

    static Res postJson(String url, Map<String, String> headers, Object body) throws Exception {
        Map<String, String> h = new HashMap<>(headers == null ? Map.of() : headers);
        h.put("Content-Type", "application/json");
        return send("POST", url, h, JSON.writeValueAsString(body));
    }

    static Res send(String method, String url, Map<String, String> headers, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .setHeader("User-Agent", UA);
        if (headers != null) headers.forEach(b::setHeader);
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> res = CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Res(res.statusCode(), res.body(), res.headers());
    }
}
