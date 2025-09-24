import * as React from "react";
import { ViewProps } from "react-native";
import { requireNativeViewManager } from "expo-modules-core";

export type Props = ViewProps & {
  codec?: string;
  framerate?: number;
  bitrate?: number;
};

export type ExpoMp4MuxerRef = {
  start: () => Promise<boolean>;
  capture: () => Promise<void>;
  finish: () => Promise<string>;
};

export const ExpoMp4MuxerView = requireNativeViewManager<Props>(
  "ExpoMp4Muxer"
) as React.ForwardRefExoticComponent<
  Props & React.RefAttributes<ExpoMp4MuxerRef>
>;

export const useMuxerRef = () => {
  return React.useRef<ExpoMp4MuxerRef | null>(null);
};
