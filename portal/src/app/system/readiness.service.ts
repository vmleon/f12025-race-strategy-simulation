import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

export interface ReadinessReasons {
  outlier: number;
  invalid: number;
  cornerCut: number;
  pit: number;
  safetyCar: number;
  damage: number;
  standingStart: number;
}

export interface SectorReadiness {
  sector: number;
  good: number;
  total: number;
  confidence: number;
}

export interface CompoundReadiness {
  compound: number;
  name: string;
  total: number;
  good: number;
  reasons: ReadinessReasons;
  confidence: number;
  wearFitted: boolean;
  baselineFitted: boolean;
  degFitted: boolean;
  degSamples: number;
  degClamped: boolean;
  degLowConfidence: boolean;
  sectors: SectorReadiness[];
}

export interface ReadinessResponse {
  trackId: number;
  trackName: string;
  calibrationLastRanAt: string | null;
  overallConfidence: number;
  fuelEffectFitted: boolean;
  compounds: CompoundReadiness[];
}

export interface TrackOption {
  trackId: number;
  trackName: string;
  lastSessionAt: string;
}

export interface ScatterPoint {
  age: number;
  timeMs: number;
  used: boolean;
  current: boolean;
}

export interface ScatterRegression {
  slope: number;
  intercept: number;
  n: number;
}

export interface CompoundScatter {
  compound: number;
  points: ScatterPoint[];
  regression: ScatterRegression | null;
}

export interface SectorScatter {
  sector: number;
  compounds: CompoundScatter[];
}

export interface ScatterResponse {
  trackId: number;
  currentSessionUid: string | null;
  sectors: SectorScatter[];
}

// ── coverage / data-sufficiency charts (item 6) ──
export interface CoverageCell {
  compound: number;
  regime: string;
  sector: number;
  count: number;
}

export interface WearPoint {
  age: number;
  wearPct: number;
}

export interface WearCompound {
  compound: number;
  points: WearPoint[];
}

export interface FitConfidence {
  knob: string;
  regime: string;
  sector: number | null;
  samples: number | null;
  rSquared: number | null;
  clamped: boolean;
}

export interface AccuracyPoint {
  predicted: number;
  actual: number;
  absError: number;
}

export interface SectorTimePoint {
  compound: number;
  sector: number;
  timeMs: number;
  outlier: boolean;
}

export interface PitLossPoint {
  regime: string;
  lossMs: number;
}

export interface RegimeDeg {
  compound: number;
  regime: string;
  slopeMsPerLap: number;
  samples: number;
}

@Injectable({ providedIn: 'root' })
export class ReadinessService {
  constructor(private http: HttpClient) {}

  tracks(): Observable<TrackOption[]> {
    return this.http.get<TrackOption[]>('/api/system/readiness/tracks');
  }

  readiness(trackId?: number): Observable<ReadinessResponse> {
    const q = trackId != null ? `?trackId=${trackId}` : '';
    return this.http.get<ReadinessResponse>(`/api/system/readiness${q}`);
  }

  scatter(trackId?: number): Observable<ScatterResponse> {
    const q = trackId != null ? `?trackId=${trackId}` : '';
    return this.http.get<ScatterResponse>(`/api/system/readiness/scatter${q}`);
  }

  coverage(trackId?: number): Observable<CoverageCell[]> {
    const q = trackId != null ? `?trackId=${trackId}` : '';
    return this.http.get<CoverageCell[]>(`/api/system/readiness/coverage${q}`);
  }

  wearScatter(trackId?: number): Observable<WearCompound[]> {
    const q = trackId != null ? `?trackId=${trackId}` : '';
    return this.http.get<WearCompound[]>(`/api/system/readiness/wear-scatter${q}`);
  }

  fitConfidence(trackId?: number): Observable<FitConfidence[]> {
    const q = trackId != null ? `?trackId=${trackId}` : '';
    return this.http.get<FitConfidence[]>(`/api/system/readiness/fit-confidence${q}`);
  }

  accuracy(): Observable<AccuracyPoint[]> {
    return this.http.get<AccuracyPoint[]>('/api/system/readiness/accuracy');
  }

  sectorTimes(trackId?: number): Observable<SectorTimePoint[]> {
    const q = trackId != null ? `?trackId=${trackId}` : '';
    return this.http.get<SectorTimePoint[]>(`/api/system/readiness/sector-times${q}`);
  }

  pitLoss(trackId?: number): Observable<PitLossPoint[]> {
    const q = trackId != null ? `?trackId=${trackId}` : '';
    return this.http.get<PitLossPoint[]>(`/api/system/readiness/pit-loss${q}`);
  }

  regimeDeg(trackId?: number): Observable<RegimeDeg[]> {
    const q = trackId != null ? `?trackId=${trackId}` : '';
    return this.http.get<RegimeDeg[]>(`/api/system/readiness/regime-deg${q}`);
  }
}
