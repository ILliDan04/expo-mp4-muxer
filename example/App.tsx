import { CameraView, useCameraPermissions } from "expo-camera";
import { useEffect, useRef, useState } from "react";
import {
  Button,
  findNodeHandle,
  Pressable,
  StyleSheet,
  Text,
  View,
} from "react-native";
import { initEncoder, ExpoMp4MuxerView } from "expo-mp4-muxer";
import { ExpoMp4MuxerRef, useMuxerRef } from "expo-mp4-muxer/ExpoMp4MuxerView";
import { GLView } from "expo-gl";

const ENCODER_VIEW_ID = "ENCODER_VIEW";

export default function App() {
  const muxer = useMuxerRef();

  const [recording, setRecording] = useState(false);
  const [videoPath, setVideoPath] = useState<string | null>(null);

  const [permission, requestPermission] = useCameraPermissions();

  useEffect(() => {
    if (!recording) return;

    const interval = setInterval(() => {
      console.log("capturing");

      muxer.current?.capture();
    }, 10);

    return () => {
      clearInterval(interval);
    };
  }, [recording]);

  if (!permission) {
    return <View />;
  }

  if (!permission.granted) {
    return (
      <View>
        <Text>We need your permission to show the camera</Text>
        <Button onPress={requestPermission} title="grant permission" />
      </View>
    );
  }

  const handlePress = async () => {
    if (recording) {
      setRecording(false);
      const videoPath = await muxer.current?.finish();
      setVideoPath(videoPath ?? null);
      return;
    }
    const res = await muxer.current?.start();
    console.log(res);

    setRecording(res ?? false);
  };

  return (
    <ExpoMp4MuxerView style={{ flex: 1 }} ref={muxer}>
      <View style={{ flex: 1 }}>
        <CameraView
          active
          style={StyleSheet.absoluteFill}
          videoQuality="1080p"
        />

        <Pressable onPress={handlePress} style={styles.recordBtn}>
          <Text style={styles.recordBtnText}>
            {recording ? "Stop" : "Start"} Recording
          </Text>
        </Pressable>
        {/* <GLView
          onContextCreate={(gl) => {
            gl.getBufferSubData();
          }}
        /> */}
      </View>
    </ExpoMp4MuxerView>
  );
}

const styles = StyleSheet.create({
  recordBtn: {
    position: "absolute",
    bottom: 100,
    backgroundColor: "orange",
    padding: 10,
    alignSelf: "center",
    borderRadius: 100,
  },
  recordBtnText: {
    textTransform: "uppercase",
    fontWeight: "bold",
    color: "white",
  },
});
