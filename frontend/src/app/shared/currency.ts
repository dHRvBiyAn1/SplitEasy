/** Finite catalogue mirrored from authenticated GET /currencies; identity never comes from a preference. */
export const CURRENCIES = ['USD', 'EUR', 'GBP', 'INR', 'JPY', 'KWD'] as const;
export type Currency = (typeof CURRENCIES)[number];
export const CURRENCY_EXPONENT: Record<Currency, number> = {
  USD: 2,
  EUR: 2,
  GBP: 2,
  INR: 2,
  JPY: 0,
  KWD: 3,
};
export const MAX_TRANSACTION_MINOR = 1_000_000_000_000;
export type MoneyInput =
  | { currency: Currency; amountMinor: number; amountCents?: never }
  | { amountCents: number; amountMinor?: never; currency?: 'USD' };

/** Decimal text to integer minor units. Round half away from zero without floating multiplication. */
export function amountToMinor(
  value: string | number,
  currency: Currency = 'USD',
  round = true,
): number {
  const match = /^([+-]?)(?:(\d+)(?:\.(\d*))?|\.(\d+))$/.exec(String(value).trim());
  if (!match) return NaN;
  const exponent = CURRENCY_EXPONENT[currency];
  const fraction = match[3] ?? match[4] ?? '';
  if (!round && /[1-9]/.test(fraction.slice(exponent))) return NaN;
  const unit = 10n ** BigInt(exponent);
  let amount =
    BigInt(match[2] ?? '0') * unit +
    BigInt(fraction.padEnd(exponent, '0').slice(0, exponent) || '0');
  if (fraction.length > exponent && fraction[exponent] >= '5') amount += 1n;
  if (match[1] === '-') amount = -amount;
  if (amount > BigInt(MAX_TRANSACTION_MINOR) || amount < -BigInt(MAX_TRANSACTION_MINOR)) return NaN;
  return Number(amount);
}

export function minorToDisplay(minor: number, currency: Currency = 'USD'): string {
  if (!Number.isSafeInteger(minor)) return '';
  const exponent = CURRENCY_EXPONENT[currency];
  const value = BigInt(minor);
  const abs = value < 0n ? -value : value;
  const unit = 10n ** BigInt(exponent);
  return `${value < 0n ? '-' : ''}${abs / unit}${exponent ? '.' + String(abs % unit).padStart(exponent, '0') : ''}`;
}
