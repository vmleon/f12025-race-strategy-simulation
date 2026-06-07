import { Injectable, computed, inject, signal } from '@angular/core';
import { interval } from 'rxjs';
import {
  RaceService,
  CarSnapshot,
  RaceMessage,
  WeatherForecastSample,
  RankedStrategy,
} from '../race.service';
import { SessionService } from '../session.service';
import { trackName } from '../track-names';
import { GapRow } from './gap-indicator/gap-indicator.component';

/**
 * Holds the live-race state for the whole app lifetime. The Race page used to own
 * the WebSocket subscription and all accumulated state, so navigating away (which
 * destroys the component) dropped the socket and wiped the table — returning to the
 * page showed nothing until fresh data arrived. Moving it here keeps the stream and
 * the accumulation alive regardless of which page is shown, so the Race view is
 * always current the instant it mounts.
 */
@Injectable({ providedIn: 'root' })
export class LiveRaceStore {
  private raceService = inject(RaceService);
  private sessionService = inject(SessionService);

  status = signal('waiting...');
  trackId = signal<number | null>(null);
  totalLaps = signal(0);
  currentLap = signal(0);
  weather = signal<number | null>(null);
  trackTemp = signal<number | null>(null);
  airTemp = signal<number | null>(null);
  safetyCarStatus = signal<number | null>(null);
  trackLength = signal(0);
  sessionType = signal<number | null>(null);
  sessionTimeLeft = signal<number | null>(null);
  sessionDuration = signal<number | null>(null);
  yellowSector = signal<number | null>(null);
  cars = signal<CarSnapshot[]>([]);
  events = signal<RaceMessage[]>([]);
  forecast = signal<WeatherForecastSample[]>([]);

  selectedSessionUid = signal<string | null>(null);

  strategyStrategies = signal<RankedStrategy[]>([]);
  strategyEvaluatedAtLap = signal(0);
  strategyStale = signal(false);
  strategyInsufficientCalibration = signal(false);

  private bestLapMap = new Map<number, number>();

  bestLapForCar(idx: number): number | null {
    return this.bestLapMap.get(idx) ?? null;
  }

  // Sector history tracking
  private sectorHistory = new Map<number, { lap: number; sector: number; timeMs: number }[]>();
  private prevSectors = new Map<number, number>();
  private prevLaps = new Map<number, number>();

  gapAhead = signal<GapRow | null>(null);
  gapBehind = signal<GapRow | null>(null);
  lastPlayerLaps = signal<{ lap: number; timeMs: number; tyre: string }[]>([]);

  bestLapOverallMs = signal<number | null>(null);
  bestS1Ms = signal<number | null>(null);
  bestS2Ms = signal<number | null>(null);
  bestS3Ms = signal<number | null>(null);

  private carBestLapSectors = new Map<number, { s1: number; s2: number; s3: number }>();
  private prevCarSnapshot = new Map<number, { s1: number; s2: number; lap: number }>();

  bestTimesRows = signal<
    {
      carIdx: number;
      pos: number | null;
      name: string;
      ai: boolean;
      out: boolean;
      bestS1: number | null;
      bestS2: number | null;
      bestS3: number | null;
      bestLap: number | null;
    }[]
  >([]);

  isFastestSector(car: CarSnapshot, idx: number): boolean {
    const val = car.lastSectorMs?.[idx];
    if (!val || val <= 0) return false;
    if (idx === 0) return val === this.bestS1Ms();
    if (idx === 1) return val === this.bestS2Ms();
    return false;
  }

  playerCar = computed(() => this.cars().find((c) => !c.ai) ?? null);
  penaltyEvents = computed(() => this.events().filter((e) => e.event === 'PENA'));

