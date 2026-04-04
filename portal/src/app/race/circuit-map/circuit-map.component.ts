import { Component, Input, OnChanges, OnInit, SimpleChanges } from '@angular/core';
import { CarSnapshot } from '../../race.service';
import { teamColour, teamColourSecondary } from '../../team-colours';
import DEFAULT_CIRCUIT from '../../../assets/circuits/default.json';

interface CircuitConfig {
  name: string;
  sectors: number[];
  turns: number[];
  pitEntry: number;
  pitExit: number;
  drsZones: { from: number; to: number }[];
}

interface CarDot {
  x: number;
  y: number;
  colour: string;
  label: string;
  isPlayer: boolean;
  inPit: boolean;
}

const CX = 200;
const CY = 200;
const R = 160;
const PIT_R = 110;
const DOT_R = 6;
const PLAYER_DOT_R = 8;

@Component({
  selector: 'app-circuit-map',
  template: `
    <svg
      [attr.viewBox]="'0 0 400 400'"
      class="circuit-map"
      xmlns="http://www.w3.org/2000/svg"
    >
      <!-- Main track circle -->
      <circle [attr.cx]="cx" [attr.cy]="cy" [attr.r]="r" class="track" />

      <!-- DRS zones -->
      @for (arc of drsArcs; track $index) {
        <path [attr.d]="arc" class="drs-zone" />
      }

      <!-- Yellow flag sectors -->
      @for (arc of flagArcs; track $index) {
        <path [attr.d]="arc" class="flag-yellow" />
      }

      <!-- Red flag overlay -->
      @if (redFlag) {
        <circle [attr.cx]="cx" [attr.cy]="cy" [attr.r]="r" class="flag-red" />
      }

      <!-- Sector dividers -->
      @for (line of sectorLines; track $index) {
        <line
          [attr.x1]="line.x1"
          [attr.y1]="line.y1"
          [attr.x2]="line.x2"
          [attr.y2]="line.y2"
          class="sector-line"
        />
      }

      <!-- Sector labels -->
      @for (lbl of sectorLabels; track $index) {
        <text [attr.x]="lbl.x" [attr.y]="lbl.y" class="sector-label">{{ lbl.text }}</text>
      }

      <!-- Turn markers -->
      @for (turn of turnMarkers; track $index) {
        <circle [attr.cx]="turn.x" [attr.cy]="turn.y" r="3" class="turn-marker" />
        <text [attr.x]="turn.tx" [attr.y]="turn.ty" class="turn-label">{{ turn.num }}</text>
      }

      <!-- Pit lane arc -->
      <path [attr.d]="pitLanePath" class="pit-lane" />
      <text [attr.x]="cx" [attr.y]="cy + pitR + 14" class="pit-label">PIT</text>

      <!-- Car dots -->
      @for (car of carDots; track car.label) {
        <circle
          [attr.cx]="car.x"
          [attr.cy]="car.y"
          [attr.r]="car.isPlayer ? playerDotR : dotR"
          [attr.fill]="car.colour"
          [class.player-dot]="car.isPlayer"
          class="car-dot"
        />
        <text
          [attr.x]="car.x"
          [attr.y]="car.y - (car.isPlayer ? playerDotR : dotR) - 4"
          [attr.fill]="car.colour"
          class="car-label"
        >
          {{ car.label }}
        </text>
      }
    </svg>
  `,
  styles: `
    .circuit-map {
      width: 100%;
      max-width: 400px;
      height: auto;
    }
    .track {
      fill: none;
      stroke: #444;
      stroke-width: 14;
    }
    .drs-zone {
      fill: none;
      stroke: #4caf50;
      stroke-width: 16;
      opacity: 0.35;
    }
    .flag-yellow {
      fill: none;
      stroke: #f9a825;
      stroke-width: 16;
      opacity: 0.5;
    }
    .flag-red {
      fill: none;
      stroke: #e53935;
      stroke-width: 16;
      opacity: 0.5;
    }
    .sector-line {
      stroke: #666;
      stroke-width: 1;
      stroke-dasharray: 4 3;
    }
    .sector-label {
      fill: #888;
      font-size: 11px;
      text-anchor: middle;
      dominant-baseline: middle;
    }
    .turn-marker {
      fill: #555;
    }
    .turn-label {
      fill: #666;
      font-size: 8px;
      text-anchor: middle;
      dominant-baseline: middle;
    }
    .pit-lane {
      fill: none;
      stroke: #555;
      stroke-width: 4;
      stroke-dasharray: 6 4;
    }
    .pit-label {
      fill: #666;
      font-size: 10px;
      text-anchor: middle;
    }
    .car-dot {
      stroke: #000;
      stroke-width: 1;
    }
    .car-dot.player-dot {
      stroke: #fff;
      stroke-width: 2;
    }
    .car-label {
      font-size: 9px;
      text-anchor: middle;
      font-weight: bold;
    }
  `,
})
export class CircuitMapComponent implements OnInit, OnChanges {
  @Input() cars: CarSnapshot[] = [];
  @Input() trackLength = 0;
  @Input() safetyCarStatus: number | null = null;
  @Input() yellowSector: number | null = null;

  readonly cx = CX;
  readonly cy = CY;
  readonly r = R;
  readonly pitR = PIT_R;
  readonly dotR = DOT_R;
  readonly playerDotR = PLAYER_DOT_R;

  circuit: CircuitConfig = DEFAULT_CIRCUIT;

