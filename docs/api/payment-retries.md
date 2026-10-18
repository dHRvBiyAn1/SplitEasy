# Payment operation IDs

`POST /api/groups/{groupId}/payments` accepts an optional UUID `operationId` alongside
`payerUserId`, `payeeUserId`, and integer `amountCents`. Generate one ID per intended
payment and resend that same ID and payload after an uncertain response.

The key is `(groupId, authenticated requesterId, operationId)`. The requester comes
from the JWT, never from the body. Every request, including a replay, first requires
current membership of the group. An operation ID grants no authorization.

For a valid request with an ID:

- The first committed payment returns HTTP 201 and a payment response.
- An identical retry returns HTTP 201 with the original payment ID, timestamp,
  payer, payee and amount; it inserts no second row and leaves balances unchanged.
  This also applies when retries race before the first insert commits.
- Reusing the key with different payer, payee, or amount returns HTTP 409 without
  changing the original payment. Normal request validation still applies.
- Different IDs permit intentional equal payments. The same ID used by another
  authorized requester or in another group is an independent operation.
- Failed transactions reserve no key. There is no key expiry while the payment
  exists. Deleting a group deletes its payments and operation identities.

Omitting the ID (or supplying null) preserves legacy additive behavior: each POST
creates another payment. Retry protection applies only to supplied IDs.

V9 adds nullable `recorded_by` (requester FK) and `operation_id` UUID columns,
a paired-null check, and a unique constraint on the scope above. Existing rows
remain null and are not deduplicated. Keyed inserts use PostgreSQL `ON CONFLICT
DO NOTHING`, then read and compare the winning payment in READ COMMITTED. A loser
waits for the winning insert without raising a uniqueness error or reading from
an aborted transaction. No balance state is stored or separately updated.

The Angular payment service retains one pending operation in memory. It reuses
its UUID for an identical submitted group/payer/payee/amount through network or
server errors, RxJS resubscription, and closing/reopening the settlement modal.
A successful response, different submitted intent, or account-session reset
clears/replaces the pending identity. Session reset also cancels pending HTTP.
A browser reload/tab change loses this in-memory identity; after an uncertain
response, inspect payment history before recreating a payment in a new tab or
reloaded page. Changing intent after an uncertain response may also represent a
second payment: the first operation might already have committed. No automatic
retry, durable queue, or cross-tab coordination is introduced.