  trackLabel = computed(() => trackName(this.trackId()!));
  weatherLabel = computed(() => {
    const w = this.weather();
    if (w == null) return '';
    const labels = ['Clear', 'Light Cloud', 'Overcast', 'Light Rain', 'Heavy Rain', 'Storm'];
    return labels[w] ?? `Weather ${w}`;
  });
  showSessionRemaining = computed(() => {
    const t = this.sessionType();
    if (t == null) return false;
    return (t >= 1 && t <= 9) || t === 14;
  });
  sessionRemainingLabel = computed(() => {
    const s = this.sessionTimeLeft();
    if (s == null) return '';
    const m = Math.floor(s / 60);
    const sec = s % 60;
    return `${m}:${sec.toString().padStart(2, '0')}`;
  });

  safetyCarLabel = computed(() => {
    const s = this.safetyCarStatus();
    if (s === 1) return 'Full SC';
    if (s === 2) return 'VSC';
    if (s === 3) return 'Formation Lap';
    return 'SC';
  });

  private started = false;

  /**
   * Start the live stream once, then keep it running for the app lifetime. Safe to
   * call on every Race-page mount — subsequent calls are no-ops, so the accumulated
   * state survives navigation. The subscriptions are intentionally never torn down.
   */
  ensureStarted(): void {
    if (this.started) return;
    this.started = true;

    this.fetchActiveSessions();
    interval(10_000).subscribe(() => this.fetchActiveSessions());

    this.raceService.race$.subscribe({
      next: (msg) => this.onMessage(msg),
      error: () => this.status.set('disconnected'),
    });
  }

  private fetchActiveSessions() {
    this.sessionService.getActiveSessions().subscribe((sessions) => {
      const selected = this.selectedSessionUid();
      if (sessions.length === 0) {
        this.selectedSessionUid.set(null);
      } else if (!selected || !sessions.some((s) => s.sessionUid === selected)) {
        const preferred = sessions.find((s) => s.live) ?? sessions[0];
        this.selectedSessionUid.set(preferred.sessionUid);
        this.resetState();
      }
    });
  }

  private resetState() {
    this.status.set('waiting...');
    this.cars.set([]);
    this.events.set([]);
    this.trackId.set(null);
    this.totalLaps.set(0);
    this.currentLap.set(0);
    this.weather.set(null);
    this.trackTemp.set(null);
    this.airTemp.set(null);
    this.safetyCarStatus.set(null);
    this.trackLength.set(0);
    this.sessionType.set(null);
    this.sessionTimeLeft.set(null);
    this.sessionDuration.set(null);
    this.yellowSector.set(null);
    this.forecast.set([]);
    this.strategyStrategies.set([]);
    this.strategyEvaluatedAtLap.set(0);
    this.strategyStale.set(false);
    this.strategyInsufficientCalibration.set(false);
    this.sectorHistory.clear();
    this.prevSectors.clear();
    this.prevLaps.clear();
    this.gapAhead.set(null);
    this.gapBehind.set(null);
    this.lastPlayerLaps.set([]);
    this.bestLapMap.clear();
    this.bestLapOverallMs.set(null);
    this.bestS1Ms.set(null);
    this.bestS2Ms.set(null);
    this.bestS3Ms.set(null);
    this.carBestLapSectors.clear();
    this.prevCarSnapshot.clear();
    this.bestTimesRows.set([]);
  }

  private updateSectorHistory(cars: CarSnapshot[]) {
    for (const car of cars) {
      const prevSector = this.prevSectors.get(car.idx);
      const prevLap = this.prevLaps.get(car.idx);
      this.prevSectors.set(car.idx, car.sector);
      this.prevLaps.set(car.idx, car.lap);

      if (prevSector === undefined || prevLap === undefined) continue;
      if (car.sector === prevSector && car.lap === prevLap) continue;

      let completedSector: number;
      let completedLap: number;
      let timeMs: number;

      if (prevSector === 0 && car.sector === 1) {
        completedSector = 0;
        completedLap = car.lap;
        timeMs = car.lastSectorMs[0];
      } else if (prevSector === 1 && car.sector === 2) {
        completedSector = 1;
        completedLap = car.lap;
        timeMs = car.lastSectorMs[1] - car.lastSectorMs[0];
      } else if (prevSector === 2 && car.sector === 0) {
        completedSector = 2;
        completedLap = prevLap;
        if (car.lastLapTimeMs && car.lastSectorMs[1] > 0) {
          timeMs = car.lastLapTimeMs - car.lastSectorMs[1];
        } else {
          continue;
        }
      } else {
        continue;
      }

      if (timeMs <= 0) continue;

      const history = this.sectorHistory.get(car.idx) ?? [];
      history.push({ lap: completedLap, sector: completedSector, timeMs });
      if (history.length > 6) history.splice(0, history.length - 6);
      this.sectorHistory.set(car.idx, history);
    }
  }

