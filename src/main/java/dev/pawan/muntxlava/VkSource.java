package dev.pawan.muntxlava;

import com.fasterxml.jackson.databind.JsonNode;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.InternalAudioTrack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** VK Music via the VK API (user token, or a cookie used to mint one). */
public class VkSource extends BaseSource {

    private static final Logger log = LoggerFactory.getLogger(VkSource.class);
    private static final String API = "https://api.vk.com/method/";
    private static final String API_VERSION = "5.131";
    private static final String B64 = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMN0PQRSTUVWXYZO123456789+/=";
    private static final String BROWSER_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:146.0) Gecko/20100101 Firefox/146.0";
    private static final String API_UA = "KateMobileAndroid/56 lite-460 (Android 4.4.2; SDK 19; x86; unknown Android SDK built for x86; en)";

    private static final String HOST = "https?://(?:www\\.|m\\.)?vk\\.(?:com|ru)/";
    private static final String HASH = "(?:(?:%2F|_|/|(?:\\?|&)access_hash=)(?<hash>[a-z0-9]+))?";
    private static final Pattern PLAYLIST_Z = Pattern.compile(
            HOST + ".*?[?&]z=audio_playlist(?<owner>-?\\d+)_(?<id>\\d+)" + HASH, Pattern.CASE_INSENSITIVE);
    private static final Pattern PLAYLIST = Pattern.compile(
            HOST + "music/(?:playlist|album)/(?<owner>-?\\d+)_(?<id>\\d+)" + HASH, Pattern.CASE_INSENSITIVE);
    private static final Pattern TRACK = Pattern.compile(
            HOST + "audio(?<owner>-?\\d+)_(?<id>\\d+)(?:(?:%2F|_|/)(?<hash>[a-z0-9]+))?", Pattern.CASE_INSENSITIVE);
    private static final Pattern AUDIOS = Pattern.compile(HOST + "audios(?<id>-?\\d+)", Pattern.CASE_INSENSITIVE);

    private final MuntXLavaConfig.Vk vc;
    private final Object authLock = new Object();
    private volatile String accessToken;
    private volatile long tokenExpiry;
    private volatile long userId;

    public VkSource(MuntXLavaConfig cfg) {
        super(cfg, cfg.getVk().getSourceName());
        this.vc = cfg.getVk();
        this.accessToken = vc.getUserToken().isEmpty() ? null : vc.getUserToken();
    }

    @Override
    protected AudioItem load(String id) throws Exception {
        if (id.startsWith("vksearch:")) return search(id.substring(9).trim());
        if (id.startsWith("vkrec:")) return recommendations(id.substring(6).trim());

        Matcher m = PLAYLIST_Z.matcher(id);
        if (!m.lookingAt()) m = PLAYLIST.matcher(id);
        if (m.lookingAt()) return audioList(m.group("owner"), m.group("id"), m.group("hash"));

        m = TRACK.matcher(id);
        if (m.lookingAt()) return single(m.group("owner"), m.group("id"), m.group("hash"));

        m = AUDIOS.matcher(id);
        if (m.lookingAt()) return audioList(m.group("id"), null, null);
        return null;
    }

    // ------------------------------------------------------------------ auth

    private void refreshToken() throws Exception {
        if (vc.getUserCookie().isEmpty()) throw new IllegalStateException("no VK cookie for token refresh");
        synchronized (authLock) {
            Http.Res res = Http.post("https://login.vk.ru/?act=web_token", Map.of(
                    "User-Agent", BROWSER_UA, "Referer", "https://vk.ru/", "Origin", "https://vk.ru",
                    "Cookie", vc.getUserCookie(), "Content-Type", "application/x-www-form-urlencoded"),
                    "version=1&app_id=6287487");
            JsonNode body = res.json();
            if (res.status() != 200 || !"okay".equals(body.path("type").asText()) || !body.path("data").hasNonNull("access_token")) {
                throw new IllegalStateException("VK token refresh failed: " + body.path("error_info").asText("HTTP " + res.status()));
            }
            JsonNode d = body.path("data");
            accessToken = d.path("access_token").asText();
            tokenExpiry = d.path("expires").asLong(0) * 1000L;
            userId = d.path("user_id").asLong(userId);
            log.info("VK access token refreshed");
        }
    }

    private void ensureAuth() throws Exception {
        boolean expired = tokenExpiry > 0 && System.currentTimeMillis() >= tokenExpiry - 60_000;
        if (accessToken == null || expired) refreshToken();
        if (userId == 0) {
            JsonNode r = call("users.get", Map.of());
            if (r != null && r.path(0).hasNonNull("id")) userId = r.path(0).path("id").asLong();
        }
    }

    /** Calls a VK API method. Returns the `response` node or null. */
    private JsonNode call(String method, Map<String, String> params) throws Exception {
        return call(method, params, true);
    }

    private JsonNode call(String method, Map<String, String> params, boolean retry) throws Exception {
        Map<String, String> p = new LinkedHashMap<>(params);
        p.put("access_token", accessToken == null ? "" : accessToken);
        p.put("v", API_VERSION);
        Object[] kv = new Object[p.size() * 2];
        int i = 0;
        for (Map.Entry<String, String> e : p.entrySet()) { kv[i++] = e.getKey(); kv[i++] = e.getValue(); }
        Http.Res res = Http.get(Http.url(API + method, kv), Map.of("User-Agent", API_UA));
        JsonNode body = res.json();
        if (body.has("error")) {
            int code = body.path("error").path("error_code").asInt();
            if (code == 5 && retry && !vc.getUserCookie().isEmpty()) {
                refreshToken();
                return call(method, params, false);
            }
            throw new IllegalStateException(body.path("error").path("error_msg").asText("VK error " + code));
        }
        if (res.status() != 200) throw new IllegalStateException("VK HTTP " + res.status());
        return body.path("response");
    }

    // ------------------------------------------------------------------ builders

    private AudioTrack build(JsonNode item) {
        if (!item.hasNonNull("id")) return null;
        String id = item.path("owner_id").asText() + "_" + item.path("id").asText();
        JsonNode thumb = item.path("album").path("thumb");
        if (thumb.isMissingNode()) thumb = item.path("album").path("images").path(0);
        String art = text(thumb, "photo_1200", "photo_600", "photo_300", "url");
        return track(item.path("title").asText("Unknown Title"), item.path("artist").asText("Unknown Artist"),
                item.path("duration").asLong(0) * 1000L, id, false, "https://vk.com/audio" + id, art,
                item.path("external_ids").path("isrc").asText(null), item.path("access_key").asText(null));
    }

    private List<AudioTrack> buildAll(JsonNode items, int max) {
        List<AudioTrack> out = new ArrayList<>();
        for (JsonNode i : items) {
            AudioTrack t = build(i);
            if (t != null) out.add(t);
            if (out.size() >= max) break;
        }
        return out;
    }

    // ------------------------------------------------------------------ loaders

    private AudioItem search(String query) throws Exception {
        if (query.isEmpty()) return AudioReference.NO_TRACK;
        ensureAuth();
        JsonNode r = call("audio.search", Map.of("q", query, "count", String.valueOf(cfg.getSearchLimit()), "extended", "1"));
        return playlist("VK search: " + query, buildAll(r.path("items"), cfg.getSearchLimit()), true);
    }

    private AudioItem recommendations(String query) throws Exception {
        ensureAuth();
        String audioId = query;
        if (!query.matches("-?\\d+_\\d+")) {
            AudioItem found = search(query);
            if (found instanceof AudioPlaylist pl && !pl.getTracks().isEmpty()) audioId = pl.getTracks().get(0).getIdentifier();
            else return AudioReference.NO_TRACK;
        }
        JsonNode r = call("audio.getRecommendations", Map.of("target_audio", audioId, "count", "20", "extended", "1"));
        return playlist("VK Recommendations", buildAll(r.path("items"), 20), false);
    }

    private AudioItem single(String owner, String id, String hash) throws Exception {
        ensureAuth();
        String audios = owner + "_" + id + (hash == null ? "" : "_" + hash);
        JsonNode r = call("audio.getById", Map.of("audios", audios, "extended", "1"));
        AudioTrack t = r.isArray() && !r.isEmpty() ? build(r.get(0)) : null;
        if (t == null) throw new FriendlyException("VK track not found", FriendlyException.Severity.COMMON, null);
        return t;
    }

    private AudioItem audioList(String owner, String playlistId, String accessKey) throws Exception {
        ensureAuth();
        Map<String, String> p = new LinkedHashMap<>();
        p.put("owner_id", owner);
        p.put("extended", "1");
        p.put("count", String.valueOf(cfg.getCollectionLimit()));
        if (playlistId != null) p.put("album_id", playlistId);
        if (accessKey != null) p.put("access_key", accessKey);
        JsonNode r = call("audio.get", p);
        return playlist("VK Playlist", buildAll(r.path("items"), cfg.getCollectionLimit()), false);
    }

    // ------------------------------------------------------------------ playback

    @Override
    protected InternalAudioTrack resolvePlayable(AudioTrackInfo info, String accessKey) {
        try {
            ensureAuth();
            String url = null;
            JsonNode r = call("audio.getById", Map.of("audios", accessKey == null ? info.identifier : info.identifier + "_" + accessKey));
            if (r.isArray() && !r.isEmpty()) url = r.get(0).path("url").asText(null);

            if (url == null || url.isBlank()) { // fall back to a search for the same track
                JsonNode s = call("audio.search", Map.of("q", info.author + " " + info.title, "count", "10"));
                for (JsonNode i : s.path("items")) {
                    if ((i.path("owner_id").asText() + "_" + i.path("id").asText()).equals(info.identifier)) {
                        url = i.path("url").asText(null);
                        break;
                    }
                }
                if ((url == null || url.isBlank()) && s.path("items").isArray() && !s.path("items").isEmpty()) {
                    url = s.path("items").get(0).path("url").asText(null);
                }
            }
            if (url != null && !url.isBlank()) {
                url = unmask(url, userId);
                InternalAudioTrack d = direct(url);
                if (d != null) return d;
            }
        } catch (Exception e) {
            log.warn("VK stream resolution failed for {}: {} — mirroring", info.identifier, e.getMessage());
        }
        return mirror(info);
    }

    // ------------------------------------------------------------------ URL unmasking

    private static String b64(String enc) {
        StringBuilder dec = new StringBuilder();
        int e = 0;
        int n = 0;
        for (int i = 0; i < enc.length(); i++) {
            int r = B64.indexOf(enc.charAt(i));
            if (r == -1) continue;
            e = n % 4 != 0 ? 64 * e + r : r;
            if (n++ % 4 != 0) dec.append((char) (255 & (e >> ((-2 * n) & 6))));
        }
        return dec.toString();
    }

    static String unmask(String maskUrl, long vkId) {
        if (!maskUrl.contains("audio_api_unavailable")) return maskUrl;
        try {
            String[] ex = maskUrl.split("\\?extra=", 2);
            if (ex.length < 2) return maskUrl;
            String[] parts = ex[1].split("#");
            if (parts.length < 2) return maskUrl;

            String[] split1 = b64(parts[1]).split(String.valueOf((char) 11));
            char[] arr = b64(parts[0]).toCharArray();
            if (split1.length < 2) return maskUrl;

            int len = arr.length;
            int index = Integer.parseInt(split1[1].trim()) ^ (int) vkId;
            int[] indexes = new int[len];
            for (int n = len - 1; n >= 0; n--) {
                index = ((len * (n + 1)) ^ (index + n)) % len;
                if (index < 0) index += len;
                indexes[n] = index;
            }
            for (int n = 1; n < len; n++) {
                int idx = indexes[len - 1 - n];
                char c = arr[n];
                arr[n] = arr[idx];
                arr[idx] = c;
            }
            return new String(arr);
        } catch (Exception e) {
            return maskUrl;
        }
    }
}
