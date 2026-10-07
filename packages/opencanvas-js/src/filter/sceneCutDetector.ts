export class SceneCutDetector {
  private boxJumpThreshold: number;
  private lastCenterX: number | null = null;

  constructor(boxJumpThreshold = 0.28) {
    this.boxJumpThreshold = boxJumpThreshold;
  }

  checkCenterJump(currentCenterX: number): boolean {
    const prev = this.lastCenterX;
    this.lastCenterX = currentCenterX;
    if (prev === null) return false;

    const jump = Math.abs(currentCenterX - prev);
    return jump >= this.boxJumpThreshold;
  }

  reset(): void {
    this.lastCenterX = null;
  }
}
