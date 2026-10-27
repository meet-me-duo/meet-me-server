# Preregistered classification evaluation assets

These assets are ready for a separately authorized evaluation. No model, HTTP or Gradle calls were made to prepare them. They contain gold annotations, not model predictions, measured accuracy or confidence calibration. The original development60 and untuned-holdout60 remain byte-identical to their frozen source commit. The new16 are a separate all-blind supplemental partition of eight independently reviewed single-factor contrasts.

Do not open holdout or supplemental payloads as a prompt/schema/threshold author. File access is procedural, not cryptographically enforced. The oracle author and independent reviewer have accessed the data and cannot claim it is unseen by them. Root and implementation roles receive only aggregates and file-level hashes. No individual corpus gold or text is present in this aggregate directory; the protocol-only records are wholly invented.

Active payloads are the three `docs/fixtures/issue-123-ui-evaluation-*.json` files. Original source corpora remain the two `issue-107-decision-*.json` files. Every new asset is `FINAL_PRE_EXECUTION_ORACLE`; the portable validator rejects DRAFT status and checks pinned final SHA values. `review-summary.json` links reviewed draft hashes to final hashes through status-only changes and status-excluded semantic fingerprints. Four initial draft issue groups were corrected; independent re-review has no open P1/P2. Superseded payloads, private findings and exact diffs are outside active fixtures in ignored evidence. Their fingerprints and tiny status-only diffs are indexed under `.tdd/history/issue123-classification-evaluation-oracle/`.

Run from the server root:

```sh
python3 docs/evaluation/issue123-classification-evaluation/validate.py
python3 docs/evaluation/issue123-classification-evaluation/protocol_sanity.py
```

Both run without credentials, providers, network or dependencies outside Python's standard library. Neither writes by default. `--receipt PATH` explicitly writes a receipt; validator `--root PATH` supports an isolated copied asset tree. The validator checks original hashes, splits, IDs, exact A gold and A provenance, B policies, verdict partitions, fixed contexts, boundaries, pair links and final review fingerprints. Its literal replacement check is structural: semantic true-pair judgment comes from the separate independent review, never a regular expression or string similarity.

`protocol-only-sanity.json` contains nine wholly invented outcome records for arithmetic checks. It demonstrates raw false negatives retained after B recovery, unnecessary questions, safe-but-incomplete edits, refusal, errors, unrun cases and N/A denominators. It is explicitly PROTOCOL_ONLY and provides no model accuracy. `scoring-contract.json` is the predeclared scoring contract; no runner sends these annotation files to a model.

A must-not-question case protects contextual clarity, intentional breadth and valid dependencies. A material ambiguity must be clarified without silently guessing. A safe direct-edit outcome is distinguished from successful question generation and completed submission. In the new supplement, some missing facts do not ground two alternatives; forcing a question there would invent information. The old stage-A-only prohibition on question generation remains in A provenance and is excluded from B conditions to avoid an impossible two-stage oracle.

Each provider input must contain only exact `rawText` and the five frozen context fields. All IDs, group/dimension labels, pair links, gold, reasons, hashes, paths, split/order and annotations stay out of model inputs. The date reference is fixed in November2026 and must not be replaced with current clock or the separate October live-case context. Do not change a confidence threshold based on these heldout outcomes.

`coverage.json` counts overlapping metadata dimensions separately. It is not a population frequency estimate. `exposure.json` records this evaluator's access and checks against four distinct inputs in 15 known historical consumed HTTP records. Exact overlap is zero; this does not prove no paraphrase overlap or unrecorded exposure. Those historical attempts and the separate one-case latency success are not evaluation results for these corpora. Earlier raw failures, provider errors, timeouts and parser failures remain unchanged.

Paid execution, request/cost bounds, runner implementation, model selection or profile activation require a separate plan. Current preparation makes no p50/p95, accuracy, billing or production-readiness claim.
