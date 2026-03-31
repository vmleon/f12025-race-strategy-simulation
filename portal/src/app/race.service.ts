import { Injectable } from '@angular/core';
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

export interface RaceMessage {
  type:
    | 'state'
    | 'sessionStarted'
    | 'sessionEnded'
    | 'event'
    | 'simulationResult'
    | 'calibrationComplete'
    | 'calibrationFailed';
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
}

@Injectable({ providedIn: 'root' })
export class RaceService {
  readonly race$ = webSocket<RaceMessage>(
    `${location.protocol === 'https:' ? 'wss:' : 'ws:'}//${location.host}/ws/race`,
  );
}
