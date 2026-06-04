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
  baselineFitted: boolean;
  degFitted: boolean;
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
}
