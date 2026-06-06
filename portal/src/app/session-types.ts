const SESSION_TYPES: Record<number, string> = {
  0: 'Unknown',
  1: 'Practice 1',
  2: 'Practice 2',
  3: 'Practice 3',
  4: 'Short Practice',
  5: 'Qualifying 1',
  6: 'Qualifying 2',
  7: 'Qualifying 3',
  8: 'Short Qualifying',
  9: 'One-Shot Qualifying',
  10: 'Race',
  11: 'Race 2',
  12: 'Race 3',
  13: 'Time Trial',
  // F1 25 emits the 14-17 band too (15 = Race confirmed empirically). The bundled
  // appendix only documents 0-13; keep in sync with backend SessionKind.
  14: 'Sprint Qualifying',
  15: 'Race',
  16: 'Race 2',
  17: 'Race 3',
};

export function sessionTypeName(code: number): string {
  return SESSION_TYPES[code] ?? `Type ${code}`;
}
