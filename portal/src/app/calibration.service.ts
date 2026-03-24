import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';

export interface CalibrationStatusDto {
  knobName: string;
  calibrationRegime: string;
  sectorNumber: number | null;
  value: number;
  confidence: number;
  isDefault: boolean;
  sessionCount: number;
  dataPointCount: number;
  trainedAt: string | null;
}

@Injectable({ providedIn: 'root' })
export class CalibrationService {
  constructor(private http: HttpClient) {}

  getStatus(trackId?: number) {
    const params: Record<string, string> = {};
    if (trackId != null) params['trackId'] = String(trackId);
    return this.http.get<CalibrationStatusDto[]>('/api/calibration/status', { params });
  }

  runCalibration(trackId: number) {
    return this.http.post<{ status: string; trackId: string }>('/api/calibration/run', null, {
      params: { trackId: String(trackId) },
    });
  }
}
