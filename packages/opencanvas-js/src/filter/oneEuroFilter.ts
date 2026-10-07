class LowPassFilter {
  private s: number | null = null;

  filter(value: number, alpha: number): number {
    const prev = this.s;
    const result = prev === null ? value : alpha * value + (1.0 - alpha) * prev;
    this.s = result;
    return result;
  }

  lastValue(): number | null {
    return this.s;
  }

  reset(): void {
    this.s = null;
  }
}

export class OneEuroFilter {
  private minCutoff: number;
  private beta: number;
  private dCutoff: number;
  private xFilter = new LowPassFilter();
  private dxFilter = new LowPassFilter();
  private lastTime: number | null = null;

  constructor(minCutoff = 1.0, beta = 0.007, dCutoff = 1.0) {
    this.minCutoff = minCutoff;
    this.beta = beta;
    this.dCutoff = dCutoff;
  }

  private alpha(rate: number, cutoff: number): number {
    const tau = 1.0 / (2.0 * Math.PI * cutoff);
    const te = 1.0 / rate;
    return 1.0 / (1.0 + tau / te);
  }

  filter(x: number, t: number): number {
    const prevTime = this.lastTime;
    if (prevTime === null) {
      this.lastTime = t;
      return this.xFilter.filter(x, 1.0);
    }

    const dt = Math.max(t - prevTime, 1e-4);
    this.lastTime = t;
    const rate = 1.0 / dt;

    const lastX = this.xFilter.lastValue() ?? x;
    const dx = (x - lastX) * rate;
    const edx = this.dxFilter.filter(dx, this.alpha(rate, this.dCutoff));

    const cutoff = this.minCutoff + this.beta * Math.abs(edx);
    return this.xFilter.filter(x, this.alpha(rate, cutoff));
  }

  reset(): void {
    this.xFilter.reset();
    this.dxFilter.reset();
    this.lastTime = null;
  }
}
