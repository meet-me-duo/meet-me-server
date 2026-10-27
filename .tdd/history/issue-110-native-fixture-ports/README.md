# Native fixture port isolation

The original AuthEndToEndPostgresTest.kt bytes are preserved in AuthEndToEndPostgresTest.before.kt; expectation-change.json records both SHA-256 values. All existing test methods and assertions are byte-for-byte unchanged.

Only runBrowserServer's API/web ports and the synthetic provider's accepted redirect base become configurable. Defaults remain localhost:8080/5173 for the existing #109 main. #110 explicitly uses localhost:18910/18911 because another task owns port 8080. This changes test fixtures only and does not create production configuration or account privileges. No test was run by the author for this edit.

The #110 stop-file property is validated before creating any resources. The daemon watcher exits only its own JVM; the shutdown hook and startup failure path clean up only the reused synthetic fixture. The seed's 22-character invite code and both fixed-ID UPDATE predicates were independently reviewed against V2 and the known isolated row IDs.
