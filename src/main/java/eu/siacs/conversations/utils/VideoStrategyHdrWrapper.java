package eu.siacs.conversations.utils;

import android.media.MediaFormat;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import com.otaliastudios.transcoder.common.TrackStatus;
import com.otaliastudios.transcoder.strategy.TrackStrategy;

import java.util.List;

public class VideoStrategyHdrWrapper implements TrackStrategy {

    private final TrackStrategy wrapped;

    private VideoStrategyHdrWrapper(@NonNull TrackStrategy wrapped) {
        this.wrapped = wrapped;
    }

    public static TrackStrategy maybeWrap(@NonNull TrackStrategy wrapped) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return new VideoStrategyHdrWrapper(wrapped);
        } else {
            return wrapped;
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    @NonNull
    @Override
    public TrackStatus createOutputFormat(@NonNull List<MediaFormat> inputFormats, @NonNull MediaFormat outputFormat) {
        TrackStatus wrappedTrackStatus = this.wrapped.createOutputFormat(inputFormats, outputFormat);

        // No need to set up output format if we're not recompressing the track
        if (wrappedTrackStatus != TrackStatus.COMPRESSING) {
            return wrappedTrackStatus;
        }

        // Conversations doesn't work with multiple source tracks, so we don't need to handle this case
        if (inputFormats.size() != 1) {
            return wrappedTrackStatus;
        }

        // Simply always copy HDR-related attributes of input track to output track (if any)
        MediaFormat inputFormat = inputFormats.get(0);
        List.of(
                MediaFormat.KEY_COLOR_STANDARD,
                MediaFormat.KEY_COLOR_TRANSFER,
                MediaFormat.KEY_COLOR_RANGE,
                MediaFormat.KEY_HDR_STATIC_INFO
        ).forEach(key -> {
            if (inputFormat.containsKey(key)) {
                outputFormat.setInteger(key, inputFormat.getInteger(key));
            }
        });

        return wrappedTrackStatus;
    }
}
