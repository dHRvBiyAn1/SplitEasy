# E02 / A02 / H06 independent review

Base: verified dev `e5b219773e54f890e56358f2a57de28f56d4bdff`. Reviews follow the
project-local Matt Pocock code-review skill; Standards and Spec stay separate.
Reviewers inspect pinned diffs and do not treat supplied green checks as proof.
No reviewer edited application files or reran tests. Root owns execution evidence.

## Standards

- E02 through `b91bc80` and H06 through `15ebc09`, including shared GroupDetail
  merge `c626c0e`: zero documented breaches/material smells.
- A02 backend `e1616ee`: zero documented breaches/material smells.
- A02 frontend and combined `e7d1a33`: two P2 documented breaches: new components
  owned resource HTTP directly, and invalid submits did not move focus as required
  by the selected UI design. `cf12e93` moves concrete commands into existing
  AuthService/ProfileService and adds single-field/linked-summary focus; four
  sequential rendered red→green slices and a pinned remediation review confirm
  zero remaining hard/material findings.
- A repeated fixed-action presentation switch remains an optional, nonblocking
  judgement call. The small fixed cases did not justify a dispatch framework.
- Hash re-entry `da4c663` and router cleanup `ea09931`, integrated through
  `d9534486`, received narrow final reviews: zero remaining hard/material findings.

## Spec

- E02/H06 and A02 backend checkpoints each have zero material findings.
- Combined `e7d1a33` exposed one P2: A→B cross-tab replacement cleared old reads,
  but the shell effect tracked only authenticated visibility, which stayed true.
  `8d396c6` tracks session generation and reinitializes B through existing
  coalesced revalidation. The rendered test first observed no dashboard request,
  then passed; independent shell review at `0de27e7` reports zero remaining.
- Root browser acceptance also found a signed-in mailbox form recreated after
  token scrubbing, then a same-route reopening gap, then Router fragment restore.
  `94a0048` uses one stable outlet; `da4c663` handles explicit native hash re-entry;
  `ea09931` rescrubs after Router completion without resetting the form. Public
  rendered/real-Router red→green and unchanged real-browser scenarios verify fixes.
- Final Spec reviews through `d9534486` report zero remaining findings. Session
  cancellation/coherent identity, unrelated B account preservation, explicit
  mailbox confirmation, E02 uncertain-write identity and H06 revalidation remain.

External report files under `batch3/reviews/` retain the separate pinned reviews,
including `e02-h06-{standards,spec}.md`, `a02-backend-{standards,spec}.md`,
`final-ui-{standards,spec}.md`, remediation and `router-final-{standards,spec}.md`.
The final integration report records executable checks and remaining rollout
limits. These reviews do not authorize remote merges, production mail or deploys.
