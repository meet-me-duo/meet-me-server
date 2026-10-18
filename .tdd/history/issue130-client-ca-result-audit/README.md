This audit is a fixed 16-case A-only contrast diagnostic. Read `docs/verification/issue130-client-ca-aggregate-result.json` together with `docs/verification/issue130-client-ca-result-audit.md`, and this directory's `preregistered-basis.json` and `invalidations.json`. No individual text, ID, gold/output linkage or raw response is included.

The first aggregate projection is preserved at count-type-correction/aggregate-result.before.json (SHA06029e8b...). Its single-member SAFE_EDIT_ONLY count fields had JSON booleans; only these numerical-count types were corrected to integer1/0, including the edit-only raw-miss numerator. See count-type-correction/correction.json and the exact patch in that directory for full hashes. Audit prose and all scoring/gold/threshold decisions are unchanged: raw exact9/16, material misses6/8, unnecessary B routes5/8, raw quality gateFAIL.

B was not run, so questions/choices/direct edit/UI quality remain unmeasured. Existing prior failures and UNKNOWN reservation remain preserved. No further call or operating activation is authorized by this audit.

Publication copy path correction only; exact original custodian README bytes are preserved as README.source.md. No scoring, values or decision changed.
