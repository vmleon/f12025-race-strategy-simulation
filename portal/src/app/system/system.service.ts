import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

export interface DayCount {
  day: string;
  count: number;
}

export interface PriorityCount {
  priority: string;
  count: number;
}

export interface SimulationStats {
  total: number;
  avgDurationMs: number;
  avgIterations: number;
  perDay: DayCount[];
  byStatus: Record<string, number>;
}

export interface RadioStats {
  total: number;
  byPriority: PriorityCount[];
  renderedVsFallback: { rendered: number; fallback: number };
  perDay: DayCount[];
}

export interface CalibrationStats {
  totalCoefficients: number;
  totalRuns: number;
  perDay: DayCount[];
}

@Injectable({ providedIn: 'root' })
export class SystemService {
  constructor(private http: HttpClient) {}

  simulations(): Observable<SimulationStats> {
    return this.http.get<SimulationStats>('/api/system/stats/simulations');
  }

  radio(): Observable<RadioStats> {
    return this.http.get<RadioStats>('/api/system/stats/radio');
  }

  calibration(): Observable<CalibrationStats> {
    return this.http.get<CalibrationStats>('/api/system/stats/calibration');
  }
}
