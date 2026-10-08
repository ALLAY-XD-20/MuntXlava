package dev.pawan.muntxlava;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Bound from application.yml under plugins.muntxlava */
@Component
@ConfigurationProperties(prefix = "plugins.muntxlava")
public class MuntXLavaConfig {

    private String engine = "enable";
    private int searchLimit = 10;
    private int collectionLimit = 100;
    private List<String> providers = new ArrayList<>(List.of("ytsearch:%QUERY%", "ytmsearch:%QUERY%"));

    private final Spotify spotify = new Spotify();
    private final Qobuz qobuz = new Qobuz();
    private final Yandex yandex = new Yandex();
    private final Vk vk = new Vk();
    private final Twitch twitch = new Twitch();
    private final Twitter twitter = new Twitter();

    public String getEngine() { return engine; }
    public void setEngine(String engine) { this.engine = engine; }

    public boolean isEngineEnabled() {
        String e = engine == null ? "enable" : engine.trim().toLowerCase(Locale.ROOT);
        return !(e.equals("disable") || e.equals("disabled") || e.equals("false")
                || e.equals("off") || e.equals("no") || e.equals("0"));
    }

    public int getSearchLimit() { return searchLimit; }
    public void setSearchLimit(int v) { this.searchLimit = Math.max(1, Math.min(v, 50)); }

    public int getCollectionLimit() { return collectionLimit; }
    public void setCollectionLimit(int v) { this.collectionLimit = Math.max(1, Math.min(v, 500)); }

    public List<String> getProviders() { return providers; }
    public void setProviders(List<String> v) {
        this.providers = v == null || v.isEmpty() ? new ArrayList<>(List.of("ytsearch:%QUERY%")) : v;
    }

    public Spotify getSpotify() { return spotify; }
    public Qobuz getQobuz() { return qobuz; }
    public Yandex getYandex() { return yandex; }
    public Vk getVk() { return vk; }
    public Twitch getTwitch() { return twitch; }
    public Twitter getTwitter() { return twitter; }

    /** Settings shared by every source. */
    public abstract static class SourceCfg {
        private boolean enabled;
        private String sourceName;
        private final String defaultName;

        SourceCfg(String defaultName, boolean enabledByDefault) {
            this.defaultName = defaultName;
            this.enabled = enabledByDefault;
        }

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getSourceName() { return sourceName == null || sourceName.isBlank() ? defaultName : sourceName.trim(); }
        public void setSourceName(String sourceName) { this.sourceName = sourceName; }
    }

    public static class Spotify extends SourceCfg {
        private String clientId = "";
        private String clientSecret = "";
        private String market = "US";
        public Spotify() { super("spotify", true); }
        public String getClientId() { return clientId == null ? "" : clientId.trim(); }
        public void setClientId(String v) { this.clientId = v; }
        public String getClientSecret() { return clientSecret == null ? "" : clientSecret.trim(); }
        public void setClientSecret(String v) { this.clientSecret = v; }
        public String getMarket() { return market == null || market.isBlank() ? "US" : market.trim(); }
        public void setMarket(String v) { this.market = v; }
    }

    public static class Qobuz extends SourceCfg {
        private String userToken = "";
        private String formatId = "5";
        public Qobuz() { super("qobuz", true); }
        public String getUserToken() { return userToken == null ? "" : userToken.trim(); }
        public void setUserToken(String v) { this.userToken = v; }
        public String getFormatId() { return formatId == null || formatId.isBlank() ? "5" : formatId.trim(); }
        public void setFormatId(String v) { this.formatId = v; }
    }

    public static class Yandex extends SourceCfg {
        private String accessToken = "";
        public Yandex() { super("yandexmusic", true); }
        public String getAccessToken() { return accessToken == null ? "" : accessToken.trim(); }
        public void setAccessToken(String v) { this.accessToken = v; }
    }

    public static class Vk extends SourceCfg {
        private String userToken = "";
        private String userCookie = "";
        public Vk() { super("vkmusic", true); }
        public String getUserToken() { return userToken == null ? "" : userToken.trim(); }
        public void setUserToken(String v) { this.userToken = v; }
        public String getUserCookie() { return userCookie == null ? "" : userCookie.trim(); }
        public void setUserCookie(String v) { this.userCookie = v; }
    }

    /** Off by default: Lavalink already ships a (live-only) twitch source with the same name. */
    public static class Twitch extends SourceCfg {
        private String clientId = "kimne78kx3ncx6brgo4mv6wki5h1ko";
        public Twitch() { super("twitch", false); }
        public String getClientId() { return clientId == null || clientId.isBlank() ? "kimne78kx3ncx6brgo4mv6wki5h1ko" : clientId.trim(); }
        public void setClientId(String v) { this.clientId = v; }
    }

    public static class Twitter extends SourceCfg {
        public Twitter() { super("twitter", true); }
    }
}
