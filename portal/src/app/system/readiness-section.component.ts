import { Component, OnInit, OnDestroy, Output, EventEmitter, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Subscription, interval } from 'rxjs';
import {
  ReadinessService,
  ReadinessResponse,
  CompoundReadiness,
  SectorReadiness,
  TrackOption,
} from './readiness.service';

@Component({
  selector: 'app-readiness-section',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="readiness card">
      <div class="readiness__head">
        <h3>Calibration Readiness</h3>
        <div class="controls">
          <select [value]="selectedTrackId() ?? ''" (change)="onTrackChange($event)">
            <option *ngFor="let t of tracks()" [value]="t.trackId">{{ t.trackName }}</option>
          </select>
        </div>
      </div>

      <div class="summary" *ngIf="data() as d">
        <div class="tile">
          <span>{{ d.overallConfidence * 100 | number: '1.0-0' }}%</span>sim confidence
        </div>
        <div class="tile"><span>{{ d.fuelEffectFitted ? '✓' : '✗' }}</span>fuel effect</div>
        <div class="tile last"><span>last calibrated</span>{{ d.calibrationLastRanAt ?? 'never' }}</div>
      </div>

      <div class="rows" *ngIf="data() as d">
        <div class="comp" *ngFor="let c of d.compounds">
          <!-- Row 1: identity + overall readiness + fitted-state badges -->
          <div class="comp__main">
            <span class="chip" [style.background]="compoundColor(c.compound)"></span>
            <span class="name">{{ c.name }}</span>
            <span class="conf">
              <span class="bar"><i [style.width.%]="c.confidence * 100" [class.thin]="c.confidence < 0.5"></i></span>
              {{ c.confidence * 100 | number: '1.0-0' }}%
            </span>
            <span class="badges">
              <span class="badge" [class.on]="c.baselineFitted" title="sector pace baseline">pace</span>
              <span class="badge" [class.on]="c.degFitted" title="tyre degradation fit">deg</span>
              <span class="badge" [class.on]="c.wearFitted" title="wear-rate / cliff fit">cliff</span>
              <span
                class="flag"
                *ngIf="c.degFitted && c.degLowConfidence"
                [class.prior]="c.degClamped"
                [title]="'deg fit n=' + c.degSamples"
              >
                {{ c.degClamped ? 'prior-fallback' : 'low-confidence' }}
              </span>
              <span class="nfit" *ngIf="c.degFitted && !c.degLowConfidence">n={{ c.degSamples }}</span>
            </span>
          </div>
          <!-- Row 2: per-sector good/total mini-bars + exclusion reason counters -->
          <div class="comp__detail">
            <span class="sector" *ngFor="let s of c.sectors">
              <span class="slabel">S{{ s.sector + 1 }}</span>
              <span class="mini"><i [style.width.%]="miniPct(s)" [class.ok]="s.good >= GATE"></i></span>
              <span class="scount">{{ s.good }}/{{ s.total }}</span>
            </span>
            <span class="reasons">
              <span class="rc" *ngFor="let r of reasonCounters(c)">{{ r.label }} {{ r.count }}</span>
              <span class="rc clean" *ngIf="reasonCounters(c).length === 0">no exclusions</span>
            </span>
          </div>
        </div>
      </div>

      <p class="empty" *ngIf="data()?.compounds?.length === 0">No session data for this track yet.</p>
    </div>
  `,
  styles: [
    `
      .readiness { margin-top: 1rem; }
      .readiness__head { display: flex; justify-content: space-between; align-items: center; }
      .readiness__head h3 { margin: 0; font-size: 0.95rem; }
      .controls { display: flex; gap: 0.5rem; }
      .summary { display: flex; gap: 1.5rem; margin: 0.75rem 0; }
      .tile { display: flex; flex-direction: column; font-size: 0.7rem; color: var(--gray-500, #999); }
      .tile span { font-size: 1.3rem; font-weight: 600; color: var(--gray-100, #eee); }
      .tile.last span { font-size: 0.7rem; font-weight: 400; }
      .rows { font-size: 0.85rem; }
      .comp { border-top: 1px solid #2a2a2a; padding: 0.55rem 0.25rem; }
      .comp__main { display: flex; align-items: center; gap: 0.6rem; }
      .chip { width: 12px; height: 12px; border-radius: 50%; flex: 0 0 auto; border: 1px solid #00000055; }
      .name { font-weight: 600; min-width: 4rem; }
      .conf { white-space: nowrap; display: flex; align-items: center; }
      .bar { display: inline-block; width: 90px; height: 8px; background: #2a2a2a; border-radius: 4px; margin-right: 0.4rem; overflow: hidden; }
      .bar i { display: block; height: 100%; background: #4caf50; }
      .bar i.thin { background: #e6a23c; }
      .badges { display: flex; align-items: center; gap: 0.4rem; margin-left: auto; }
      .badge { font-size: 0.7rem; color: #555; border: 1px solid #444; border-radius: 3px; padding: 0 0.3rem; }
      .badge.on { color: #1a1a1a; background: #4caf50; border-color: #4caf50; font-weight: 600; }
      .flag { font-size: 0.7rem; color: #1a1a1a; background: #e6a23c; border-radius: 3px; padding: 0 0.3rem; font-weight: 600; }
      .flag.prior { background: #d9534f; color: #fff; }
      .nfit { font-size: 0.7rem; color: var(--gray-500, #999); }
      .comp__detail { display: flex; align-items: center; gap: 1rem; margin-top: 0.4rem; padding-left: 1.7rem; flex-wrap: wrap; }
      .sector { display: flex; align-items: center; gap: 0.35rem; font-size: 0.75rem; color: var(--gray-500, #999); }
      .slabel { font-weight: 600; color: #bbb; }
      .mini { display: inline-block; width: 48px; height: 6px; background: #2a2a2a; border-radius: 3px; overflow: hidden; }
      .mini i { display: block; height: 100%; background: #e6a23c; }
      .mini i.ok { background: #4caf50; }
      .reasons { display: flex; gap: 0.35rem; flex-wrap: wrap; margin-left: auto; }
      .rc { font-size: 0.7rem; color: var(--gray-500, #999); background: #222; border-radius: 3px; padding: 0 0.3rem; }
      .rc.clean { color: #4caf50; background: transparent; }
      .empty { color: var(--gray-500, #999); font-size: 0.85rem; }
    `,
  ],
})
export class ReadinessSectionComponent implements OnInit, OnDestroy {
  // Signals (not plain fields): the portal is zoneless, so a plain field set inside an
  // HttpClient.subscribe callback would never repaint the view. signal.set() does.
  tracks = signal<TrackOption[]>([]);
  selectedTrackId = signal<number | undefined>(undefined);
  data = signal<ReadinessResponse | undefined>(undefined);
  /** Clean-sample gate a sector needs before its deg fit is data-backed (MIN_TYRE_DEG_SAMPLES). */
  readonly GATE = 10;
  private pollSub?: Subscription;

  /** Emits whenever the selected track changes, so the degradation charts below
   * can follow the same track without a second picker. */
  @Output() trackChange = new EventEmitter<number | undefined>();

  constructor(private svc: ReadinessService) {}

  ngOnInit(): void {
    this.svc.tracks().subscribe((ts) => {
      this.tracks.set(ts);
      const selected = ts.length ? ts[0].trackId : undefined;
      this.selectedTrackId.set(selected);
      this.trackChange.emit(selected);
      this.refresh();
    });
    // Auto-refresh readiness for the selected track while a session runs.
    this.pollSub = interval(15_000).subscribe(() => this.refresh());
  }

  ngOnDestroy(): void {
    this.pollSub?.unsubscribe();
  }

  onTrackChange(ev: Event): void {
    this.selectedTrackId.set(Number((ev.target as HTMLSelectElement).value));
    this.trackChange.emit(this.selectedTrackId());
    this.refresh();
  }

  refresh(): void {
    this.svc.readiness(this.selectedTrackId()).subscribe((d) => this.data.set(d));
  }

  /** Official F1 compound colours (matches the degradation scatter / strategy widget). */
  compoundColor(compound: number): string {
    return COMPOUND_COLOR[compound] ?? '#888888';
  }

  /** Per-sector good/total fill, capped at 100%. */
  miniPct(s: SectorReadiness): number {
    return s.total > 0 ? Math.min((s.good / s.total) * 100, 100) : 0;
  }

  /** Non-zero exclusion reasons as labelled counters. */
  reasonCounters(c: CompoundReadiness): { label: string; count: number }[] {
    const r = c.reasons;
    const out: { label: string; count: number }[] = [];
    if (r.outlier) out.push({ label: 'outlier', count: r.outlier });
    if (r.invalid) out.push({ label: 'invalid', count: r.invalid });
    if (r.cornerCut) out.push({ label: 'corner-cut', count: r.cornerCut });
    if (r.damage) out.push({ label: 'damage', count: r.damage });
    if (r.pit) out.push({ label: 'pit', count: r.pit });
    if (r.safetyCar) out.push({ label: 'SC', count: r.safetyCar });
    if (r.standingStart) out.push({ label: 'start', count: r.standingStart });
    return out;
  }
}

// Official F1 compound colours: 16 Soft, 17 Medium, 18 Hard, 7 Inter, 8 Wet.
const COMPOUND_COLOR: Record<number, string> = {
  16: '#DA291C',
  17: '#FFD12E',
  18: '#F0F0EC',
  7: '#43B02A',
  8: '#0067AD',
};
