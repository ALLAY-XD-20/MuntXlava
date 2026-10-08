package dev.pawan.muntxlava;

import com.fasterxml.jackson.databind.JsonNode;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Spotify metadata via the official Web API (client credentials). Audio is mirrored. */
public class SpotifySource extends BaseSource {

    private static final String API = "https://api.spotify.com/v1";
    private static final String SEARCH = "spsearch:";
    private static final Pattern URL = Pattern.compile(
            "https?://(?:open\\.)?spotify\\.com/(?:intl-[a-zA-Z]{2}/)?(track|album|playlist|artist|episode|show)/([a-zA-Z0-9]+)");

    private final MuntXLavaConfig.Spotify sc;
    private final Object tokenLock = new Object();
    private volatile String token;
    private volatile long expiresAt;

    public SpotifySource(MuntXLavaConfig cfg) {
        super(cfg, cfg.getSpotify().getSourceName());
        this.sc = cfg.getSpotify();
    }

    @Override
    protected AudioItem load(String id) throws Exception {
        if (id.startsWith(SEARCH)) return search(id.substring(SEARCH.length()).trim());
        Matcher m = URL.matcher(id);
        if (!m.lookingAt()) return null;
        String type = m.group(1);
        String sid = m.group(2);
        return switch (type) {
            case "track" -> track(sid);
            case "album" -> album(sid);
            case "playlist" -> playlist(sid);
            case "artist" -> artist(sid);
            default -> throw new FriendlyException("Spotify episodes and podcasts are not supported",
                    FriendlyException.Severity.COMMON, null);
        };
    }

    // ------------------------------------------------------------------ auth + http

    private String token() throws Exception {
        synchronized (tokenLock) {
            if (token != null && System.currentTimeMillis() < expiresAt - 60_000) return token;
            String basic = Base64.getEncoder().encodeToString(
                    (sc.getClientId() + ":" + sc.getClientSecret()).getBytes());
            Http.Res res = Http.post("https://accounts.spotify.com/api/token",
                    Map.of("Authorization", "Basic " + basic, "Content-Type", "application/x-www-form-urlencoded"),
                    "grant_type=client_credentials");
            JsonNode body = res.json();
            if (res.status() != 200 || !body.hasNonNull("access_token")) {
                throw new IllegalStateException("Spotify token request failed: HTTP " + res.status());
            }
            token = body.path("access_token").asText();
            expiresAt = System.currentTimeMillis() + body.path("expires_in").asLong(3600) * 1000L;
            return token;
        }
    }

    /** GET a path or absolute URL. Returns null on terminal failure (404, etc). */
    private JsonNode api(String pathOrUrl) throws Exception {
        String url = pathOrUrl.startsWith("http") ? pathOrUrl : API + pathOrUrl;
        for (int attempt = 0; attempt < 3; attempt++) {
            Http.Res res = Http.get(url, Map.of("Authorization", "Bearer " + token(), "Accept", "application/json"));
            if (res.status() == 200) return res.json();
            if (res.status() == 401) {
                token = null;
                continue;
            }
            if (res.status() == 429) {
                long wait = 5;
                try { wait = Long.parseLong(res.header("retry-after")); } catch (Exception ignored) { }
                Thread.sleep(Math.min(wait, 10) * 1000L);
                continue;
            }
            return null;
        }
        return null;
    }

    // ------------------------------------------------------------------ builders

    private AudioTrack build(JsonNode t, String artFallback) {
        if (t == null || !t.hasNonNull("id")) return null; // local files have no id
        if ("episode".equals(t.path("type").asText())) return null;
        String id = t.path("id").asText();
        String art = t.path("album").path("images").path(0).path("url").asText(null);
        if (art == null) art = artFallback;
        String uri = t.path("external_urls").path("spotify").asText("https://open.spotify.com/track/" + id);
        String author = joinNames(t.path("artists"), "name");
        return track(t.path("name").asText("Unknown Title"), author.isEmpty() ? "Unknown" : author,
                t.path("duration_ms").asLong(0), id, false, uri, art,
                t.path("external_ids").path("isrc").asText(null), null);
    }

