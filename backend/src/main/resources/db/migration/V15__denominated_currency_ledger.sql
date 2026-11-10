-- Owner-confirmed legacy denomination: USD. Preserve every historical integer.
-- A03 V14 must be deployed before this migration (or in the same release).
-- Drain all old servers/writers before non-USD data: their read queries are unsafe.
ALTER TABLE expenses ADD COLUMN currency VARCHAR(3) NOT NULL DEFAULT 'USD';
ALTER TABLE payments ADD COLUMN currency VARCHAR(3) NOT NULL DEFAULT 'USD';
ALTER TABLE expenses ADD CONSTRAINT expenses_currency_check CHECK (currency IN ('USD','EUR','GBP','INR','JPY','KWD'));
ALTER TABLE payments ADD CONSTRAINT payments_currency_check CHECK (currency IN ('USD','EUR','GBP','INR','JPY','KWD'));
ALTER TABLE users DROP CONSTRAINT users_preferred_currency_check;
ALTER TABLE users ADD CONSTRAINT users_preferred_currency_check CHECK (preferred_currency IN ('USD','EUR','GBP','INR','JPY','KWD'));
