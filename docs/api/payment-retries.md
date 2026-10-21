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

The Angular payment service persists one account-owned pending operation in this tab's
sessionStorage before dispatch. An identical submitted group/payer/payee/amount reuses
its UUID through network/server errors, RxJS resubscription, modal reopen, and reload.
Reload restores the partial amount and counterparty without sending automatically.
A successful response removes only its matching identity; account-session reset clears
this tab's pending identities and cancels pending HTTP.

Changed uncertain intent is blocked until the user explicitly acknowledges that the
previous payment may already have been recorded and chooses to start a separate
payment. Storage failure blocks dispatch with actionable feedback. Other tabs have
independent sessionStorage; inspect history before recreating an uncertain payment
there. No automatic retry or offline dispatch queue is introduced. The shared browser
contract and rollout details are in [retry-safe writes](retry-safe-writes.md).
