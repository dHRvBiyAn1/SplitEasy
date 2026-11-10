# Keep financial identities immutable when accounts claim them

G01 separates financial participants from sign-in accounts. Existing participants retain their account's UUID, while a verified account can own several immutable participant identities; derived balances and obligations combine those identities through the account's primary participant. Rewriting guest financial references would collide with existing shares, erase original split identities and break historical snapshots and retry receipts, so claims add ownership links and leave those facts unchanged.

Guests remain scoped to their creation group. That keeps a claim's financial effects under the existing group transaction lock; ownership alone never grants group access. Claims require explicit verified-email acceptance, while a group join link grants only membership and never claims another participant.
