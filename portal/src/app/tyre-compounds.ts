const TYRE_COMPOUNDS: Record<number, string> = {
  7: 'Inter',
  8: 'Wet',
  16: 'Soft',
  17: 'Medium',
  18: 'Hard',
};

export function tyreCompoundName(code: number): string {
  return TYRE_COMPOUNDS[code] ?? `Compound ${code}`;
}
