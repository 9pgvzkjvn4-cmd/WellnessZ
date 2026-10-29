import type { BodyMapRegion } from '../api/types';

/** Simplified body silhouette geometry for the body map (viewBox 0 0 200 390). */
export type Shape = { kind: 'rect'; x: number; y: number; w: number; h: number } | { kind: 'ellipse'; cx: number; cy: number; rx: number; ry: number };

const VIEW_W = 200;

/** Centre-line shapes (no left/right). */
const CENTRE: Record<string, Shape> = {
  HEAD: { kind: 'ellipse', cx: 100, cy: 32, rx: 22, ry: 26 },
  NECK: { kind: 'rect', x: 90, y: 58, w: 20, h: 14 },
  CHEST: { kind: 'rect', x: 72, y: 74, w: 56, h: 50 },
  ABDOMEN: { kind: 'rect', x: 74, y: 124, w: 52, h: 42 },
  PELVIS: { kind: 'rect', x: 84, y: 166, w: 32, h: 26 },
};

/** Paired shapes drawn on the viewer's left; the other side is mirrored. */
const PAIRED: Record<string, Shape> = {
  SHOULDER: { kind: 'ellipse', cx: 62, cy: 82, rx: 11, ry: 10 },
  UPPER_ARM: { kind: 'rect', x: 46, y: 92, w: 18, h: 48 },
  ELBOW: { kind: 'ellipse', cx: 54, cy: 147, rx: 9, ry: 8 },
  FOREARM: { kind: 'rect', x: 40, y: 155, w: 16, h: 43 },
  WRIST: { kind: 'rect', x: 38, y: 198, w: 16, h: 8 },
  HAND: { kind: 'ellipse', cx: 45, cy: 219, rx: 10, ry: 13 },
  HIP: { kind: 'rect', x: 72, y: 166, w: 12, h: 26 },
  THIGH: { kind: 'rect', x: 74, y: 194, w: 24, h: 70 },
  KNEE: { kind: 'ellipse', cx: 86, cy: 273, rx: 11, ry: 9 },
  LOWER_LEG: { kind: 'rect', x: 77, y: 283, w: 19, h: 68 },
  ANKLE: { kind: 'rect', x: 77, y: 352, w: 18, h: 10 },
  FOOT: { kind: 'ellipse', cx: 84, cy: 373, rx: 13, ry: 9 },
};

/** Synonyms used by region codes → drawing key. Back-view torso parts reuse the front torso shapes. */
const ALIASES: Record<string, string> = {
  FACE: 'HEAD',
  HEAD_BACK: 'HEAD',
  BACK_OF_HEAD: 'HEAD',
  EYE: 'HEAD',
  EAR: 'HEAD',
  NECK_BACK: 'NECK',
  THORAX: 'CHEST',
  UPPER_BACK: 'CHEST',
  BACK_UPPER: 'CHEST',
  BACK: 'CHEST',
  STOMACH: 'ABDOMEN',
  LOWER_BACK: 'ABDOMEN',
  BACK_LOWER: 'ABDOMEN',
  GROIN: 'PELVIS',
  BUTTOCKS: 'PELVIS',
  GLUTEAL: 'PELVIS',
  ARM: 'UPPER_ARM',
  FINGERS: 'HAND',
  FINGER: 'HAND',
  THUMB: 'HAND',
  UPPER_LEG: 'THIGH',
  LEG: 'LOWER_LEG',
  SHIN: 'LOWER_LEG',
  CALF: 'LOWER_LEG',
  TOES: 'FOOT',
};

/** Split `HAND_R` / `KNEE_LEFT` into base + laterality. */
export function parseRegionCode(code: string): { base: string; lat?: 'L' | 'R' } {
  const m = /^(.*?)_(L|R|LEFT|RIGHT)$/.exec(code.toUpperCase());
  if (!m) return { base: code.toUpperCase() };
  return { base: m[1]!, lat: m[2]!.startsWith('L') ? 'L' : 'R' };
}

function mirror(s: Shape): Shape {
  return s.kind === 'rect' ? { ...s, x: VIEW_W - s.x - s.w } : { ...s, cx: VIEW_W - s.cx };
}

/**
 * Shape for a region on a view, or undefined if the drawing has no shape for it (it is still listed as a button).
 * Front view shows the person facing the viewer (their right on the viewer's left); back view the opposite.
 */
export function shapeFor(region: BodyMapRegion): Shape | undefined {
  const { base, lat } = parseRegionCode(region.code);
  const key = ALIASES[base] ?? base;
  const centre = CENTRE[key];
  if (centre) return centre;
  const paired = PAIRED[key];
  if (!paired || !lat) return undefined;
  const back = String(region.side).toUpperCase() === 'BACK';
  // Drawn shape is on the viewer's left: the person's right side in front view, their left side in back view.
  const onViewerLeft = back ? lat === 'L' : lat === 'R';
  return onViewerLeft ? paired : mirror(paired);
}

