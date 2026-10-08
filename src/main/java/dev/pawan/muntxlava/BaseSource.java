package dev.pawan.muntxlava;

import com.fasterxml.jackson.databind.JsonNode;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.Units;
import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.BasicAudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.InternalAudioTrack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Shared behaviour for every MuntXlava source: loading, mirroring, encoding. */
public abstract class BaseSource implements AudioSourceManager {

    private static final Logger log = LoggerFactory.getLogger(BaseSource.class);
    private static final Pattern FEAT = Pattern.compile("(?i)\\s*\\((?:feat|ft)\\..*?\\)");
    private static final long LOAD_TIMEOUT_SEC = 10;
    private static final long DURATION_TOLERANCE_MS = 15_000;

    protected final MuntXLavaConfig cfg;
    private final String name;
    private volatile AudioPlayerManager playerManager;

    protected BaseSource(MuntXLavaConfig cfg, String name) {
        this.cfg = cfg;
        this.name = name;
    }

    public void setPlayerManager(AudioPlayerManager manager) { this.playerManager = manager; }

    @Override
    public String getSourceName() { return name; }

    /** Returns null when the identifier is not handled by this source. */
    protected abstract AudioItem load(String identifier) throws Exception;

    /** Finds the audio to play. Default: mirror through the configured providers. */
    protected InternalAudioTrack resolvePlayable(AudioTrackInfo info, String extra) throws Exception {
        return mirror(info);
    }

    @Override
    public AudioItem loadItem(AudioPlayerManager manager, AudioReference ref) {
        String id = ref.identifier;
        if (id == null) return null;
        try {
            return load(id);
        } catch (FriendlyException e) {
            throw e;
        } catch (Exception e) {
            log.warn("{} load failed for {}: {}", name, id, e.getMessage());
            throw new FriendlyException(name + " load failed: " + e.getMessage(),
                    FriendlyException.Severity.SUSPICIOUS, e);
        }
    }

    // ------------------------------------------------------------------ track building

    protected AudioTrack track(String title, String author, long length, String id, boolean stream,
                               String uri, String artwork, String isrc, String extra) {
        String identifier = id == null || id.isBlank() ? author + " - " + title : id;
        AudioTrackInfo info = new AudioTrackInfo(title, author, length <= 0 && !stream ? Units.DURATION_MS_UNKNOWN : length,
                identifier, stream, uri, artwork, isrc);
        return new MuntTrack(info, this, extra);
    }

    protected AudioItem playlist(String title, List<AudioTrack> tracks, boolean search) {
        if (tracks.isEmpty()) return AudioReference.NO_TRACK;
        return new BasicAudioPlaylist(title, tracks, null, search);
    }

    // ------------------------------------------------------------------ mirroring

    static List<String> buildQueries(String artist, String fullTitle) {
        String core = FEAT.matcher(fullTitle).replaceFirst("").strip();
        boolean differs = !core.isEmpty() && !core.equals(fullTitle);
        List<String> q = new ArrayList<>();
        if (differs) q.add(artist + " " + core + " official audio");
        q.add(artist + " " + fullTitle + " official audio");
        if (differs) q.add(artist + " " + core);
        q.add(artist + " " + fullTitle);
        return q;
    }

    /** Searches the providers; prefers a result whose duration matches within 15 s. */
    protected InternalAudioTrack mirror(AudioTrackInfo info) {
        List<String> queries = new ArrayList<>();
        if (info.isrc != null && !info.isrc.isBlank()) queries.add("\"" + info.isrc + "\"");
        queries.addAll(buildQueries(info.author == null ? "" : info.author, info.title == null ? "" : info.title));

        boolean knownLength = info.length > 0 && info.length != Units.DURATION_MS_UNKNOWN;
        AudioTrack fallback = null;
        for (String query : queries) {
            for (String provider : cfg.getProviders()) {
                if (!provider.contains("%QUERY%")) continue;
                List<AudioTrack> results = loadOther(provider.replace("%QUERY%", query));
                if (results.isEmpty()) continue;
                if (fallback == null) fallback = results.get(0);
                if (!knownLength) return asInternal(results.get(0));
                for (AudioTrack r : results.subList(0, Math.min(5, results.size()))) {
                    if (Math.abs(r.getDuration() - info.length) <= DURATION_TOLERANCE_MS) return asInternal(r);
                }
            }
        }
        return fallback == null ? null : asInternal(fallback);
    }

    /** Hands a direct media URL (mp3, flac, mp4, m3u8...) to Lavaplayer's HTTP source. */
    protected InternalAudioTrack direct(String url) {
        List<AudioTrack> results = loadOther(url);
        return results.isEmpty() ? null : asInternal(results.get(0));
    }

    private static InternalAudioTrack asInternal(AudioTrack t) {
        AudioTrack clone = t.makeClone();
        return clone instanceof InternalAudioTrack internal ? internal : null;
    }

    protected List<AudioTrack> loadOther(String identifier) {
        AudioPlayerManager pm = playerManager;
        if (pm == null) return List.of();
        CompletableFuture<List<AudioTrack>> future = new CompletableFuture<>();
        pm.loadItem(identifier, new AudioLoadResultHandler() {
            @Override public void trackLoaded(AudioTrack track) { future.complete(List.of(track)); }
            @Override public void playlistLoaded(AudioPlaylist playlist) { future.complete(new ArrayList<>(playlist.getTracks())); }
            @Override public void noMatches() { future.complete(List.of()); }
            @Override public void loadFailed(FriendlyException exception) { future.complete(List.of()); }
        });
        try {
            return future.get(LOAD_TIMEOUT_SEC, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        } catch (Exception e) {
            log.debug("{} secondary load '{}' failed: {}", name, identifier, e.getMessage());
            return List.of();
        }
    }

    // ------------------------------------------------------------------ json helpers

    protected static String text(JsonNode n, String... keys) {
        for (String k : keys) {
            JsonNode v = n.path(k);
            if (v.isTextual() && !v.asText().isBlank()) return v.asText();
            if (v.isNumber()) return v.asText();
        }
        return null;
    }

    protected static String joinNames(JsonNode arr, String field) {
        List<String> out = new ArrayList<>();
        for (JsonNode a : arr) {
            String n = a.path(field).asText("");
            if (!n.isBlank()) out.add(n);
        }
        return String.join(", ", out);
    }

    protected static String snip(JsonNode n) {
        String s = String.valueOf(n);
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }

    // ------------------------------------------------------------------ encoding

    @Override
    public boolean isTrackEncodable(AudioTrack track) { return true; }

    @Override
    public void encodeTrack(AudioTrack track, DataOutput output) throws IOException {
        String extra = track instanceof MuntTrack mt ? mt.extra() : null;
        output.writeUTF(extra == null ? "" : extra);
    }

    @Override
    public AudioTrack decodeTrack(AudioTrackInfo info, DataInput input) throws IOException {
        String extra = input.readUTF();
        return new MuntTrack(info, this, extra.isEmpty() ? null : extra);
    }

    @Override
    public void shutdown() { }
}
