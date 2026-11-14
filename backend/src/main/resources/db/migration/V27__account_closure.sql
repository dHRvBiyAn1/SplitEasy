-- Closure state only; scrubbing happens in AccountClosureService. Drain old servers first:
-- they would let a closed account's tombstone row be treated as a live one.
ALTER TABLE users ADD COLUMN closed_at TIMESTAMPTZ;
