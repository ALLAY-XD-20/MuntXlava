package dev.pawan.muntxlava;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.InternalAudioTrack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Qobuz. App id/secret are scraped from the web player bundle. With a userToken the real
 * stream is used; otherwise (or on a sample/failure) the track is mirrored.
 */
public class QobuzSource extends BaseSource {

    private static final Logger log = LoggerFactory.getLogger(QobuzSource.class);
    private static final String API = "https://www.qobuz.com/api.json/0.2";
    private static final String WEB = "https://play.qobuz.com";
    private static final Pattern URL = Pattern.compile(
            "https?://(?:www\\.|play\\.|open\\.)?qobuz\\.com/(?:[a-z]{2}-[a-z]{2}/)?(track|album|playlist|artist)/(?:[^/?#]+/)*([a-zA-Z0-9]+)(?:[?#].*)?$");

    private final MuntXLavaConfig.Qobuz qc;
    private final Object initLock = new Object();
    private volatile String appId;
    private volatile String appSecret;
    private volatile long retryAt;

    public QobuzSource(MuntXLavaConfig cfg) {
        super(cfg, cfg.getQobuz().getSourceName());
        this.qc = cfg.getQobuz();
    }

    @Override
    protected AudioItem load(String id) throws Exception {
        if (id.startsWith("qbsearch:")) return search(id.substring(9).trim());
        if (id.startsWith("qbrec:")) return recommendations(id.substring(6).trim());
        Matcher m = URL.matcher(id);
        if (!m.lookingAt()) return null;
        String sid = m.group(2);
        return switch (m.group(1)) {
            case "track" -> single(sid);
            case "album" -> album(sid);
            case "playlist" -> collection("/playlist/get", "playlist_id", sid, "name", "Qobuz Playlist");
            default -> collection("/artist/get", "artist_id", sid, "name", "Qobuz Artist");
        };
    }

    // ------------------------------------------------------------------ credentials

    private boolean ensureCredentials() {
        if (appId != null) return true;
        synchronized (initLock) {
            if (appId != null) return true;
            if (System.currentTimeMillis() < retryAt) return false;
            try {
                Http.Res login = Http.get(WEB + "/login", null);
                Matcher bm = Pattern.compile("<script src=\"(/resources/\\d+\\.\\d+\\.\\d+-[a-z]\\d{3}/bundle\\.js)\"")
                        .matcher(login.body() == null ? "" : login.body());
                if (!bm.find()) throw new IllegalStateException("bundle.js not found");
                String bundle = Http.get(WEB + bm.group(1), null).body();
                if (bundle == null) throw new IllegalStateException("empty bundle.js");

                Matcher idm = Pattern.compile("production:\\{api:\\{appId:\"(.*?)\"").matcher(bundle);
                String id = idm.find() ? idm.group(1) : null;
                String secret = extractSecret(bundle);
                if (id == null || secret == null) throw new IllegalStateException("appId/appSecret not found");
                appSecret = secret;
                appId = id;
                log.info("Qobuz initialised with appId {}", id);
                return true;
            } catch (Exception e) {
                log.error("Qobuz init failed: {}", e.getMessage());
                retryAt = System.currentTimeMillis() + 5 * 60_000L;
                return false;
            }
        }
    }

