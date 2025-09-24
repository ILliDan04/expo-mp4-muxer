import { NativeModule, requireNativeModule } from "expo";

import { ExpoMp4MuxerModuleEvents } from "./ExpoMp4Muxer.types";

export type EncoderOptions = {
  viewId: number;
  codec?: string;
  width?: number;
  height?: number;
  bitrate?: number;
  framerate?: number;
};

declare class ViewCaptureEncoder {
  capture: () => void;
  finilize: () => string;
}

declare class ExpoMp4MuxerModule extends NativeModule<ExpoMp4MuxerModuleEvents> {
  public initEncoder: (options: EncoderOptions) => void;
}

// This call loads the native module object from the JSI.
export default requireNativeModule<ExpoMp4MuxerModule>("ExpoMp4Muxer");
