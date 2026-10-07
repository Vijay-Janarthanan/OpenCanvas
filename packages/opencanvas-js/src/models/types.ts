export enum OpenCanvasMode {
  LOOP_CANVAS = 'LOOP_CANVAS',
  FULL_SYNCED_VIDEO = 'FULL_SYNCED_VIDEO',
}

export interface CropKeyframe {
  t: number;
  cx: number;
  cy?: number;
  isSceneCut?: boolean;
}

export interface CropTrajectory {
  videoId: string;
  keyframes: CropKeyframe[];
}

export interface DetectionBox {
  xMin: number;
  yMin: number;
  xMax: number;
  yMax: number;
  confidence: number;
}

export interface OpenCanvasTrack {
  videoId: string;
  videoStreamUrl: string;
  title: string;
  artist: string;
  mode: OpenCanvasMode;
  loopStartMs: number;
  loopEndMs: number;
  audioOffsetMs?: number;
  trajectory?: CropTrajectory;
  targetAspectRatio?: number;
  source: string;
}