    private static String extractSecret(String content) {
        Matcher seedM = Pattern.compile("\\):[a-z]\\.initialSeed\\(\"(.*?)\",window\\.utimezone\\.(.*?)\\)").matcher(content);
        if (!seedM.find()) return null;
        String seed = seedM.group(1);
        String tz = seedM.group(2);
        tz = tz.substring(0, 1).toUpperCase() + tz.substring(1).toLowerCase();

        Matcher ie = Pattern.compile("timezones:\\[.*?name:.*?/" + Pattern.quote(tz)
                + "\",info:\"(?<info>.*?)\",extras:\"(?<extras>.*?)\"").matcher(content);
        if (!ie.find()) return null;
        String joined = seed + ie.group("info") + ie.group("extras");
        if (joined.length() <= 44) return null;
        String encoded = joined.substring(0, joined.length() - 44);
        return new String(Base64.getMimeDecoder().decode(encoded), StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ http

    private JsonNode api(String path, Object... kv) throws Exception {
        if (!ensureCredentials()) return null;
        Map<String, String> h = new HashMap<>();
        h.put("x-app-id", appId);
        if (!qc.getUserToken().isEmpty()) h.put("x-user-auth-token", qc.getUserToken());
        Http.Res res = Http.get(Http.url(API + path, kv), h);
        if (res.status() != 200) {
            log.debug("Qobuz API error {} on {}: {}", res.status(), path, res.body());
            return null;
        }
        return res.json();
    }

    // ------------------------------------------------------------------ builders

    private AudioTrack build(JsonNode i, JsonNode albumOverride) {
        if (!i.hasNonNull("id")) return null;
        String id = i.path("id").asText();
        JsonNode album = albumOverride != null ? albumOverride : i.path("album");
        String author = text(i.path("performer"), "name");
        if (author == null) author = text(i.path("artist"), "name");
        if (author == null) author = text(album.path("artist"), "name");
        String art = text(album.path("image"), "large", "small");
        String title = i.path("title").asText("Unknown Title");
        return track(title, author == null ? "Unknown Artist" : author, i.path("duration").asLong(0) * 1000L,
                id, false, "https://open.qobuz.com/track/" + id, art, i.path("isrc").asText(null), null);
    }

    private List<AudioTrack> buildAll(JsonNode items, JsonNode albumOverride, int max) {
        List<AudioTrack> out = new ArrayList<>();
        for (JsonNode i : items) {
            AudioTrack t = build(i, albumOverride);
            if (t != null) out.add(t);
            if (out.size() >= max) break;
        }
        return out;
    }

    // ------------------------------------------------------------------ loaders

    private AudioItem search(String query) throws Exception {
        if (query.isEmpty()) return AudioReference.NO_TRACK;
        JsonNode data = api("/catalog/search", "query", query, "limit", cfg.getSearchLimit(), "type", "tracks");
        if (data == null) return AudioReference.NO_TRACK;
        return playlist("Qobuz search: " + query, buildAll(data.path("tracks").path("items"), null, cfg.getSearchLimit()), true);
    }

    private AudioItem single(String id) throws Exception {
        JsonNode data = api("/track/get", "track_id", id);
        AudioTrack t = data == null ? null : build(data, null);
        return t == null ? AudioReference.NO_TRACK : t;
    }

    private AudioItem album(String id) throws Exception {
        int max = cfg.getCollectionLimit();
        JsonNode data = api("/album/get", "album_id", id, "limit", Math.min(max, 50));
        if (data == null || !data.has("tracks")) return AudioReference.NO_TRACK;
        List<AudioTrack> tracks = new ArrayList<>(buildAll(data.path("tracks").path("items"), data, max));
        long total = Math.min(data.path("tracks").path("total").asLong(tracks.size()), max);
        while (tracks.size() < total) {
            JsonNode more = api("/album/get", "album_id", id, "limit", 50, "offset", tracks.size());
            List<AudioTrack> batch = more == null ? List.of() : buildAll(more.path("tracks").path("items"), data, max);
            if (batch.isEmpty()) break;
            tracks.addAll(batch);
        }
        return playlist(data.path("title").asText("Qobuz Album"), tracks.subList(0, Math.min(tracks.size(), max)), false);
    }

    /** Playlist / artist: both expose a paged `tracks` block when extra=tracks is set. */
    private AudioItem collection(String path, String idKey, String id, String nameKey, String fallback) throws Exception {
        int max = cfg.getCollectionLimit();
        JsonNode data = api(path, idKey, id, "extra", "tracks", "limit", Math.min(max, 50));
        if (data == null || !data.has("tracks")) return AudioReference.NO_TRACK;
        List<AudioTrack> tracks = new ArrayList<>(buildAll(data.path("tracks").path("items"), null, max));
        long total = Math.min(data.path("tracks").path("total").asLong(tracks.size()), max);
        while (tracks.size() < total) {
            JsonNode more = api(path, idKey, id, "extra", "tracks", "limit", 50, "offset", tracks.size());
            List<AudioTrack> batch = more == null ? List.of() : buildAll(more.path("tracks").path("items"), null, max);
            if (batch.isEmpty()) break;
            tracks.addAll(batch);
        }
        String name = data.path(nameKey).asText(fallback);
        if (path.startsWith("/artist")) name += "'s Top Tracks";
        return playlist(name, tracks.subList(0, Math.min(tracks.size(), max)), false);
    }

    private AudioItem recommendations(String trackId) throws Exception {
        JsonNode t = api("/track/get", "track_id", trackId);
        if (t == null) return AudioReference.NO_TRACK;
        long artistId = t.path("performer").path("id").asLong(0);
        if (artistId == 0) return AudioReference.NO_TRACK;
        if (!ensureCredentials()) return AudioReference.NO_TRACK;

        ObjectNode body = Http.JSON.createObjectNode();
        body.put("limit", 20);
        body.putArray("listened_tracks_ids").add(Long.parseLong(trackId));
        ObjectNode seed = body.putArray("track_to_analyse").addObject();
        seed.put("track_id", Long.parseLong(trackId));
        seed.put("artist_id", artistId);

        Map<String, String> h = new HashMap<>();
        h.put("x-app-id", appId);
        if (!qc.getUserToken().isEmpty()) h.put("x-user-auth-token", qc.getUserToken());
        Http.Res res = Http.postJson(API + "/dynamic/suggest", h, body);
        if (res.status() != 200) return AudioReference.NO_TRACK;
        return playlist("Qobuz Recommendations", buildAll(res.json().path("tracks").path("items"), null, 20), false);
    }

    // ------------------------------------------------------------------ playback

    @Override
    protected InternalAudioTrack resolvePlayable(AudioTrackInfo info, String extra) {
        if (!qc.getUserToken().isEmpty() && ensureCredentials()) {
            try {
                long ts = System.currentTimeMillis() / 1000;
                String fmt = qc.getFormatId();
                String sigData = "trackgetFileUrlformat_id" + fmt + "intentstreamtrack_id" + info.identifier + ts + appSecret;
                String sig = md5(sigData);
                JsonNode data = api("/track/getFileUrl", "request_ts", ts, "request_sig", sig,
                        "track_id", info.identifier, "format_id", fmt, "intent", "stream");
                String url = data == null ? null : data.path("url").asText(null);
                boolean sample = data != null && "true".equalsIgnoreCase(data.path("sample").asText("false"));
                if (url != null && !sample) {
                    InternalAudioTrack d = direct(url);
                    if (d != null) return d;
                }
                log.debug("Qobuz direct stream unavailable (sample={}), mirroring", sample);
            } catch (Exception e) {
                log.warn("Qobuz direct stream failed: {}", e.getMessage());
            }
        }
        return mirror(info);
    }

    static String md5(String s) throws Exception {
        byte[] d = MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
