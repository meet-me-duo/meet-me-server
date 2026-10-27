# Issue123 independent budget and fence fault injection

The current main sources/resources, wrapper/build configuration and only the unchanged94-test budget/fence set were copied into an isolated temporary project. No Git metadata, .env or secrets were copied. Both provider API-key variables were explicitly empty. Actual PG is Testcontainers; all amounts/quotes/bills/coverage are synthetic TEST_SUBUNIT facts.

Baseline94 passed. Each documented mutation produced requirement assertion failures (no compile/setup errors), was restored exactly, and its same selected tests then passed. Final restored94 passed. Original source and all11 test-file hashes were checked throughout. All Gradle calls ran under the global lock. These are post-implementation fault injections, not chronological RED.

Exact mutation patches and a hash/result manifest are tracked here. Raw logs/XML/original-source copies remain local under .codex/tdd-evidence/issue123-budget-core-fence-fi/. The canonical seven-field FI record is ../../verification/issue123-budget-core-fence-fi.json. Root owns final full guard/review/publication. No real prices, bill cap, accuracy, latency, production activation or provider exactly-once was demonstrated.
