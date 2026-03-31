const TEAM_NAMES: Record<number, string> = {
  0: 'Red Bull Racing',
  1: 'Mercedes',
  2: 'Ferrari',
  3: 'McLaren',
  4: 'Alpine',
  5: 'Aston Martin',
  6: 'Racing Bulls',
  7: 'Haas',
  8: 'Kick Sauber',
  9: 'Williams',
};

export function teamName(id: number): string {
  return TEAM_NAMES[id] ?? `Team ${id}`;
}