  private updateGapDeltas() {
    const allCars = this.cars();
    const player = allCars.find((c) => !c.ai);
    if (!player) {
      this.gapAhead.set(null);
      this.gapBehind.set(null);
      return;
    }

    const activeCars = allCars.filter((c) => (c.resultStatus ?? 2) < 4);
    const sorted = [...activeCars].sort((a, b) => a.pos - b.pos);
    const playerIdx = sorted.findIndex((c) => c.idx === player.idx);

    const carAhead = playerIdx > 0 ? sorted[playerIdx - 1] : null;
    const carBehind = playerIdx < sorted.length - 1 ? sorted[playerIdx + 1] : null;

    this.gapAhead.set(carAhead ? this.computeGapRow(player, carAhead) : null);
    this.gapBehind.set(carBehind ? this.computeGapRow(player, carBehind) : null);
  }

  private updateBestTimes(cars: CarSnapshot[]) {
    let overallLap = this.bestLapOverallMs();
    let overallS1 = this.bestS1Ms();
    let overallS2 = this.bestS2Ms();
    let overallS3 = this.bestS3Ms();

    for (const car of cars) {
      const s1 = car.lastSectorMs?.[0] ?? 0;
      const s2 = car.lastSectorMs?.[1] ?? 0;

      // Track overall fastest sectors across the whole field for the "purple"
      // highlight. These can come from any lap, including non-best ones.
      if (s1 > 0 && (overallS1 === null || s1 < overallS1)) overallS1 = s1;
      if (s2 > 0 && (overallS2 === null || s2 < overallS2)) overallS2 = s2;

      // On lap rollover, fold the just-completed lap's sector triple into the
      // best-lap record. The displayed sectors must come from the best lap as
      // a whole — not from independent per-sector personal bests, which would
      // produce a mismatched S1+S2+S3 ≠ best-lap total.
      if (car.lastLapTimeMs && car.lastLapTimeMs > 0) {
        const prevSnap = this.prevCarSnapshot.get(car.idx);
        if (
          prevSnap &&
          car.lap > prevSnap.lap &&
          prevSnap.s1 > 0 &&
          prevSnap.s2 > 0 &&
          car.lastLapTimeMs > prevSnap.s1 + prevSnap.s2
        ) {
          const lapS1 = prevSnap.s1;
          const lapS2 = prevSnap.s2;
          const lapS3 = car.lastLapTimeMs - lapS1 - lapS2;

          if (overallS3 === null || lapS3 < overallS3) overallS3 = lapS3;

          const prevBest = this.bestLapMap.get(car.idx);
          if (prevBest === undefined || car.lastLapTimeMs < prevBest) {
            this.bestLapMap.set(car.idx, car.lastLapTimeMs);
            this.carBestLapSectors.set(car.idx, { s1: lapS1, s2: lapS2, s3: lapS3 });
          }
          if (overallLap === null || car.lastLapTimeMs < overallLap) {
            overallLap = car.lastLapTimeMs;
          }
        }
      }

      this.prevCarSnapshot.set(car.idx, { s1, s2, lap: car.lap });
    }

    if (overallLap !== this.bestLapOverallMs()) this.bestLapOverallMs.set(overallLap);
    if (overallS1 !== this.bestS1Ms()) this.bestS1Ms.set(overallS1);
    if (overallS2 !== this.bestS2Ms()) this.bestS2Ms.set(overallS2);
    if (overallS3 !== this.bestS3Ms()) this.bestS3Ms.set(overallS3);
  }

