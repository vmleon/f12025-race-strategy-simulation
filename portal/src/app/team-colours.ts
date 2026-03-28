const TEAM_COLOURS: Record<number, string> = {
  0: '#3671C6', // Red Bull Racing
  1: '#27F4D2', // Mercedes
  2: '#E80020', // Ferrari
  3: '#FF8000', // McLaren
  4: '#64C4FF', // Alpine
  5: '#00E701', // Aston Martin
  6: '#1868DB', // RB (Visa Cash App)
  7: '#B6BABD', // Haas
  8: '#52E252', // Kick Sauber
  9: '#E0005E', // Williams
};

const DEFAULT_COLOUR = '#888888';

export function teamColour(teamId: number): string {
  return TEAM_COLOURS[teamId] ?? DEFAULT_COLOUR;
}
