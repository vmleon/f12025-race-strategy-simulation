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
}
