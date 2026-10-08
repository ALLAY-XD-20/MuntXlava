# MuntXlava

Multi-source Lavalink v4 plugin. Author: **Pawan**
Ported from NodeLink's Spotify, Qobuz, Yandex Music, VK Music, Twitch and Twitter/X sources.
Same layout as PandoraXlava (Gradle + `dev.arbjerg.lavalink.gradle-plugin`, Java 17).

## Install (JitPack)

```yaml
lavalink:
  plugins:
    - dependency: "com.github.ALLAY-XD-20:MuntXlava:1.0.0"
      repository: "https://jitpack.io"
```

## Enable

```yaml
plugins:
  muntxlava:
    engine: enable
    spotify: { clientId: "...", clientSecret: "..." }
```

See `application.yml` for every option.

## Sources

| Source | Search prefix | URLs | Credentials | Audio |
| --- | --- | --- | --- | --- |
| Spotify | `spsearch:` | track, album, playlist, artist | clientId + clientSecret (required) | mirrored |
| Qobuz | `qbsearch:`, `qbrec:<trackId>` | track, album, playlist, artist | optional `userToken` | real stream with token, else mirrored |
| Yandex Music | `ymsearch:`, `ymrec:<id or query>` | track, album, artist, playlist | `accessToken` (required) | real MP3, mirrored on failure |
| VK Music | `vksearch:`, `vkrec:<id or query>` | audio, playlist/album, audios | `userToken` or `userCookie` | real stream, mirrored on failure |
| Twitch | – | clip, VOD, live channel | none | direct (HLS / MP4) |
| Twitter / X | `twsearch:` | status with video | none | direct (MP4 / HLS) |

A source without its required credentials is skipped with a warning at startup.
Source names (`spotify`, `qobuz`, `yandexmusic`, `vkmusic`, `twitch`, `twitter`) can be changed with `sourceName`.

## Mirroring

For mirrored tracks the plugin searches the `providers` for the ISRC, then `artist title official audio`, then
`artist title` (also without a `(feat. ...)` suffix). A result within 15 s of the original duration is preferred.

## Conflicts

- `twitch` is **disabled by default**: Lavalink ships a live-only `twitch` source with the same name.
  To use this one (clips + VODs), set `lavalink.server.sources.twitch: false` and `plugins.muntxlava.twitch.enabled: true`.
- Don't run `spotify` here together with LavaSrc's Spotify unless you rename one with `sourceName`.

## Differences from NodeLink

- Spotify uses the official Web API only (no anonymous/mobile web-player tokens, no Canvas, no `sprec:`).
- VK has no HTML-scraping fallback and no artist URLs.
- Yandex has no Song.link fallback or metadata enrichment.
- Direct HLS streams (VK, Twitch, Twitter) are handed to Lavaplayer's HTTP source. If your Lavalink build
  can't play a given `.m3u8`, the track fails; verify this on your server.

## Build

```
./gradlew build        # jar in build/libs/muntxlava-1.0.0.jar
```

## Notes

Uses unofficial/web endpoints (Qobuz bundle scraping, Twitter GraphQL query IDs, Twitch persisted-query hashes,
VK URL unmasking). These can change without notice. This code was ported but **not compiled or run** in the
environment it was written in; expect to fix small compile or API issues on the first build.
