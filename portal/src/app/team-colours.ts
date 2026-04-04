const TEAM_COLOURS: Record<number, string> = {
  0: '#3671C6', // Red Bull Racing
  1: '#27F4D2', // Mercedes
  2: '#E80020', // Ferrari
  3: '#FF8000', // McLaren
  4: '#0093CC', // Alpine
  5: '#229971', // Aston Martin
  6: '#6692FF', // RB (Visa Cash App)
  7: '#B6BABD', // Haas
  8: '#52E252', // Kick Sauber
  9: '#00A3E0', // Williams
};

const DEFAULT_COLOUR = '#888888';

// Precomputed lighter variants for the second driver on each team
const TEAM_COLOURS_SECONDARY: Record<number, string> = Object.fromEntries(
  Object.entries(TEAM_COLOURS).map(([k, v]) => [Number(k), lightenHex(v, 0.15)])
);

export function teamColour(teamId: number): string {
  return TEAM_COLOURS[teamId] ?? DEFAULT_COLOUR;
}

export function teamColourSecondary(teamId: number): string {
  return TEAM_COLOURS_SECONDARY[teamId] ?? lightenHex(DEFAULT_COLOUR, 0.15);
}

function lightenHex(hex: string, amount: number): string {
  const r = parseInt(hex.slice(1, 3), 16) / 255;
  const g = parseInt(hex.slice(3, 5), 16) / 255;
  const b = parseInt(hex.slice(5, 7), 16) / 255;

  const max = Math.max(r, g, b);
  const min = Math.min(r, g, b);
  let h = 0;
  let s = 0;
  let l = (max + min) / 2;

  if (max !== min) {
    const d = max - min;
    s = l > 0.5 ? d / (2 - max - min) : d / (max + min);
    if (max === r) h = ((g - b) / d + (g < b ? 6 : 0)) / 6;
    else if (max === g) h = ((b - r) / d + 2) / 6;
    else h = ((r - g) / d + 4) / 6;
  }

  l = Math.min(1, l + amount);

  const hue2rgb = (p: number, q: number, t: number) => {
    if (t < 0) t += 1;
    if (t > 1) t -= 1;
    if (t < 1 / 6) return p + (q - p) * 6 * t;
    if (t < 1 / 2) return q;
    if (t < 2 / 3) return p + (q - p) * (2 / 3 - t) * 6;
    return p;
  };

  let rr: number, gg: number, bb: number;
  if (s === 0) {
    rr = gg = bb = l;
  } else {
    const q = l < 0.5 ? l * (1 + s) : l + s - l * s;
    const p = 2 * l - q;
    rr = hue2rgb(p, q, h + 1 / 3);
    gg = hue2rgb(p, q, h);
    bb = hue2rgb(p, q, h - 1 / 3);
  }

  const toHex = (c: number) =>
    Math.round(c * 255)
      .toString(16)
      .padStart(2, '0');
  return `#${toHex(rr)}${toHex(gg)}${toHex(bb)}`;
}
