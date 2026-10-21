# Native fixture UUID correction

The first native browser run received a room-list HTTP 200 but could not render the drilldown controls because the synthetic response IDs had version 0 and non-RFC variant bits. This is a synthetic fixture/harness setup failure, not product RED. Production generates UUID.randomUUID values, and its UUID validation is preserved.

Original fixture bytes and SHA-256 are preserved in OperatorNativeBrowserServer.before.kt and expectation-change.json. The correction replaces 25 occurrences of 10 fixed IDs with version 4 / RFC variant 8 IDs, including the synthetic account, SQL FK references, owner token and matching JVM allowlist. Each leading group is retained, and reversing only these replacements reproduces the original text exactly. Independent HTTP authorization tests and all product validation remain unchanged. The author did not execute the fixture or browser.