  sectorLines: { x1: number; y1: number; x2: number; y2: number }[] = [];
  sectorLabels: { x: number; y: number; text: string }[] = [];
  turnMarkers: { x: number; y: number; tx: number; ty: number; num: number }[] = [];
  drsArcs: string[] = [];
  flagArcs: string[] = [];
  redFlag = false;
  pitLanePath = '';
  carDots: CarDot[] = [];

  ngOnInit() {
    this.buildStaticElements();
  }

  ngOnChanges(changes: SimpleChanges) {
    if (changes['cars'] || changes['trackLength']) {
      this.buildCarDots();
    }
    if (changes['safetyCarStatus'] || changes['yellowSector']) {
      this.buildFlags();
    }
  }

  private buildStaticElements() {
    const c = this.circuit;

    // Sector divider lines
    this.sectorLines = c.sectors.map((pct) => {
      const angle = pctToAngle(pct);
      const inner = polarToXY(CX, CY, R - 20, angle);
      const outer = polarToXY(CX, CY, R + 20, angle);
      return { x1: inner.x, y1: inner.y, x2: outer.x, y2: outer.y };
    });

    // Sector labels (midpoint of each sector arc)
    const sectorBounds = [0, ...c.sectors, 1];
    this.sectorLabels = [];
    for (let i = 0; i < sectorBounds.length - 1; i++) {
      const mid = (sectorBounds[i] + sectorBounds[i + 1]) / 2;
      const angle = pctToAngle(mid);
      const pos = polarToXY(CX, CY, R + 28, angle);
      this.sectorLabels.push({ x: pos.x, y: pos.y, text: `S${i + 1}` });
    }

    // Turn markers
    this.turnMarkers = c.turns.map((pct, i) => {
      const angle = pctToAngle(pct);
      const pos = polarToXY(CX, CY, R, angle);
      const labelPos = polarToXY(CX, CY, R - 20, angle);
      return { x: pos.x, y: pos.y, tx: labelPos.x, ty: labelPos.y, num: i + 1 };
    });

    // DRS zone arcs
    this.drsArcs = c.drsZones.map((z) => arcPath(CX, CY, R, pctToAngle(z.from), pctToAngle(z.to)));

    // Pit lane arc
    const pitStartAngle = pctToAngle(c.pitEntry);
    const pitEndAngle = pctToAngle(c.pitExit);
    this.pitLanePath = arcPath(CX, CY, PIT_R, pitStartAngle, pitEndAngle);
  }

  private buildCarDots() {
    if (!this.trackLength || this.trackLength === 0) {
      this.carDots = [];
      return;
    }

    // Determine which cars are the "second" driver on their team (higher idx)
    const teamFirstIdx = new Map<number, number>();
    for (const car of this.cars) {
      if (car.teamId == null) continue;
      const existing = teamFirstIdx.get(car.teamId);
      if (existing === undefined || car.idx < existing) {
        teamFirstIdx.set(car.teamId, car.idx);
      }
    }

    this.carDots = this.cars
      .filter((car) => car.lapDist != null && car.resultStatus !== 0)
      .map((car) => {
        const pct = Math.max(0, car.lapDist!) / this.trackLength;
        const inPit = car.pitStatus > 0;
        const radius = inPit ? PIT_R : R;
        const angle = pctToAngle(pct);
        const pos = polarToXY(CX, CY, radius, angle);
        const abbr = car.name ? car.name.substring(0, 3).toUpperCase() : `C${car.idx}`;
        const isSecondary = car.teamId != null && teamFirstIdx.get(car.teamId) !== car.idx;
        const colour = isSecondary
          ? teamColourSecondary(car.teamId ?? 0)
          : teamColour(car.teamId ?? 0);

        return {
          x: pos.x,
          y: pos.y,
          colour,
          label: abbr,
          isPlayer: !car.ai,
          inPit,
        };
      });
  }

  private buildFlags() {
    this.flagArcs = [];
    this.redFlag = false;

    if (this.yellowSector != null) {
      const c = this.circuit;
      const bounds = [0, ...c.sectors, 1];
      const s = this.yellowSector;
      if (s >= 0 && s < bounds.length - 1) {
        const from = bounds[s];
        const to = bounds[s + 1];
        this.flagArcs.push(arcPath(CX, CY, R, pctToAngle(from), pctToAngle(to)));
      }
    }
  }
}

/** Convert a lap-distance percentage (0..1) to angle in radians. 0% = top (12 o'clock), clockwise. */
function pctToAngle(pct: number): number {
  return -Math.PI / 2 + pct * 2 * Math.PI;
}

function polarToXY(cx: number, cy: number, r: number, angle: number): { x: number; y: number } {
  return { x: cx + r * Math.cos(angle), y: cy + r * Math.sin(angle) };
}

/** SVG arc path from startAngle to endAngle (radians), going clockwise. */
function arcPath(cx: number, cy: number, r: number, startAngle: number, endAngle: number): string {
  // Normalize so the arc always goes clockwise from start to end
  let sweep = endAngle - startAngle;
  if (sweep < 0) sweep += 2 * Math.PI;
  const largeArc = sweep > Math.PI ? 1 : 0;

  const start = polarToXY(cx, cy, r, startAngle);
  const end = polarToXY(cx, cy, r, endAngle);

  return `M ${start.x} ${start.y} A ${r} ${r} 0 ${largeArc} 1 ${end.x} ${end.y}`;
}
