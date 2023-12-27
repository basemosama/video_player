// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

import 'package:pigeon/java_generator.dart';
import 'package:pigeon/objc_generator.dart';
import 'package:pigeon/pigeon_lib.dart';

@ConfigurePigeon(PigeonOptions(
  dartOut: '../video_player_platform_interface/lib/messages.dart',
  dartTestOut: '../video_player_platform_interface/lib/test.dart',
  objcHeaderOut: 'ios/Classes/messages.h',
  objcSourceOut: 'ios/Classes/messages.m',
  objcOptions: ObjcOptions(prefix: 'FLT'),
  javaOut: 'android/src/main/java/io/flutter/plugins/videoplayer/Messages.java',
  javaOptions: JavaOptions(package: 'io.flutter.plugins.videoplayer'),
))


class TextureMessage {
  TextureMessage(this.textureId);

  int textureId;
}

class LoopingMessage {
  LoopingMessage({
    required this.textureId,
    required this.isLooping,
  });

  int textureId;
  bool isLooping;
}

class VolumeMessage {
  VolumeMessage({
    required this.textureId,
    required this.volume,
  });

  int textureId;
  double volume;
}

class PlaybackSpeedMessage {
  PlaybackSpeedMessage({
    required this.textureId,
    required this.speed,
  });

  int textureId;
  double speed;
}

class PositionMessage {
  PositionMessage({
    required this.textureId,
    required this.position,
  });

  int textureId;
  int position;
}

class TrackSelectionsMessage {
  TrackSelectionsMessage({
    required this.textureId,
    required this.trackId,
    required this.trackSelections,
  });

  int textureId;
  String trackId;
  List<Object> trackSelections;
}

class CreateMessage {
  CreateMessage({
    required this.asset,
    required this.uri,
    required this.packageName,
    required this.formatHint,
    required this.httpHeaders,
  });

  String asset;
  String uri;
  String packageName;
  String formatHint;
  Map<String, String> httpHeaders;
}

class MixWithOthersMessage {
  MixWithOthersMessage({
    required this.mixWithOthers,
  });

  bool mixWithOthers;
}

@HostApi(dartHostTestHandler: 'TestHostVideoPlayerApi')
abstract class VideoPlayerApi {
  void initialize();

  TextureMessage create(CreateMessage msg);

  void dispose(TextureMessage msg);

  void setLooping(LoopingMessage msg);

  void setVolume(VolumeMessage msg);

  void setPlaybackSpeed(PlaybackSpeedMessage msg);

  void play(TextureMessage msg);

  PositionMessage position(TextureMessage msg);

  void seekTo(PositionMessage msg);

  TrackSelectionsMessage trackSelections(TextureMessage msg);

  void setTrackSelection(TrackSelectionsMessage msg);

  void pause(TextureMessage msg);

  void setMixWithOthers(MixWithOthersMessage msg);
}
