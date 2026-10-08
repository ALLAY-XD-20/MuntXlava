package dev.pawan.muntxlava;

import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.DelegatedAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.InternalAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Track whose audio is resolved at play time by its source (direct stream or mirror). */
public class MuntTrack extends DelegatedAudioTrack {

    private static final Logger log = LoggerFactory.getLogger(MuntTrack.class);

    private final BaseSource source;
    private final String extra;

    public MuntTrack(AudioTrackInfo info, BaseSource source, String extra) {
        super(info);
        this.source = source;
        this.extra = extra;
    }

    /** Source-specific payload persisted inside the encoded track (e.g. VK access key). */
    public String extra() { return extra; }

    @Override
    public void process(LocalAudioTrackExecutor executor) throws Exception {
        InternalAudioTrack playable = source.resolvePlayable(trackInfo, extra);
        if (playable == null) {
            throw new FriendlyException(source.getSourceName() + " track unavailable: " + trackInfo.title,
                    FriendlyException.Severity.COMMON, null);
        }
        log.info("{} track '{}' playing via {}", source.getSourceName(), trackInfo.title, playable.getInfo().uri);
        processDelegate(playable, executor);
    }

    @Override
    protected AudioTrack makeShallowClone() {
        return new MuntTrack(trackInfo, source, extra);
    }

    @Override
    public AudioSourceManager getSourceManager() {
        return source;
    }
}
