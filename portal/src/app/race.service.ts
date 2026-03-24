import { Injectable } from '@angular/core';
import { webSocket } from 'rxjs/webSocket';

export interface CarSnapshot {
  idx: number;
  pos: number;
  lap: number;
  sector: number;
  lastSectorMs: number[];
  tyre: string;
  tyreAge: number;
  pitStatus: number;
}

export interface RaceSnapshot {
  type: 'state' | 'sessionStarted' | 'sessionEnded';
  sessionUid: string;
  trackId?: number;
  weather?: number;
  trackTemp?: number;
  airTemp?: number;
  safetyCarStatus?: number;
  cars?: CarSnapshot[];
}

@Injectable({ providedIn: 'root' })
export class RaceService {
  readonly race$ = webSocket<RaceSnapshot>(
    `${location.protocol === 'https:' ? 'wss:' : 'ws:'}//${location.host}/ws/race`
  );
}
