// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugins.videoplayer;

import static com.google.android.exoplayer2.Player.REPEAT_MODE_ALL;
import static com.google.android.exoplayer2.Player.REPEAT_MODE_OFF;

import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.Player.Listener;
import com.google.android.exoplayer2.Tracks;
import com.google.android.exoplayer2.audio.AudioAttributes;
import com.google.android.exoplayer2.extractor.ts.DefaultTsPayloadReaderFactory;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.source.TrackGroup;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.source.dash.DashMediaSource;
import com.google.android.exoplayer2.source.dash.DefaultDashChunkSource;
import com.google.android.exoplayer2.source.hls.DefaultHlsExtractorFactory;
import com.google.android.exoplayer2.source.hls.HlsExtractorFactory;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.source.rtsp.RtspMediaSource;
import com.google.android.exoplayer2.source.smoothstreaming.DefaultSsChunkSource;
import com.google.android.exoplayer2.source.smoothstreaming.SsMediaSource;
import com.google.android.exoplayer2.text.Cue;
import com.google.android.exoplayer2.text.CueGroup;
import com.google.android.exoplayer2.trackselection.AdaptiveTrackSelection;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector.Parameters;
import com.google.android.exoplayer2.trackselection.MappingTrackSelector;
import com.google.android.exoplayer2.trackselection.MappingTrackSelector.MappedTrackInfo;
import com.google.android.exoplayer2.trackselection.TrackSelectionArray;
import com.google.android.exoplayer2.trackselection.TrackSelectionOverride;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultDataSource;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;
import com.google.android.exoplayer2.util.Assertions;
import com.google.android.exoplayer2.util.EventLogger;
import com.google.android.exoplayer2.util.MimeTypes;
import com.google.android.exoplayer2.util.Util;
import com.google.common.collect.ImmutableList;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.flutter.plugin.common.EventChannel;
import io.flutter.view.TextureRegistry;


final class VideoPlayer {
    private static final String FORMAT_SS = "ss";
    private static final String FORMAT_DASH = "dash";
    private static final String FORMAT_HLS = "hls";
    private static final String FORMAT_OTHER = "other";
    private static final String FORMAT_RTSP = "rtsp";

    private ExoPlayer exoPlayer;

    private Surface surface;

    private DefaultTrackSelector trackSelector;

    private Parameters trackSelectorParameters;

    private final TextureRegistry.SurfaceTextureEntry textureEntry;

    private QueuingEventSink eventSink;

    private final EventChannel eventChannel;

    private static final String USER_AGENT = "User-Agent";

    @VisibleForTesting
    boolean isInitialized = false;

    private final VideoPlayerOptions options;

    private DefaultHttpDataSource.Factory httpDataSourceFactory = new DefaultHttpDataSource.Factory();

    VideoPlayer(
            Context context,
            EventChannel eventChannel,
            TextureRegistry.SurfaceTextureEntry textureEntry,
            String dataSource,
            String formatHint,
            @NonNull Map<String, String> httpHeaders,
            VideoPlayerOptions options) {
        this.eventChannel = eventChannel;
        this.textureEntry = textureEntry;
        this.options = options;

        AdaptiveTrackSelection.Factory trackSelectionFactory = new AdaptiveTrackSelection.Factory();
        trackSelectorParameters = new DefaultTrackSelector.Parameters.Builder(context).build();

        trackSelector = new DefaultTrackSelector(context, trackSelectionFactory);
        trackSelector.setParameters(trackSelectorParameters);

        final DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(context);

        renderersFactory.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER);
        renderersFactory.setEnableDecoderFallback(true);


        ExoPlayer exoPlayer = new ExoPlayer.Builder(context)
                .setUsePlatformDiagnostics(true)
                .setRenderersFactory(renderersFactory)
                .setTrackSelector(trackSelector).build();


        Uri uri = Uri.parse(dataSource);

        buildHttpDataSourceFactory(httpHeaders);
        DataSource.Factory dataSourceFactory =
                new DefaultDataSource.Factory(context, httpDataSourceFactory);

