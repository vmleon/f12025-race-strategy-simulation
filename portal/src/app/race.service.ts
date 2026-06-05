import { Injectable } from '@angular/core';
import { Observable, defer, timer } from 'rxjs';
import { retry, share } from 'rxjs/operators';
import { webSocket } from 'rxjs/webSocket';

export interface CarSnapshot {
  idx: number;
  name?: string;
  ai?: boolean;
  pos: number;
  lap: number;
  sector: number;
  lastSectorMs: number[];
  lastLapTimeMs?: number;
  tyre: string;
  tyreAge: number;
  fuel?: number;
  pitStatus: number;
  pits?: number;
  fwDmg?: number;
  flDmg?: number;
  engDmg?: number;
  rwDmg?: number;
  spDmg?: number;
  gbDmg?: number;
  diffDmg?: number;
  tyreWear?: number[];
  brakeTemp?: number[];
  tyreSurfTemp?: number[];
  tyreInnerTemp?: number[];
  pen?: number;
  unservedDT?: number;
  unservedSG?: number;
  warnings?: number;
  resultStatus?: number;
  lapDist?: number;
  teamId?: number;
}

export interface WeatherForecastSample {
  offset: number;
  weather: number;
  trackTemp: number;
  airTemp: number;
  rain: number;
}

export interface SimulationCarResult {
  carIndex: number;
  driverName: string;
  meanPosition: number;
  positionStdDev: number;
  ci95Low: number;
  ci95High: number;
  dnfProbability: number;
  top3Probability: number;
  pointsFinishProbability: number;
  positionDistribution: Record<string, number>;
}

export interface SimulationResult {
  iterations: number;
  converged: boolean;
  wallClockMs: number;
  cars: SimulationCarResult[];
}

export interface StrategyPitStop {
  onLap: number;
  newCompound: number;
}

export interface StrategyCandidate {
  label: string;
  stops: StrategyPitStop[];
}

export interface RankedStrategy {
  rank: number;
  candidate: StrategyCandidate;
  meanPosition: number;
  positionStdDev: number;
  ci95Low: number;
  ci95High: number;
  dnfProbability: number;
  top3Probability: number;
  pointsFinishProbability: number;
  expectedPoints: number;
}

export interface StrategyEvaluationResult {
  playerCarIndex: number;
  strategies: RankedStrategy[];
  // Player pace was uncalibrated (circuit default) → the ranked numbers are not
  // trustworthy and the panel shows an "insufficient calibration" notice instead.
  insufficientCalibration: boolean;
}

export interface RaceMessage {
  type:
    | 'state'
    | 'sessionStarted'
    | 'sessionEnded'
    | 'event'
    | 'simulationResult'
    | 'calibrationComplete'
    | 'calibrationFailed'
    | 'strategyEvaluation';
  sessionUid?: string;
  trackId?: number;
  totalLaps?: number;
  currentLap?: number;
  currentSector?: number;
  weather?: number;
  trackTemp?: number;
  airTemp?: number;
  safetyCarStatus?: number;
  trackLength?: number;
  sessionType?: number;
  sessionTimeLeft?: number;
  sessionDuration?: number;
  cars?: CarSnapshot[];
  forecast?: WeatherForecastSample[];
  // event fields
  event?: string;
  carIndex?: number;
  penaltyType?: number;
  infringementType?: number;
  time?: number;
  lap?: number;
  details?: Record<string, unknown>;
  // simulation result fields
  jobId?: string;
  result?: SimulationResult;
  // calibration fields
  elapsedMs?: number;
  exitCode?: number;
  // strategy evaluation fields
  evaluatedAtLap?: number;
  stale?: boolean;
  evaluation?: StrategyEvaluationResult;
}

@Injectable({ providedIn: 'root' })
export class RaceService {
  readonly race$: Observable<RaceMessage> = defer(() =>
    webSocket<RaceMessage>(
      `${location.protocol === 'https:' ? 'wss:' : 'ws:'}//${location.host}/ws/race`,
    )
  ).pipe(
    retry({ delay: (_err, attempt) => timer(Math.min(1000 * 2 ** attempt, 30000)) }),
    share()
  );
}
