import { Component, OnInit, OnDestroy, signal, computed } from '@angular/core';
import { Subscription } from 'rxjs';
import { DecimalPipe } from '@angular/common';
import { CalibrationService, CalibrationStatusDto } from '../calibration.service';
import { RaceService, RaceMessage } from '../race.service';

@Component({
  selector: 'app-calibration',
  imports: [DecimalPipe],
  template: `
    <div class="header">
      <h2>Calibration Status</h2>
      <div class="actions">
        <label>
          Track ID:
          <input
            type="number"
            [value]="trackIdInput()"
            (input)="onTrackIdChange($event)"
            min="0"
            max="40"
            style="width: 4rem"
          />
        </label>
        <button (click)="loadStatus()">Refresh</button>
        <button (click)="runCalibration()" [disabled]="running() || trackIdInput() == null">
          {{ running() ? 'Running...' : 'Run Calibration' }}
        </button>
      </div>
      @if (error()) {
        <span class="error">{{ error() }}</span>
      }
      @if (calibrationMsg()) {
        <span class="cal-msg" [class.success]="calibrationMsg()!.startsWith('Complete')"
              [class.fail]="calibrationMsg()!.startsWith('Failed')">
          {{ calibrationMsg() }}
        </span>
      }
    </div>

    <div class="readiness">
      <span class="readiness-label">Overall:</span>
      <span class="readiness-count green">{{ fittedCount() }} fitted</span>
      <span class="readiness-count red">{{ defaultCount() }} defaults</span>
    </div>

    @if (knobs().length > 0) {
      <table class="cal-table">
        <thead>
          <tr>
            <th>Status</th>
            <th>Knob</th>
            <th>Regime</th>
            <th>Sector</th>
            <th>Value</th>
            <th>Confidence</th>
            <th>Sessions</th>
            <th>Data Points</th>
            <th>Trained At</th>
          </tr>
        </thead>
        <tbody>
          @for (k of knobs(); track k.knobName + k.sectorNumber) {
            <tr [class]="knobClass(k)">
              <td>
                <span class="indicator" [class]="knobClass(k)"></span>
              </td>
              <td>{{ k.knobName }}</td>
              <td>{{ k.calibrationRegime }}</td>
              <td>{{ k.sectorNumber != null ? k.sectorNumber : 'all' }}</td>
              <td class="mono">{{ k.value | number : '1.4-4' }}</td>
              <td class="mono">{{ k.confidence | number : '1.3-3' }}</td>
              <td>{{ k.sessionCount }}</td>
              <td>{{ k.dataPointCount }}</td>
              <td>{{ k.trainedAt || '-' }}</td>
            </tr>
          }
        </tbody>
      </table>
    } @else if (!loading()) {
      <p class="empty">No calibration data. Set a track ID and click Refresh, or run a session first.</p>
    }
  `,
  styles: `
    .header { display: flex; align-items: center; gap: 1rem; margin-bottom: 1rem; flex-wrap: wrap; }
    .header h2 { margin: 0; }
    .actions { display: flex; gap: 0.5rem; align-items: center; }
    .actions input {
      background: #222;
      border: 1px solid #555;
      color: #eee;
      padding: 0.25rem 0.4rem;
      border-radius: 3px;
    }
    .actions button {
      padding: 0.4rem 0.8rem;
      background: #333;
      color: #eee;
      border: 1px solid #555;
      border-radius: 4px;
      cursor: pointer;
      font-size: 0.85rem;
    }
    .actions button:disabled { opacity: 0.5; cursor: not-allowed; }
    .error { color: #ef5350; font-size: 0.85rem; }
    .cal-msg { font-size: 0.85rem; padding: 0.2rem 0.5rem; border-radius: 4px; }
    .cal-msg.success { background: #1b5e20; color: #a5d6a7; }
    .cal-msg.fail { background: #b71c1c; color: #ef9a9a; }

    .readiness { display: flex; gap: 0.75rem; align-items: center; margin-bottom: 1rem; }
    .readiness-label { color: #999; font-size: 0.85rem; }
    .readiness-count { font-size: 0.85rem; padding: 0.2rem 0.5rem; border-radius: 4px; }
    .readiness-count.green { background: #1b5e20; color: #a5d6a7; }
    .readiness-count.red { background: #b71c1c; color: #ef9a9a; }

    .cal-table { width: 100%; border-collapse: collapse; font-size: 0.9rem; }
    .cal-table th {
      text-align: left;
      padding: 0.4rem 0.6rem;
      border-bottom: 2px solid #444;
      color: #999;
      font-size: 0.8rem;
      text-transform: uppercase;
    }
    .cal-table td { padding: 0.35rem 0.6rem; border-bottom: 1px solid #2a2a2a; }
    .mono { font-family: monospace; }

    .indicator {
      display: inline-block;
      width: 10px;
      height: 10px;
      border-radius: 50%;
    }
    .indicator.green { background: #4caf50; }
    .indicator.yellow { background: #ffc107; }
    .indicator.red { background: #f44336; }

    tr.green { border-left: 3px solid #4caf50; }
    tr.yellow { border-left: 3px solid #ffc107; }
    tr.red { border-left: 3px solid #f44336; }

    .empty { color: #888; }

    @media (max-width: 1200px) {
      .cal-table { display: block; overflow-x: auto; }
    }
    @media (max-width: 768px) {
      .cal-table { font-size: 0.8rem; }
      .cal-table th, .cal-table td { padding: 0.25rem 0.35rem; }
      .actions { flex-wrap: wrap; }
    }
  `,
})
export class CalibrationComponent implements OnInit, OnDestroy {
  knobs = signal<CalibrationStatusDto[]>([]);
  trackIdInput = signal<number | null>(null);
  loading = signal(false);
  running = signal(false);
  error = signal('');
  calibrationMsg = signal<string | null>(null);

