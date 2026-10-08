package dev.pawan.muntxlava;

import com.fasterxml.jackson.databind.JsonNode;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.InternalAudioTrack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Yandex Music (needs an OAuth access token). Plays the real MP3; mirrors on failure. */
public class YandexSource extends BaseSource {

    private static final Logger log = LoggerFactory.getLogger(YandexSource.class);
    private static final String API = "https://api.music.yandex.net";
    private static final String SIGN_SECRET = "XGRlBW9FXlekgbPrRHuSiA";

    private static final Pattern URL = Pattern.compile(
            "^(?:https?://)?music\\.yandex\\.(?<domain>ru|com|kz|by)/(?<type1>artist|album|track)/(?<id1>\\d+)(?:/(?<type2>track)/(?<id2>\\d+))?/?(?:[?#].*)?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PLAYLIST = Pattern.compile(
            "^(?:https?://)?music\\.yandex\\.(?<domain>ru|com|kz|by)/users/(?<user>[0-9A-Za-z@.-]+)/playlists/(?<id>\\d+)/?(?:[?#].*)?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PLAYLIST_UUID = Pattern.compile(
            "^(?:https?://)?music\\.yandex\\.(?<domain>ru|com|kz|by)/playlists/(?<uuid>[0-9A-Za-z.-]+)/?(?:[?#].*)?$",
            Pattern.CASE_INSENSITIVE);

    private final MuntXLavaConfig.Yandex yc;

    public YandexSource(MuntXLavaConfig cfg) {
        super(cfg, cfg.getYandex().getSourceName());
        this.yc = cfg.getYandex();
    }

    @Override
    protected AudioItem load(String id) throws Exception {
        if (id.startsWith("ymsearch:")) return search(id.substring(9).trim());
        if (id.startsWith("ymrec:")) return recommendations(id.substring(6).trim());

        Matcher m = URL.matcher(id);
        if (m.matches()) {
            String domain = m.group("domain").toLowerCase();
            String type = m.group("type1").toLowerCase();
            if (type.equals("album") && m.group("id2") != null) return singleTrack(m.group("id2"), domain);
            return switch (type) {
                case "album" -> album(m.group("id1"), domain);
                case "artist" -> artist(m.group("id1"), domain);
                default -> singleTrack(m.group("id1"), domain);
            };
        }
        m = PLAYLIST.matcher(id);
        if (m.matches()) {
            return playlist("/users/" + m.group("user") + "/playlists/" + m.group("id"), m.group("domain").toLowerCase());
        }
        m = PLAYLIST_UUID.matcher(id);
        if (m.matches()) return playlist("/playlist/" + m.group("uuid"), m.group("domain").toLowerCase());
        return null;
    }

    // ------------------------------------------------------------------ http

    private JsonNode api(String path, String... kv) throws Exception {
        Map<String, String> h = new HashMap<>();
        h.put("Accept", "application/json");
        h.put("Authorization", "OAuth " + yc.getAccessToken());
        h.put("User-Agent", "Yandex-Music-API");
        h.put("X-Yandex-Music-Client", "YandexMusicAndroid/24023621");
        Object[] args = (Object[]) kv;
        Http.Res res = Http.get(Http.url(API + path, args), h);
        if (res.status() != 200) throw new IllegalStateException("Yandex API returned HTTP " + res.status() + " for " + path);
        return res.json();
    }

    // ------------------------------------------------------------------ builders

    private static String cover(JsonNode n) {
        String uri = text(n, "ogImage", "coverUri");
        if (uri == null) uri = text(n.path("cover"), "uri");
        if (uri == null && n.path("cover").path("itemsUri").isArray() && !n.path("cover").path("itemsUri").isEmpty()) {
            uri = n.path("cover").path("itemsUri").get(0).asText(null);
        }
        return uri == null ? null : "https://" + uri.replace("%%", "400x400");
    }

    private AudioTrack build(JsonNode json, String domain) {
        if (json == null || !json.hasNonNull("id")) return null;
        if (json.has("available") && !json.path("available").asBoolean(true)) return null;
        String artist = joinNames(json.path("artists"), "name");
        JsonNode album = json.path("albums").path(0);
        if ("PODCASTS".equals(json.path("major").path("name").asText()) && album.hasNonNull("title")) {
            artist = album.path("title").asText();
        }
        String id = json.path("id").asText();
        return track(json.path("title").asText("Unknown Title"), artist.isEmpty() ? "Unknown Artist" : artist,
                json.path("durationMs").asLong(0), id, false, "https://music.yandex." + domain + "/track/" + id,
                cover(json), json.path("isrc").asText(null), null);
    }

    private List<AudioTrack> buildAll(Iterable<JsonNode> list, String domain, int max) {
        List<AudioTrack> out = new ArrayList<>();
        for (JsonNode item : list) {
            JsonNode node = item.has("track") ? item.path("track") : item;
            AudioTrack t = build(node, domain);
            if (t != null) out.add(t);
            if (out.size() >= max) break;
        }
        return out;
    }