    // ------------------------------------------------------------------ loaders

    private AudioItem search(String query) throws Exception {
        if (query.isEmpty()) return AudioReference.NO_TRACK;
        JsonNode data = api(Http.url("/search", "q", query, "type", "track",
                "limit", cfg.getSearchLimit(), "market", sc.getMarket()));
        if (data == null) return AudioReference.NO_TRACK;
        List<AudioTrack> tracks = new ArrayList<>();
        for (JsonNode t : data.path("tracks").path("items")) {
            AudioTrack at = build(t, null);
            if (at != null) tracks.add(at);
        }
        return playlist("Spotify search: " + query, tracks, true);
    }

    private AudioItem track(String id) throws Exception {
        JsonNode t = api("/tracks/" + id + "?market=" + sc.getMarket());
        AudioTrack at = build(t, null);
        return at == null ? AudioReference.NO_TRACK : at;
    }

    private AudioItem album(String id) throws Exception {
        JsonNode a = api("/albums/" + id + "?market=" + sc.getMarket());
        if (a == null) return AudioReference.NO_TRACK;
        String art = a.path("images").path(0).path("url").asText(null);
        List<AudioTrack> tracks = new ArrayList<>();
        JsonNode page = a.path("tracks");
        int max = cfg.getCollectionLimit();
        while (page != null && !page.isMissingNode() && tracks.size() < max) {
            for (JsonNode t : page.path("items")) {
                AudioTrack at = build(t, art);
                if (at != null) tracks.add(at);
                if (tracks.size() >= max) break;
            }
            String next = page.path("next").asText(null);
            page = next == null ? null : api(next);
        }
        return playlist(a.path("name").asText("Spotify Album"), tracks, false);
    }

    private AudioItem playlist(String id) throws Exception {
        JsonNode meta = api("/playlists/" + id + "?market=" + sc.getMarket() + "&fields=name");
        String name = meta == null ? "Spotify Playlist" : meta.path("name").asText("Spotify Playlist");
        int max = cfg.getCollectionLimit();

        List<AudioTrack> tracks = new ArrayList<>();
        // /items is the current endpoint; /tracks is the legacy one.
        for (String leaf : new String[]{"items", "tracks"}) {
            String next = "/playlists/" + id + "/" + leaf + "?market=" + sc.getMarket() + "&limit=100";
            while (next != null && tracks.size() < max) {
                JsonNode page = api(next);
                if (page == null) break;
                for (JsonNode it : page.path("items")) {
                    JsonNode node = it.hasNonNull("item") ? it.path("item") : it.path("track");
                    if (it.path("is_local").asBoolean(false)) continue;
                    AudioTrack at = build(node, null);
                    if (at != null) tracks.add(at);
                    if (tracks.size() >= max) break;
                }
                next = page.path("next").asText(null);
            }
            if (!tracks.isEmpty()) break;
        }
        return playlist(name, tracks, false);
    }

    private AudioItem artist(String id) throws Exception {
        JsonNode a = api("/artists/" + id);
        String name = a == null ? "Unknown Artist" : a.path("name").asText("Unknown Artist");
        List<AudioTrack> tracks = new ArrayList<>();
        JsonNode top = api("/artists/" + id + "/top-tracks?market=" + sc.getMarket());
        if (top != null) {
            for (JsonNode t : top.path("tracks")) {
                AudioTrack at = build(t, null);
                if (at != null) tracks.add(at);
            }
        }
        if (tracks.isEmpty() && a != null) { // top-tracks is restricted for newer apps
            JsonNode s = api(Http.url("/search", "q", "artist:\"" + name + "\"", "type", "track",
                    "limit", 20, "market", sc.getMarket()));
            if (s != null) {
                for (JsonNode t : s.path("tracks").path("items")) {
                    AudioTrack at = build(t, null);
                    if (at != null) tracks.add(at);
                }
            }
        }
        return playlist(name + "'s Top Tracks", tracks, false);
    }
}
