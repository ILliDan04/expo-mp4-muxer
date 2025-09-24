// Reexport the native module. On web, it will be resolved to ExpoMp4MuxerModule.web.ts

import ExpoMp4MuxerModule, { EncoderOptions } from "./ExpoMp4MuxerModule";

// and on native platforms to ExpoMp4MuxerModule.ts
export { default } from "./ExpoMp4MuxerModule";
export { ExpoMp4MuxerView } from "./ExpoMp4MuxerView";
export * from "./ExpoMp4Muxer.types";

export const initEncoder = (options: EncoderOptions) => {
  return ExpoMp4MuxerModule.initEncoder(options);
};