        MediaSource mediaSource = buildMediaSource(uri, dataSourceFactory, formatHint);
        exoPlayer.addAnalyticsListener(new EventLogger());


        exoPlayer.setMediaSource(mediaSource);
        exoPlayer.prepare();

        setUpVideoPlayer(exoPlayer, new QueuingEventSink());
    }

    // Constructor used to directly test members of this class.
    @VisibleForTesting
    VideoPlayer(
            ExoPlayer exoPlayer,
            EventChannel eventChannel,
            TextureRegistry.SurfaceTextureEntry textureEntry,
            VideoPlayerOptions options,
            QueuingEventSink eventSink,
            DefaultHttpDataSource.Factory httpDataSourceFactory) {
        this.eventChannel = eventChannel;
        this.textureEntry = textureEntry;
        this.options = options;
        this.httpDataSourceFactory = httpDataSourceFactory;

        setUpVideoPlayer(exoPlayer, eventSink);
    }

    @VisibleForTesting
    public void buildHttpDataSourceFactory(@NonNull Map<String, String> httpHeaders) {
        final boolean httpHeadersNotEmpty = !httpHeaders.isEmpty();
        final String userAgent =
                httpHeadersNotEmpty && httpHeaders.containsKey(USER_AGENT)
                        ? httpHeaders.get(USER_AGENT)
                        : "ExoPlayer";

        httpDataSourceFactory.setUserAgent(userAgent).setAllowCrossProtocolRedirects(true);

        if (httpHeadersNotEmpty) {
            httpDataSourceFactory.setDefaultRequestProperties(httpHeaders);
        }
    }

    private MediaSource buildMediaSource(
            Uri uri, DataSource.Factory mediaDataSourceFactory, String formatHint) {
        int type;
        if (formatHint == null) {
            type = Util.inferContentType(uri);
        } else {
            switch (formatHint) {
                case FORMAT_SS:
                    type = C.CONTENT_TYPE_SS;
                    break;
                case FORMAT_DASH:
                    type = C.CONTENT_TYPE_DASH;
                    break;
                case FORMAT_HLS:
                    type = C.CONTENT_TYPE_HLS;
                    break;
                case FORMAT_RTSP:
                    type = C.CONTENT_TYPE_RTSP;
                    break;
                case FORMAT_OTHER:
                    type = C.CONTENT_TYPE_OTHER;
                    break;

                default:
                    type = -1;
                    break;
            }
        }
        Log.d("video_player:", "type :" + type);

        switch (type) {
            case C.CONTENT_TYPE_SS:
                return new SsMediaSource.Factory(
                        new DefaultSsChunkSource.Factory(mediaDataSourceFactory), mediaDataSourceFactory)
                        .createMediaSource(MediaItem.fromUri(uri));
            case C.CONTENT_TYPE_DASH:
                return new DashMediaSource.Factory(
                        new DefaultDashChunkSource.Factory(mediaDataSourceFactory), mediaDataSourceFactory)
                        .createMediaSource(MediaItem.fromUri(uri));

            case C.CONTENT_TYPE_HLS:
                HlsExtractorFactory hlsOtherExtractorFactory = new DefaultHlsExtractorFactory(
                        DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES, true);
                return new HlsMediaSource.Factory(mediaDataSourceFactory)
                        .setExtractorFactory(hlsOtherExtractorFactory)
                        .createMediaSource(MediaItem.fromUri(uri));
            case C.CONTENT_TYPE_RTSP:
                return new RtspMediaSource.Factory()
                        .createMediaSource(MediaItem.fromUri(uri));
            case C.CONTENT_TYPE_OTHER:
                return new ProgressiveMediaSource.Factory(mediaDataSourceFactory)
                        .createMediaSource(MediaItem.fromUri(uri));
            default: {
                throw new IllegalStateException("Unsupported type: " + type);
            }
        }
    }

    private void setUpVideoPlayer(ExoPlayer exoPlayer, QueuingEventSink eventSink) {
        this.exoPlayer = exoPlayer;
        this.eventSink = eventSink;

        eventChannel.setStreamHandler(
                new EventChannel.StreamHandler() {
                    @Override
                    public void onListen(Object o, EventChannel.EventSink sink) {
                        eventSink.setDelegate(sink);
                    }

                    @Override
                    public void onCancel(Object o) {
                        eventSink.setDelegate(null);
                    }
                });

        surface = new Surface(textureEntry.surfaceTexture());
        exoPlayer.setVideoSurface(surface);
        setAudioAttributes(exoPlayer, options.mixWithOthers);
        exoPlayer.addListener(
                new Listener() {
                    private boolean isBuffering = false;

                    public void setBuffering(boolean buffering) {
                        if (isBuffering != buffering) {
                            isBuffering = buffering;
                            Map<String, Object> event = new HashMap<>();
                            event.put("event", isBuffering ? "bufferingStart" : "bufferingEnd");
                            eventSink.success(event);
                        }
                    }

                    @Override
                    public void onPlaybackStateChanged(final int playbackState) {
                        if (playbackState == Player.STATE_BUFFERING) {
                            setBuffering(true);
                            sendBufferingUpdate();
                        } else if (playbackState == Player.STATE_READY) {
                            if (!isInitialized) {
                                isInitialized = true;
                                sendInitialized();
                            }
                        } else if (playbackState == Player.STATE_ENDED) {
                            Map<String, Object> event = new HashMap<>();
                            event.put("event", "completed");
                            eventSink.success(event);
                        }

                        if (playbackState != Player.STATE_BUFFERING) {
                            setBuffering(false);
                        }
                    }

//                    @Override
                    @SuppressWarnings("ReferenceEquality")
                    public void onTracksChanged(
                            TrackGroupArray trackGroups, TrackSelectionArray trackSelections) {
                    }


                    @Override
                    public void onPlayerError(@NonNull final PlaybackException error) {
                        setBuffering(false);
                        if (eventSink != null) {
                            eventSink.error("VideoError", "Video player had error " + error, null);
                        }
                    }

                    @Override
                    public void onCues(@NonNull CueGroup cueGroup) {
                        if (eventSink != null) {
                            Map<String, Object> event = new HashMap<>();
                            event.put("event", "subtitle");

                            final List<String> cues = new ArrayList<>();
                            for(int i=0; i<cueGroup.cues.size() ;i++){
                                final Cue cue = cueGroup.cues.get(i);
                                cues.add(cue.text != null ? cue.text.toString() : null);
                            }
                            event.put("cues", cues);
                            eventSink.success(event);
                        }

                    }

                    @Override
                    public void onIsPlayingChanged(boolean isPlaying) {
                        if (eventSink != null) {
                            Map<String, Object> event = new HashMap<>();
                            event.put("event", "isPlayingStateUpdate");
                            event.put("isPlaying", isPlaying);
                            eventSink.success(event);
                        }
                    }
                });
    }

    void sendBufferingUpdate() {
        Map<String, Object> event = new HashMap<>();
        event.put("event", "bufferingUpdate");
        List<? extends Number> range = Arrays.asList(0, exoPlayer.getBufferedPosition());
        // iOS supports a list of buffered ranges, so here is a list with a single range.
        event.put("values", Collections.singletonList(range));
        eventSink.success(event);
    }

    private static void setAudioAttributes(ExoPlayer exoPlayer, boolean isMixMode) {
        exoPlayer.setAudioAttributes(
                new AudioAttributes.Builder().setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                !isMixMode);
    }

    void play() {
        exoPlayer.setPlayWhenReady(true);
    }

    void pause() {
        exoPlayer.setPlayWhenReady(false);
    }

    void setLooping(boolean value) {
        exoPlayer.setRepeatMode(value ? REPEAT_MODE_ALL : REPEAT_MODE_OFF);
    }

    void setVolume(double value) {
        float bracketedValue = (float) Math.max(0.0, Math.min(1.0, value));
        exoPlayer.setVolume(bracketedValue);
    }

    void setPlaybackSpeed(double value) {
        // We do not need to consider pitch and skipSilence for now as we do not handle them and
        // therefore never diverge from the default values.
        final PlaybackParameters playbackParameters = new PlaybackParameters(((float) value));

        exoPlayer.setPlaybackParameters(playbackParameters);
    }

    void seekTo(int location) {
        exoPlayer.seekTo(location);
    }

    long getPosition() {
        return exoPlayer.getCurrentPosition();
    }

    private void updateTrackSelectorParameters() {
        if (trackSelector != null) {
            trackSelectorParameters = trackSelector.getParameters();
        }
    }


    public ArrayList<Object> getTrackSelections() {
        System.err.println("xxx : tracks X4 ...");
        ArrayList<Object> trackSelections = new ArrayList<>();
        ArrayList<Integer> autoTrackSelectionTypes = new ArrayList<>();


        MappedTrackInfo mappedTrackInfo =
                Assertions.checkNotNull(trackSelector.getCurrentMappedTrackInfo());
        for (int rendererIndex = 0;
             rendererIndex < mappedTrackInfo.getRendererCount();
             rendererIndex++) {
            TrackGroupArray trackGroups = mappedTrackInfo.getTrackGroups(rendererIndex);

            if (isSupportedTrackForRenderer(trackGroups, mappedTrackInfo, rendererIndex)) {
                int trackType = mappedTrackInfo.getRendererType(rendererIndex);
                for (int groupIndex = 0; groupIndex < trackGroups.length; groupIndex++) {
                    TrackGroup group = trackGroups.get(groupIndex);

                    //add auto track for each track selection types
//                    if (!autoTrackSelectionTypes.contains(trackType)) {
//                        autoTrackSelectionTypes.add(trackType);
//                        HashMap<String, Object> autoTrackSelection = new HashMap<>();
//                        autoTrackSelection.put("isUnknown", false);
//                        autoTrackSelection.put("isAuto", true);
//                        autoTrackSelection.put("trackType", trackType);
//                        final boolean isSelected = trackSelectorParameters.getRendererDisabled(rendererIndex);
//
//
//                        Log.d("xxx auto", "isSelected" + " " + trackSelectorParameters.overrides);
//
//                        autoTrackSelection.put(
//                                "isSelected",
//                                isSelected);
//                        autoTrackSelection.put("trackId", Integer.toString(rendererIndex));
//                        trackSelections.add(autoTrackSelection);
//                    }

                    //add tracks
                    for (int trackIndex = 0; trackIndex < group.length; trackIndex++) {
                        HashMap<String, Object> trackSelection = new HashMap<>();
                        Format trackFormat = group.getFormat(trackIndex);

                        int inferPrimaryTrackType = inferPrimaryTrackType(trackFormat);
                        trackSelection.put("isUnknown", false);
                        switch (inferPrimaryTrackType) {
                            case C.TRACK_TYPE_VIDEO:
                                trackSelection.put("rolesFlag", roleIntMap(trackFormat));
                                trackSelection.put("width", trackWidth(trackFormat));
                                trackSelection.put("height", trackHeight(trackFormat));
                                trackSelection.put("bitrate", trackBitrate(trackFormat));
                                break;
                            case C.TRACK_TYPE_AUDIO:
                                trackSelection.put("language", buildLanguageString(trackFormat));
                                trackSelection.put("label", buildLabelString(trackFormat));
                                trackSelection.put("rolesFlag", roleIntMap(trackFormat));
                                trackSelection.put("channelCount", audioChannelCount(trackFormat));
                                trackSelection.put("bitrate", trackBitrate(trackFormat));
                                break;
                            case C.TRACK_TYPE_TEXT:
                                trackSelection.put("language", buildLanguageString(trackFormat));
                                trackSelection.put("label", buildLabelString(trackFormat));
                                trackSelection.put("rolesFlag", roleIntMap(trackFormat));
                                break;
                            default:
                                trackSelection.put("isUnknown", true);
                        }


                        trackSelection.put("isAuto", false);
                        trackSelection.put("trackType", trackType);


                        final boolean isTrackSelected = isTrackSelected(trackType, groupIndex, trackIndex);

                        trackSelection.put(
                                "isSelected",
                                isTrackSelected);
                        trackSelection.put("trackId", getTrackId(rendererIndex, groupIndex, trackIndex));
                        trackSelections.add(trackSelection);
                    }
                }
            }
        }
        System.err.println(trackSelections.toString());
        return trackSelections;
    }


    boolean isTrackSelected(int trackType, int groupIndex, int trackIndex) {
        final Tracks tracks = exoPlayer.getCurrentTracks();
        final ImmutableList<Tracks.Group> groups = tracks.getGroups();

        final List<Tracks.Group> selectedTypeGroups = new ArrayList<>();
        for (int i = 0; i < groups.size(); i++) {
            final Tracks.Group group = groups.get(i);
            if (group.getType() == trackType) {
                selectedTypeGroups.add(group);
            }
        }

        if (groupIndex < 0 || groupIndex >= selectedTypeGroups.size()) {
            return false;
        }

        final Tracks.Group group = selectedTypeGroups.get(groupIndex);

        return group.isTrackSelected(trackIndex);
    }


    private Boolean isSupportedTrackForRenderer(
            TrackGroupArray trackGroups,
            MappingTrackSelector.MappedTrackInfo mappedTrackInfo,
            Integer rendererIndex) {
        if (trackGroups.length == 0) {
            return false;
        }
        int trackType = mappedTrackInfo.getRendererType(rendererIndex);
        return isSupportedTrackType(trackType);
    }

    private Boolean isSupportedTrackType(Integer trackType) {
        switch (trackType) {
            case C.TRACK_TYPE_VIDEO:
            case C.TRACK_TYPE_AUDIO:
            case C.TRACK_TYPE_TEXT:
                return true;
            default:
                return false;
        }
    }

    private static Integer inferPrimaryTrackType(Format format) {
        int trackType = MimeTypes.getTrackType(format.sampleMimeType);
        if (trackType != C.TRACK_TYPE_UNKNOWN) {
            return trackType;
        }
        if (MimeTypes.getVideoMediaMimeType(format.codecs) != null) {
            return C.TRACK_TYPE_VIDEO;
        }
        if (MimeTypes.getAudioMediaMimeType(format.codecs) != null) {
            return C.TRACK_TYPE_AUDIO;
        }
        if (format.width != Format.NO_VALUE || format.height != Format.NO_VALUE) {
            return C.TRACK_TYPE_VIDEO;
        }
        if (format.channelCount != Format.NO_VALUE || format.sampleRate != Format.NO_VALUE) {
            return C.TRACK_TYPE_AUDIO;
        }
        return C.TRACK_TYPE_UNKNOWN;
    }

    private String buildLabelString(Format format) {
        return TextUtils.isEmpty(format.label) ? "" : format.label;
    }

    private String buildLanguageString(Format format) {
        String language = format.language;
        if (language == null
                || TextUtils.isEmpty(language)
                || C.LANGUAGE_UNDETERMINED.equals(language)) {
            return "";
        }
        Locale locale = Util.SDK_INT >= 21 ? Locale.forLanguageTag(language) : new Locale(language);
        return locale.getDisplayName();
    }

    private Integer roleIntMap(Format format) {
        if ((format.roleFlags & C.ROLE_FLAG_ALTERNATE) != 0) {
            return 0;
        }
        if ((format.roleFlags & C.ROLE_FLAG_SUPPLEMENTARY) != 0) {
            return 1;
        }
        if ((format.roleFlags & C.ROLE_FLAG_COMMENTARY) != 0) {
            return 2;
        }
        if ((format.roleFlags & (C.ROLE_FLAG_CAPTION | C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND)) != 0) {
            return 3;
        }
        return -1;
    }

    private Integer audioChannelCount(Format format) {
        int channelCount = format.channelCount;
        if (channelCount < 1) {
            return -1;
        }
        return channelCount;
    }

    private Integer trackBitrate(Format format) {
        return format.bitrate;
    }

    private Integer trackWidth(Format format) {
        return format.width;
    }

    private Integer trackHeight(Format format) {
        return format.height;
    }


    String getTrackId(Integer rendererIndex, Integer groupIndex, Integer trackIndex) {
        return rendererIndex + ":" + groupIndex + ":" + trackIndex;
    }


    public void setTrackSelection(String trackId, int trackType) {

        final String[] split = trackId.split(":");
        if (split.length != 3) {
            System.err.println("xxx : Invalid trackId " + trackId);
            throw new IllegalStateException("Invalid trackId: " + trackId);
        }
        final int groupIndex = Integer.parseInt(split[1]);
        final int trackIndex = Integer.parseInt(split[2]);

        Tracks tracks = exoPlayer.getCurrentTracks();
        List<Tracks.Group> trackGroups = tracks.getGroups();
        final List<Tracks.Group> selectedTypeGroups = new ArrayList<>();
        for (int i = 0; i < trackGroups.size(); i++) {
            final Tracks.Group group = trackGroups.get(i);
            if (group.getType() == trackType) {
                selectedTypeGroups.add(group);
            }
        }



        if (groupIndex < 0 || groupIndex >= selectedTypeGroups.size()) {
            System.err.println("xxx : Unsupported type " + trackType);

            throw new IllegalStateException("Unsupported type: " + trackType);
        }

        final Tracks.Group trackGroup = selectedTypeGroups.get(groupIndex);

        if (!trackGroup.isTrackSupported(trackIndex)) {
            System.err.println("xxx : Unsupported track Index " + trackIndex);
            throw new IllegalStateException("Unsupported track Index: " + trackIndex);
        }


        final Format format =  trackGroup.getTrackFormat(0);


        exoPlayer.setTrackSelectionParameters(
                exoPlayer.getTrackSelectionParameters()
                        .buildUpon()
                        .setOverrideForType(
                                new TrackSelectionOverride(
                                        trackGroup.getMediaTrackGroup(),
                                       trackIndex))
                        .build());
    }

    @SuppressWarnings("SuspiciousNameCombination")
    @VisibleForTesting
    void sendInitialized() {
        if (isInitialized) {
            Map<String, Object> event = new HashMap<>();
            event.put("event", "initialized");
            event.put("duration", exoPlayer.getDuration());

            if (exoPlayer.getVideoFormat() != null) {
                Format videoFormat = exoPlayer.getVideoFormat();
                int width = videoFormat.width;
                int height = videoFormat.height;
                int rotationDegrees = videoFormat.rotationDegrees;
                // Switch the width/height if video was taken in portrait mode
                if (rotationDegrees == 90 || rotationDegrees == 270) {
                    width = exoPlayer.getVideoFormat().height;
                    height = exoPlayer.getVideoFormat().width;
                }
                event.put("width", width);
                event.put("height", height);

                // Rotating the video with ExoPlayer does not seem to be possible with a Surface,
                // so inform the Flutter code that the widget needs to be rotated to prevent
                // upside-down playback for videos with rotationDegrees of 180 (other orientations work
                // correctly without correction).
                if (rotationDegrees == 180) {
                    event.put("rotationCorrection", rotationDegrees);
                }
            }

            eventSink.success(event);
        }
    }

    void dispose() {
        if (isInitialized) {
            exoPlayer.stop();
        }
        textureEntry.release();
        eventChannel.setStreamHandler(null);
        updateTrackSelectorParameters();
        if (trackSelector != null) {
            trackSelector = null;
        }
        if (surface != null) {
            surface.release();
        }
        if (exoPlayer != null) {
            exoPlayer.release();
        }
    }
}