  private buildBestTimesRows(cars: CarSnapshot[]) {
    const rows = cars
      .filter((c) => (c.resultStatus ?? 2) >= 2)
      .map((c) => ({
        carIdx: c.idx,
        pos: null as number | null,
        name: c.name || `Car ${c.idx}`,
        ai: c.ai ?? true,
        out: (c.resultStatus ?? 2) >= 4,
        bestS1: this.carBestLapSectors.get(c.idx)?.s1 ?? null,
        bestS2: this.carBestLapSectors.get(c.idx)?.s2 ?? null,
        bestS3: this.carBestLapSectors.get(c.idx)?.s3 ?? null,
        bestLap: this.bestLapMap.get(c.idx) ?? null,
        _posFallback: c.pos,
      }));

    rows.sort((a, b) => {
      if (a.out !== b.out) return a.out ? 1 : -1;
      if (a.bestLap != null && b.bestLap != null) return a.bestLap - b.bestLap;
      if (a.bestLap != null) return -1;
      if (b.bestLap != null) return 1;
      return a._posFallback - b._posFallback;
    });

    let counter = 0;
    for (const r of rows) {
      if (!r.out) {
        counter += 1;
        r.pos = counter;
      }
    }
    return rows.map(({ _posFallback, ...rest }) => rest);
  }

  private updatePlayerLapHistory(cars: CarSnapshot[]) {
    const player = cars.find((c) => !c.ai);
    if (!player || !player.lastLapTimeMs || player.lastLapTimeMs <= 0) return;
    if (player.lap < 2) return; // need at least one completed lap

    const completedLap = player.lap - 1;
    const history = this.lastPlayerLaps();
    const lastEntry = history.length > 0 ? history[history.length - 1] : null;
    if (lastEntry && lastEntry.lap >= completedLap) return;

    const updated = [
      ...history,
      { lap: completedLap, timeMs: player.lastLapTimeMs, tyre: player.tyre },
    ];
    if (updated.length > 5) updated.splice(0, updated.length - 5);
    this.lastPlayerLaps.set(updated);
  }

  private computeGapRow(player: CarSnapshot, other: CarSnapshot): GapRow {
    const playerHist = this.sectorHistory.get(player.idx) ?? [];
    const otherHist = this.sectorHistory.get(other.idx) ?? [];

    const otherMap = new Map<string, number>();
    for (const e of otherHist) {
      otherMap.set(`${e.lap}-${e.sector}`, e.timeMs);
    }

    const common: { playerMs: number; otherMs: number }[] = [];
    for (let i = playerHist.length - 1; i >= 0 && common.length < 3; i--) {
      const e = playerHist[i];
      const otherMs = otherMap.get(`${e.lap}-${e.sector}`);
      if (otherMs !== undefined) {
        common.unshift({ playerMs: e.timeMs, otherMs });
      }
    }

    const deltas: (number | null)[] = [];
    for (let i = 0; i < 3; i++) {
      if (i < common.length) {
        deltas.push(common[i].otherMs - common[i].playerMs);
      } else {
        deltas.push(null);
      }
    }

    const code = (other.name ?? `Car ${other.idx}`).substring(0, 3).toUpperCase();
    return { driverCode: code, deltas };
  }

