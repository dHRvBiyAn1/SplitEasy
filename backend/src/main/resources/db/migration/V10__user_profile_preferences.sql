-- Additive defaults preserve existing registration dates and all financial data.
ALTER TABLE users
    ADD COLUMN phone VARCHAR(50) NOT NULL DEFAULT '',
    ADD COLUMN avatar_color VARCHAR(7) NOT NULL DEFAULT '#12A57E',
    ADD COLUMN preferred_currency VARCHAR(3) NOT NULL DEFAULT 'USD';

ALTER TABLE users
    ADD CONSTRAINT users_avatar_color_check CHECK (avatar_color IN ('#12A57E', '#D96D3F', '#3F7BD9', '#C25B84', '#D9A03F')),
    ADD CONSTRAINT users_preferred_currency_check CHECK (preferred_currency IN ('USD', 'EUR', 'GBP', 'INR'));
