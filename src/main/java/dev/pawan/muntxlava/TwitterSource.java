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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Twitter / X video posts. Uses guest-token GraphQL with the public syndication endpoint as fallback. */
public class TwitterSource extends BaseSource {

    private static final Logger log = LoggerFactory.getLogger(TwitterSource.class);
    private static final String BEARER =
            "AAAAAAAAAAAAAAAAAAAAANRILgAAAAAAnNwIzUejRCOuH5E6I8xnZz4puTs%3D1Zv7ttfk8LF81IUq16cHjhLTvJu4FA33AGWWjCpTnA";
    private static final Pattern URL = Pattern.compile(
            "https?://(?:(?:www|m(?:obile)?)\\.)?(?:twitter|x)\\.com/(?:[^/]+)/status/(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final long TOKEN_TTL_MS = 3L * 60 * 60 * 1000;

    private final Object tokenLock = new Object();
    private volatile String guestToken;
    private volatile long tokenExpiry;

    public TwitterSource(MuntXLavaConfig cfg) {
        super(cfg, cfg.getTwitter().getSourceName());
    }

    /** Normalised tweet media. */
    private record Media(String id, String author, long length, String title, String uri, String art, String directUrl) { }

    @Override
    protected AudioItem load(String id) throws Exception {
        if (id.startsWith("twsearch:")) return search(id.substring(9).trim());
        Matcher m = URL.matcher(id);
        if (!m.lookingAt()) return null;
        Media media = fetch(m.group(1), id);
        return media == null ? AudioReference.NO_TRACK : build(media);
    }

    // ------------------------------------------------------------------ auth + http

    private void ensureGuestToken() throws Exception {
        if (guestToken != null && System.currentTimeMillis() < tokenExpiry) return;
        synchronized (tokenLock) {
            if (guestToken != null && System.currentTimeMillis() < tokenExpiry) return;
            Http.Res res = Http.post("https://api.twitter.com/1.1/guest/activate.json",
                    Map.of("Authorization", "Bearer " + BEARER), null);
            String t = res.json().path("guest_token").asText(null);
            if (res.status() != 200 || t == null) throw new IllegalStateException("guest token activation failed: HTTP " + res.status());
            guestToken = t;
            tokenExpiry = System.currentTimeMillis() + TOKEN_TTL_MS;
        }
    }

    private JsonNode graphql(String operation, ObjectNode variables, ObjectNode features) throws Exception {
        ensureGuestToken();
        String url = "https://twitter.com/i/api/graphql/" + operation
                + "?variables=" + Http.enc(variables.toString()) + "&features=" + Http.enc(features.toString());
        Map<String, String> h = new HashMap<>();
        h.put("Authorization", "Bearer " + BEARER);
        h.put("x-guest-token", guestToken);
        h.put("x-twitter-active-user", "yes");
        h.put("x-twitter-client-language", "en");
        h.put("Referer", "https://twitter.com/");
        return Http.get(url, h).json();
    }

    // ------------------------------------------------------------------ extraction

    private Media fetch(String id, String url) throws Exception {
        try {
            ObjectNode features = Http.JSON.createObjectNode();
            features.put("creator_subscriptions_tweet_preview_api_enabled", true);
            features.put("responsive_web_graphql_timeline_navigation_enabled", true);
            features.put("longform_notetweets_inline_media_enabled", true);
            features.put("tweet_with_visibility_results_prefer_gql_limited_actions_policy_enabled", true);
            ObjectNode vars = Http.JSON.createObjectNode();
            vars.put("tweetId", id).put("withCommunity", false).put("includePromotedContent", false).put("withVoice", true);

            JsonNode root = graphql("2ICDjqPd81tulZcYrtpTuQ/TweetResultByRestId", vars, features);
            Media m = fromGraphql(unwrap(root.path("data").path("tweetResult").path("result")), url);
            if (m != null) return m;
        } catch (Exception e) {
            log.debug("Twitter GraphQL failed for {}: {}", id, e.getMessage());
        }

        Http.Res res = Http.get("https://cdn.syndication.twimg.com/tweet-result?id=" + id
                + "&token=" + syndicationToken(id) + "&lang=en", Map.of("User-Agent", "Googlebot"));
        return res.status() == 200 ? fromSyndication(id, res.json(), url) : null;
    }

    private static JsonNode unwrap(JsonNode result) {
        return "TweetWithVisibilityResults".equals(result.path("__typename").asText()) ? result.path("tweet") : result;
    }

    private Media fromGraphql(JsonNode result, String url) {
        JsonNode legacy = result.path("legacy");
        String id = legacy.path("id_str").asText(null);
        if (id == null) return null;
        for (JsonNode media : legacy.path("extended_entities").path("media")) {
            String type = media.path("type").asText();
            if (!type.equals("video") && !type.equals("animated_gif")) continue;
            JsonNode info = media.path("video_info");
            String direct = bestVariant(info.path("variants"));
            if (direct == null) continue;
            String author = result.path("core").path("user_results").path("result").path("legacy").path("name").asText("Twitter User");
            return new Media(id, author, info.path("duration_millis").asLong(0), title(legacy.path("full_text").asText(null)),
                    url, media.path("media_url_https").asText(null), direct);
        }
        return null;
    }

    private Media fromSyndication(String id, JsonNode payload, String url) {
        JsonNode media = payload.has("video") ? payload.path("video") : payload.path("mediaDetails").path(0);
        String direct = bestVariant(media.path("variants"));
        if (direct == null) return null;
        return new Media(id, payload.path("user").path("name").asText("Twitter User"), media.path("durationMs").asLong(0),
                title(payload.path("text").asText(null)), url, media.path("poster").asText(null), direct);
    }

    /** Highest-bitrate MP4, otherwise the first HLS playlist. */
    private static String bestVariant(JsonNode variants) {
        String best = null;
        long bestBitrate = -1;
        String hls = null;
        for (JsonNode v : variants) {
            String url = text(v, "url", "src");
            String type = text(v, "content_type", "type");
            if (url == null) continue;
            if ("video/mp4".equals(type)) {
                long br = v.hasNonNull("bitrate") ? v.path("bitrate").asLong() : estimate(url);
                if (br > bestBitrate) { bestBitrate = br; best = url; }
            } else if ("application/x-mpegURL".equals(type) && hls == null) {
                hls = url;
            }
        }
        return best != null ? best : hls;
    }

    private static long estimate(String url) {
        Matcher m = Pattern.compile("/(\\d+)x(\\d+)/").matcher(url);
        return m.find() ? Long.parseLong(m.group(1)) * Long.parseLong(m.group(2)) : 0;
    }

    private static String title(String text) {
        if (text == null) return "Twitter Content";
        String v = text.split("https://t\\.co", 2)[0].trim();
        return v.isEmpty() ? "Twitter Content" : v;
    }

    /** Best-effort port of the public syndication token: (id / 1e15 * pi) in base 36, without '0' and '.'. */
    static String syndicationToken(String id) {
        double v = (Double.parseDouble(id) / 1e15) * Math.PI;
        long whole = (long) v;
        double frac = v - whole;
        StringBuilder sb = new StringBuilder(Long.toString(whole, 36));
        for (int i = 0; i < 12 && frac > 0; i++) {
            frac *= 36;
            int digit = (int) frac;
            sb.append(Character.forDigit(digit, 36));
            frac -= digit;
        }
        return sb.toString().replace("0", "");
    }

    // ------------------------------------------------------------------ tracks + search

    private AudioTrack build(Media m) {
        boolean hls = m.directUrl().contains(".m3u8");
        return track(m.title(), m.author(), m.length(), m.id(), hls, m.uri(), m.art(), null, null);
    }

    private AudioItem search(String query) throws Exception {
        if (query.isEmpty()) return AudioReference.NO_TRACK;
        ObjectNode features = Http.JSON.createObjectNode().put("responsive_web_graphql_timeline_navigation_enabled", true);
        ObjectNode vars = Http.JSON.createObjectNode();
        vars.put("rawQuery", query + " filter:videos").put("count", cfg.getSearchLimit())
                .put("querySource", "typed_query").put("product", "Latest");

        JsonNode root = graphql("gk_S_vsh_PyInisUnZun6Q/SearchTimeline", vars, features);
        List<AudioTrack> tracks = new ArrayList<>();
        for (JsonNode ins : root.path("data").path("search_by_raw_query").path("search_timeline").path("timeline").path("instructions")) {
            if (!"TimelineAddEntries".equals(ins.path("type").asText())) continue;
            for (JsonNode entry : ins.path("entries")) {
                JsonNode result = unwrap(entry.path("content").path("itemContent").path("tweet_results").path("result"));
                String id = result.path("legacy").path("id_str").asText(null);
                if (id == null) continue;
                Media m = fromGraphql(result, "https://twitter.com/i/status/" + id);
                if (m != null) tracks.add(build(m));
                if (tracks.size() >= cfg.getSearchLimit()) break;
            }
        }
        return playlist("Twitter search: " + query, tracks, true);
    }

    // ------------------------------------------------------------------ playback

    @Override
    protected InternalAudioTrack resolvePlayable(AudioTrackInfo info, String extra) throws Exception {
        Media m = fetch(info.identifier, info.uri);
        if (m == null) return null;
        return direct(m.directUrl());
    }
}