  private onMessage(msg: RaceMessage) {
    // Live-follow: a session-defining message (state / sessionStarted) for a
    // different session than the one selected means the game has moved on (e.g.
    // Qualy -> Race). Adopt it immediately and reset, instead of dropping it.
    // Only the live game streams state, so its sessionUid is unambiguous — this
    // sidesteps the active-sessions poll lagging, a missed sessionStarted across a
    // WebSocket reconnect, and a lingering/orphaned previous session keeping the
    // old selection pinned (which froze the live view on stale qualy data).
    if (
      msg.sessionUid &&
      msg.sessionUid !== this.selectedSessionUid() &&
      (msg.type === 'state' || msg.type === 'sessionStarted')
    ) {
      this.selectedSessionUid.set(msg.sessionUid);
      this.resetState();
    }

    if (
      msg.sessionUid &&
      this.selectedSessionUid() &&
      msg.sessionUid !== this.selectedSessionUid()
    ) {
      return;
    }

    switch (msg.type) {
      case 'state':
        this.status.set('live');
        if (msg.trackId != null) this.trackId.set(msg.trackId);
        if (msg.totalLaps != null) this.totalLaps.set(msg.totalLaps);
        if (msg.currentLap != null) this.currentLap.set(msg.currentLap);
        if (msg.weather != null) this.weather.set(msg.weather);
        if (msg.trackTemp != null) this.trackTemp.set(msg.trackTemp);
        if (msg.airTemp != null) this.airTemp.set(msg.airTemp);
        if (msg.safetyCarStatus != null) this.safetyCarStatus.set(msg.safetyCarStatus);
        if (msg.yellowSectors != null) {
          this.yellowSector.set(msg.yellowSectors.length > 0 ? msg.yellowSectors[0] : null);
        }
        if (msg.trackLength != null) this.trackLength.set(msg.trackLength);
        if (msg.sessionType != null) this.sessionType.set(msg.sessionType);
        if (msg.sessionTimeLeft != null) this.sessionTimeLeft.set(msg.sessionTimeLeft);
        if (msg.sessionDuration != null) this.sessionDuration.set(msg.sessionDuration);
        if (msg.cars) {
          const active = msg.cars.filter((c) => (c.resultStatus ?? 2) >= 2);
          const racing = active
            .filter((c) => (c.resultStatus ?? 2) < 4)
            .sort((a, b) => a.pos - b.pos);
          const out = active
            .filter((c) => (c.resultStatus ?? 2) >= 4)
            .sort((a, b) => a.pos - b.pos);
          this.cars.set([...racing, ...out]);
          this.updateSectorHistory(msg.cars!);
          this.updateGapDeltas();
          this.updatePlayerLapHistory(msg.cars!);
          this.updateBestTimes(msg.cars!);
          this.bestTimesRows.set(this.buildBestTimesRows(msg.cars!));
        }
        if (msg.forecast) {
          this.forecast.set(msg.forecast);
        }
        break;
      case 'sessionStarted':
        this.status.set('session started');
        this.cars.set([]);
        this.events.set([]);
        this.forecast.set([]);
        this.sectorHistory.clear();
        this.prevSectors.clear();
        this.prevLaps.clear();
        this.gapAhead.set(null);
        this.gapBehind.set(null);
        this.lastPlayerLaps.set([]);
        this.bestLapMap.clear();
        this.bestLapOverallMs.set(null);
        this.bestS1Ms.set(null);
        this.bestS2Ms.set(null);
        this.bestS3Ms.set(null);
        this.carBestLapSectors.clear();
        this.prevCarSnapshot.clear();
        this.bestTimesRows.set([]);
        if (msg.trackId != null) this.trackId.set(msg.trackId);
        this.fetchActiveSessions();
        break;
      case 'sessionEnded':
        if (msg.sessionUid === this.selectedSessionUid()) {
          this.status.set('session ended');
          this.fetchActiveSessions();
        }
        break;
      case 'event':
        this.events.update((evs) => [msg, ...evs].slice(0, 10));
        break;
      case 'strategyEvaluation':
        if (msg.evaluation) {
          this.strategyStrategies.set(msg.evaluation.strategies);
          this.strategyEvaluatedAtLap.set(msg.evaluatedAtLap ?? 0);
          this.strategyStale.set(msg.stale ?? false);
          this.strategyInsufficientCalibration.set(msg.evaluation.insufficientCalibration ?? false);
        }
        break;
    }
  }
}
