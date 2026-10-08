package dev.pawan.muntxlava;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.InternalAudioTrack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Twitch clips, VODs and live channels through Twitch's GraphQL + Usher APIs. */
public class TwitchSource extends BaseSource {

    private static final Logger log = LoggerFactory.getLogger(TwitchSource.class);
    private static final String GQL = "https://gql.twitch.tv/gql";
    private static final String USHER = "https://usher.ttvnw.net";
    private static final Pattern URL = Pattern.compile(
            "https?://(?:www\\.|go\\.|m\\.)?twitch\\.tv/(?:[\\w_]+/clip/([\\w%-_]+)|videos/(\\d+)|([\\w_]+))",
            Pattern.CASE_INSENSITIVE);

    private static final String HASH_CLIP = "0d6d8d951d3b5305a3f2a0f2661b8a6a6d25dc042b155d8df8586905f0a0f435";
    private static final String HASH_VOD = "226edb3e692509f727fd56821f5653c05740242c82b0388883e0c0e75dcbf687";
    private static final String HASH_STREAM = "1c719a40e481453e5c48d9bb585d971b8b372f8ebb105b17076722264dfa5b3e";

    private final MuntXLavaConfig.Twitch tc;

    public TwitchSource(MuntXLavaConfig cfg) {
        super(cfg, cfg.getTwitch().getSourceName());
        this.tc = cfg.getTwitch();
    }

    @Override
    protected AudioItem load(String id) throws Exception {
        Matcher m = URL.matcher(id);
        if (!m.lookingAt()) return null;
        if (m.group(1) != null) return clip(m.group(1), id);
        if (m.group(2) != null) return vod(m.group(2), id);
        return channel(m.group(3), id);
    }

    // ------------------------------------------------------------------ graphql

    private JsonNode gql(ObjectNode payload) throws Exception {
        Http.Res res = Http.postJson(GQL, Map.of("Client-ID", tc.getClientId()), payload);
        if (res.status() != 200) throw new IllegalStateException("Twitch GraphQL HTTP " + res.status());
        return res.json();
    }

    private ObjectNode op(String name, String hash, ObjectNode vars, String query) {
        ObjectNode n = Http.JSON.createObjectNode();
        n.put("operationName", name);
        if (query != null) n.put("query", query);
        n.set("variables", vars);
        if (hash != null) {
            n.putObject("extensions").putObject("persistedQuery").put("version", 1).put("sha256Hash", hash);
        }
        return n;
    }

    // ------------------------------------------------------------------ loaders

    private JsonNode clipMeta(String slug) throws Exception {
        String query = "query ClipsView($slug: ID!) { clip(slug: $slug) { id slug title broadcaster { id displayName login }"
                + " videoQualities { quality sourceURL } thumbnailURL durationSeconds } }";
        ObjectNode vars = Http.JSON.createObjectNode().put("slug", slug);
        return gql(op("ClipsView", HASH_CLIP, vars, query)).path("data").path("clip");
    }

    private AudioItem clip(String slug, String url) throws Exception {
        JsonNode c = clipMeta(slug);
        if (!c.hasNonNull("slug")) throw new FriendlyException("Clip not found", FriendlyException.Severity.COMMON, null);
        return track(c.path("title").asText("Twitch Clip"), c.path("broadcaster").path("displayName").asText("Unknown"),
                (long) (c.path("durationSeconds").asDouble(0) * 1000), c.path("slug").asText(), false, url,
                c.path("thumbnailURL").asText(null), null, null);
    }

    private AudioItem vod(String id, String url) throws Exception {
        ObjectNode vars = Http.JSON.createObjectNode().put("videoID", id).put("channelLogin", "");
        JsonNode v = gql(op("VideoMetadata", HASH_VOD, vars, null)).path("data").path("video");
        if (!v.hasNonNull("title") && !v.hasNonNull("owner")) {
            throw new FriendlyException("VOD not found", FriendlyException.Severity.COMMON, null);
        }
        String art = v.path("previewThumbnailURL").asText(null);
        if (art != null) art = art.replace("{width}", "640").replace("{height}", "360");
        return track(v.path("title").asText("Twitch VOD"), v.path("owner").path("displayName").asText("Unknown"),
                (long) (v.path("lengthSeconds").asDouble(0) * 1000), id, false, url, art, null, null);
    }

    private AudioItem channel(String name, String url) throws Exception {
        String login = name.toLowerCase();
        ObjectNode vars = Http.JSON.createObjectNode().put("channelLogin", login);
        JsonNode user = gql(op("StreamMetadata", HASH_STREAM, vars, null)).path("data").path("user");
        if (!"live".equals(user.path("stream").path("type").asText())) {
            throw new FriendlyException("Channel is not currently live", FriendlyException.Severity.COMMON, null);
        }
        return track(user.path("lastBroadcast").path("title").asText("Twitch Live Stream"), name, 0, login, true,
                "https://www.twitch.tv/" + login,
                "https://static-cdn.jtvnw.net/previews-ttv/live_user_" + login + "-640x360.jpg", null, null);
    }

    // ------------------------------------------------------------------ playback

