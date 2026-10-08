package dev.pawan.muntxlava;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import dev.arbjerg.lavalink.api.AudioPlayerManagerConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * MuntXlava — multi-source plugin for Lavalink v4 (Spotify, Qobuz, Yandex Music,
 * VK Music, Twitch, Twitter/X). Author: Pawan
 *
 * Enable with:  plugins.muntxlava.engine: enable
 */
@Service
public class MuntXLavaPlugin implements AudioPlayerManagerConfiguration {

    private static final Logger log = LoggerFactory.getLogger(MuntXLavaPlugin.class);

    private final MuntXLavaConfig config;

    public MuntXLavaPlugin(MuntXLavaConfig config) {
        this.config = config;
    }

    @Override
    public AudioPlayerManager configure(AudioPlayerManager manager) {
        if (!config.isEngineEnabled()) {
            log.info("MuntXlava engine: disable — no sources registered");
            return manager;
        }

        MuntXLavaConfig.Spotify sp = config.getSpotify();
        if (sp.isEnabled()) {
            if (sp.getClientId().isEmpty() || sp.getClientSecret().isEmpty()) {
                log.warn("MuntXlava: spotify skipped — set plugins.muntxlava.spotify.clientId and clientSecret");
            } else {
                register(manager, new SpotifySource(config), "spsearch:");
            }
        }
        if (config.getQobuz().isEnabled()) register(manager, new QobuzSource(config), "qbsearch: / qbrec:");

        MuntXLavaConfig.Yandex ym = config.getYandex();
        if (ym.isEnabled()) {
            if (ym.getAccessToken().isEmpty()) log.warn("MuntXlava: yandexmusic skipped — set plugins.muntxlava.yandex.accessToken");
            else register(manager, new YandexSource(config), "ymsearch: / ymrec:");
        }

        MuntXLavaConfig.Vk vk = config.getVk();
        if (vk.isEnabled()) {
            if (vk.getUserToken().isEmpty() && vk.getUserCookie().isEmpty()) {
                log.warn("MuntXlava: vkmusic skipped — set plugins.muntxlava.vk.userToken or userCookie");
            } else {
                register(manager, new VkSource(config), "vksearch: / vkrec:");
            }
        }
        if (config.getTwitch().isEnabled()) register(manager, new TwitchSource(config), "(urls only)");
        if (config.getTwitter().isEnabled()) register(manager, new TwitterSource(config), "twsearch:");
        return manager;
    }

    private void register(AudioPlayerManager manager, BaseSource source, String prefixes) {
        source.setPlayerManager(manager);
        manager.registerSourceManager(source);
        log.info("MuntXlava: source '{}' registered ({})", source.getSourceName(), prefixes);
    }
}