    // ------------------------------------------------------------------ loaders

    private AudioItem search(String query) throws Exception {
        if (query.isEmpty()) return AudioReference.NO_TRACK;
        JsonNode data = api("/search", "text", query, "type", "all", "page", "0").path("result");
        List<AudioTrack> tracks = buildAll(data.path("tracks").path("results"), "com", cfg.getSearchLimit());
        return playlist("Yandex Music search: " + query, tracks, true);
    }

    private AudioItem singleTrack(String id, String domain) throws Exception {
        JsonNode data = api("/tracks/" + id);
        AudioTrack t = build(data.path("result").path(0), domain);
        if (t == null) throw new FriendlyException("Yandex track is unavailable", FriendlyException.Severity.COMMON, null);
        return t;
    }

    private AudioItem album(String id, String domain) throws Exception {
        JsonNode album = api("/albums/" + id + "/with-tracks").path("result");
        List<AudioTrack> tracks = new ArrayList<>();
        for (JsonNode volume : album.path("volumes")) {
            tracks.addAll(buildAll(volume, domain, cfg.getCollectionLimit() - tracks.size()));
            if (tracks.size() >= cfg.getCollectionLimit()) break;
        }
        return playlist(album.path("title").asText("Yandex Music Album"), tracks, false);
    }

    private AudioItem artist(String id, String domain) throws Exception {
        JsonNode data = api("/artists/" + id + "/tracks", "page-size", String.valueOf(Math.min(cfg.getCollectionLimit(), 50)));
        List<AudioTrack> tracks = buildAll(data.path("result").path("tracks"), domain, cfg.getCollectionLimit());
        String name = "Unknown Artist";
        try {
            name = api("/artists/" + id).path("result").path("artist").path("name").asText(name);
        } catch (Exception ignored) { }
        return playlist(name + "'s Top Tracks", tracks, false);
    }

    private AudioItem playlist(String path, String domain) throws Exception {
        JsonNode result = api(path, "rich-tracks", "true").path("result");
        List<AudioTrack> tracks = buildAll(result.path("tracks"), domain, cfg.getCollectionLimit());
        String owner = result.path("owner").path("name").asText(result.path("owner").path("login").asText("Unknown"));
        String title = "3".equals(result.path("kind").asText()) ? owner + "'s Liked Songs"
                : result.path("title").asText("Yandex Music Playlist");
        return playlist(title, tracks, false);
    }

    private AudioItem recommendations(String query) throws Exception {
        String trackId = query;
        if (!query.matches("\\d+")) {
            AudioItem found = search(query);
            if (found instanceof com.sedmelluq.discord.lavaplayer.track.AudioPlaylist pl && !pl.getTracks().isEmpty()) {
                trackId = pl.getTracks().get(0).getIdentifier();
            } else {
                return AudioReference.NO_TRACK;
            }
        }
        JsonNode data = api("/tracks/" + trackId + "/similar");
        JsonNode similar = data.path("result").path("similarTracks");
        if (!similar.isArray()) similar = data.path("similarTracks");
        return playlist("Yandex Music Recommendations", buildAll(similar, "com", 20), false);
    }

    // ------------------------------------------------------------------ playback

    @Override
    protected InternalAudioTrack resolvePlayable(AudioTrackInfo info, String extra) {
        try {
            String url = downloadUrl(info.identifier);
            InternalAudioTrack d = direct(url);
            if (d != null) return d;
        } catch (Exception e) {
            log.warn("Yandex download URL failed for {}: {} — mirroring", info.identifier, e.getMessage());
        }
        return mirror(info);
    }

    private String downloadUrl(String trackId) throws Exception {
        JsonNode results = api("/tracks/" + trackId + "/download-info").path("result");
        JsonNode best = null;
        for (JsonNode r : results) {
            if (!"mp3".equals(r.path("codec").asText())) continue;
            if (best == null || r.path("bitrateInKbps").asInt() > best.path("bitrateInKbps").asInt()) best = r;
        }
        if (best == null || !best.hasNonNull("downloadInfoUrl")) {
            throw new IllegalStateException("no MP3 source for track " + trackId);
        }
        Http.Res xmlRes = Http.get(best.path("downloadInfoUrl").asText(), Map.of("Authorization", "OAuth " + yc.getAccessToken()));
        if (xmlRes.status() != 200) throw new IllegalStateException("download-info XML HTTP " + xmlRes.status());
        String xml = xmlRes.body();
        String host = tag(xml, "host"), path = tag(xml, "path"), ts = tag(xml, "ts"), s = tag(xml, "s");
        if (host == null || path == null || ts == null || s == null) throw new IllegalStateException("malformed download-info XML");
        return "https://" + host + "/get-mp3/" + QobuzSource.md5(SIGN_SECRET + path + s) + "/" + ts + path;
    }

    private static String tag(String xml, String tag) {
        Matcher m = Pattern.compile("<" + tag + ">([^<]+)</" + tag + ">").matcher(xml);
        return m.find() ? m.group(1) : null;
    }
}