    @Override
    protected InternalAudioTrack resolvePlayable(AudioTrackInfo info, String extra) throws Exception {
        Matcher m = URL.matcher(info.uri == null ? "" : info.uri);
        if (!m.lookingAt()) return null;
        String url;
        if (m.group(1) != null) url = clipUrl(m.group(1));
        else if (m.group(2) != null) url = vodUrl(m.group(2));
        else url = liveUrl(m.group(3).toLowerCase());
        log.debug("Twitch resolved stream URL for {}", info.identifier);
        return direct(url);
    }

    private JsonNode token(String name, String query, ObjectNode vars, String field) throws Exception {
        return gql(op(name, null, vars, query)).path("data").path(field);
    }

    private String liveUrl(String login) throws Exception {
        String query = "query PlaybackAccessToken_Template($login: String!, $isLive: Boolean!, $vodID: ID!, $isVod: Boolean!, $playerType: String!, $platform: String!) {"
                + " streamPlaybackAccessToken(channelName: $login, params: {platform: $platform, playerBackend: \"mediaplayer\", playerType: $playerType}) @include(if: $isLive) {"
                + " value signature authorization { isForbidden forbiddenReasonCode } __typename } }";
        ObjectNode vars = Http.JSON.createObjectNode();
        vars.put("isLive", true).put("login", login).put("isVod", false).put("vodID", "")
                .put("playerType", "site").put("platform", "web");
        JsonNode t = token("PlaybackAccessToken_Template", query, vars, "streamPlaybackAccessToken");
        if (!t.hasNonNull("value")) throw new IllegalStateException("no live playback token");

        String hls = Http.url(USHER + "/api/channel/hls/" + login + ".m3u8", "player_type", "site",
                "token", t.path("value").asText(), "sig", t.path("signature").asText(),
                "allow_source", "true", "allow_audio_only", "true");
        Http.Res res = Http.get(hls, null);
        if (res.status() != 200 || res.body() == null) throw new IllegalStateException("HLS master HTTP " + res.status());
        String variant = pickVariant(res.body());
        if (variant == null) throw new IllegalStateException("no compatible HLS variant");
        return variant;
    }

    private String vodUrl(String id) throws Exception {
        String query = "query PlaybackAccessToken_Template($isVod: Boolean!, $vodID: ID!, $playerType: String!, $platform: String!) {"
                + " videoPlaybackAccessToken(id: $vodID, params: {platform: $platform, playerBackend: \"mediaplayer\", playerType: $playerType}) @include(if: $isVod) { value signature } }";
        ObjectNode vars = Http.JSON.createObjectNode();
        vars.put("isVod", true).put("vodID", id).put("playerType", "site").put("platform", "web");
        JsonNode t = token("PlaybackAccessToken_Template", query, vars, "videoPlaybackAccessToken");
        if (!t.hasNonNull("value")) throw new IllegalStateException("no VOD playback token");
        return Http.url(USHER + "/vod/" + id + ".m3u8", "player_type", "html5", "token", t.path("value").asText(),
                "sig", t.path("signature").asText(), "allow_source", "true", "allow_audio_only", "true");
    }

    private String clipUrl(String slug) throws Exception {
        JsonNode meta = clipMeta(slug);
        List<JsonNode> qualities = new ArrayList<>();
        meta.path("videoQualities").forEach(qualities::add);
        if (qualities.isEmpty()) throw new IllegalStateException("clip has no playable qualities");
        qualities.sort(Comparator.comparingInt((JsonNode q) -> parseInt(q.path("quality").asText())).reversed());

        String query = "query ClipAccessToken($slug: ID!, $params: PlaybackAccessTokenParams!) {"
                + " clip(slug: $slug) { playbackAccessToken(params: $params) { value signature } } }";
        ObjectNode vars = Http.JSON.createObjectNode().put("slug", slug);
        vars.putObject("params").put("platform", "web").put("playerBackend", "mediaplayer").put("playerType", "embed");
        JsonNode t = gql(op("ClipAccessToken", null, vars, query)).path("data").path("clip").path("playbackAccessToken");
        if (!t.hasNonNull("value")) throw new IllegalStateException("no clip playback token");
        return Http.url(qualities.get(0).path("sourceURL").asText(), "token", t.path("value").asText(), "sig", t.path("signature").asText());
    }

    private static int parseInt(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; }
    }

    /** Prefers the audio_only rendition; otherwise the highest-bandwidth variant. */
    static String pickVariant(String master) {
        String[] lines = master.split("\n");
        String best = null;
        long bestBw = -1;
        String audioOnly = null;
        Pattern bw = Pattern.compile("BANDWIDTH=(\\d+)");
        for (int i = 0; i < lines.length - 1; i++) {
            String line = lines[i].trim();
            if (!line.startsWith("#EXT-X-STREAM-INF:")) continue;
            String next = lines[i + 1].trim();
            if (next.isEmpty() || next.startsWith("#")) continue;
            if (line.contains("VIDEO=\"audio_only\"") && audioOnly == null) audioOnly = next;
            Matcher m = bw.matcher(line);
            long b = m.find() ? Long.parseLong(m.group(1)) : 0;
            if (b > bestBw) {
                bestBw = b;
                best = next;
            }
        }
        return audioOnly != null ? audioOnly : best;
    }
}
