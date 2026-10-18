# DC06 bounded decimal resources

Independent review found that a short exponent token can create a BigDecimal with a huge scale, making subsequent arithmetic consume excessive memory/CPU. Root adopted the internal parser bound: external confidence/probability precision <=1000 and scale in -1000..1000, checked before value comparison or arithmetic. Text500, body16KiB, tolerance1e-6 and operational confidence policy are unchanged.

The genuine chronological RED uses only cheap scale1001/precision1001 values:34 executed,16 assertion failures because the old adapter returned a successful assessment instead of INVALID_RESPONSE,18 PASS controls,0 errors/skips. CLEAR and explicit INDETERMINATE are both covered, including confidence. PASS controls prove inclusive1000 bounds and exact1e-6 sum tolerance; out-of-tolerance rejection remains unchanged. No OOM-sized number was executed in Gradle or the parent JVM.

Only the new supplemental test file was added; the original5 classifier tests/fixture remain byte-identical. Scale cases use the default mapper. Precision boundary cases inject a mapper with maxNumberLength4096 solely within this new test, so the public injected-mapper seam is covered without relaxing production/default Jackson constraints or editing the shared fixture.

Canonical schema7 evidence: `../../red/issue107-decision-numeric-resource.json`. Local original log/XML/new test/shared fixture/before-contract bytes are in `.codex/tdd-evidence/issue107-decision-numeric-red*`. The earlier format-only contract supersession and historical GREEN/FI hashes remain preserved separately. Actual external and paid calls0.
