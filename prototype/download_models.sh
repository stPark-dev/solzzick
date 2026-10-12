#!/bin/sh
# MediaPipe 모델 내려받기(프로토타입용). 앱은 android/app/src/main/assets 에 같은 모델이 들어 있다.
set -e
cd "$(dirname "$0")"
mkdir -p models && cd models
for m in lite full heavy; do
  curl -sSfLO "https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_$m/float16/latest/pose_landmarker_$m.task"
done
for m in efficientdet_lite0 efficientdet_lite2; do
  curl -sSfLO "https://storage.googleapis.com/mediapipe-models/object_detector/$m/float32/latest/$m.tflite"
done
ls -la