  fittedCount = computed(() => this.knobs().filter((k) => !k.isDefault).length);
  defaultCount = computed(() => this.knobs().filter((k) => k.isDefault).length);

  private wsSub?: Subscription;

  constructor(
    private calibrationService: CalibrationService,
    private raceService: RaceService
  ) {}

  ngOnInit() {
    this.wsSub = this.raceService.race$.subscribe({
      next: (msg) => this.onWsMessage(msg),
    });
  }

  ngOnDestroy() {
    this.wsSub?.unsubscribe();
  }

  onTrackIdChange(event: Event) {
    const val = (event.target as HTMLInputElement).valueAsNumber;
    this.trackIdInput.set(isNaN(val) ? null : val);
  }

  loadStatus() {
    this.loading.set(true);
    this.error.set('');
    const tid = this.trackIdInput();
    this.calibrationService.getStatus(tid ?? undefined).subscribe({
      next: (data) => {
        this.knobs.set(data);
        this.loading.set(false);
      },
      error: (err) => {
        this.loading.set(false);
        this.error.set(err.error?.message || 'Failed to load calibration status');
      },
    });
  }

  runCalibration() {
    const tid = this.trackIdInput();
    if (tid == null) return;
    this.running.set(true);
    this.error.set('');
    this.calibrationMsg.set(null);
    this.calibrationService.runCalibration(tid).subscribe({
      next: () => {},
      error: (err) => {
        this.running.set(false);
        this.error.set(err.error?.message || 'Failed to trigger calibration');
      },
    });
  }

  knobClass(k: CalibrationStatusDto): string {
    if (k.isDefault) return 'red';
    if (k.confidence < 0.7) return 'yellow';
    return 'green';
  }

  private onWsMessage(msg: RaceMessage) {
    if (msg.type === 'calibrationComplete') {
      this.running.set(false);
      this.calibrationMsg.set(`Complete (${msg.elapsedMs}ms)`);
      this.loadStatus();
    } else if (msg.type === 'calibrationFailed') {
      this.running.set(false);
      this.calibrationMsg.set(`Failed (exit code ${msg.exitCode})`);
    }
  }
}
