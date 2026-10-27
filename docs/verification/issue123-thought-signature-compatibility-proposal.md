# #123 documented text-part thoughtSignature compatibility

2026-10-18 UTC. Read-only investigation; production/test edits0, actual API/model calls0. The actual recorded response was inspected only for part field names, JSON types, signature length, and usage. No opaque signature value was printed or copied into this note.

The current parser accepts exactly one STOP candidate with one part whose keys equal `{text}`. The recorded valid B200 part keys are `{text,thoughtSignature}`; signature is a string of 1560 characters. Thus the known failing boundary is transport-envelope metadata, independently of the still-required domain/question validation. Recorded usage is input530/candidate188/thought366/total1084; missing values must remain unknown elsewhere.

The official GenerateContent `Part` reference defines `thoughtSignature` as optional opaque bytes represented by a base64 string. It is a common Part field outside the `data` union, so it can coexist with `text`. Its purpose is subsequent reuse. The thinking guide explicitly distinguishes GenerateContent metadata attached to any/final part from Interactions dedicated thought blocks. [Part reference](https://ai.google.dev/api/generate-content#Part), [Thinking guide](https://ai.google.dev/gemini-api/docs/thinking#thought-signatures)

ProtoJSON defines bytes as base64, accepting standard or URL-safe alphabets with or without padding; generic null is unset. Empty base64 represents zero bytes and no nonempty signature constraint was found. The Discovery type-format page also describes bytes encoding; it is not a reason to reject the standard alphabet accepted by ProtoJSON. [ProtoJSON](https://protobuf.dev/programming-guides/json/#representation-of-each-type), [Discovery type-format](https://developers.google.com/discovery/v1/type-format)

Minimal proposed behavior after independently frozen valid RED:

- Keep one candidate, STOP, exactly one object part, required string `text`, strict duplicate JSON rejection, and all existing domain payload/question/caps/usage validation.
- Only add `thoughtSignature` to the allowed part key set. Do not allow arbitrary metadata, `thought`, tool/data alternatives, multipart responses, or new root/domain keys as part of this fix.
- Missing/null signature: discard as absent; empty string: valid zero-byte opaque metadata. A nonnull value must be a string and valid strict base64 in the documented alphabets/padding variants. Reject wrong types, whitespace/control-containing strings, malformed padding, impossible lengths, invalid characters. Existing16KiB raw-body cap bounds encoded/decoded memory; no new public limit.
- Decode for representation validation only; discard decoded bytes immediately. Do not parse semantic content, decrypt, rewrite input, echo it into future independent requests, attach it to results/exceptions, persist it in domain/DB/retention, or expose it to logs/public DTO/metrics.
- Existing direct raw-response evaluation evidence remains evidence owned by parent; this fix adds no product response-body storage.

The null/empty acceptance recommendation is format compatibility, not an instruction to consider malformed question payloads successful. If a narrower local response policy rejects explicit null, document that as a local policy, not as a Google requirement. This parser is deliberately a strict accepted response subset, not a full ProtoJSON implementation.

For current independent one-shot checks there is no subsequent conversational request using prior model output. Therefore discarding a signature has no continuation to break; this is an inference from current request flow. If multi-turn/tool continuation is added later, signatures require a separate protected preservation/echo design, and must not be reconstructed or merged.

Recommended independent cases: no-signature unchanged; standard padded/unpadded and URL-safe padded/unpadded; empty/null; malformed/nonstring/unknown keys/multipart/thought part; signature combined with invalid text payload remains rejected; privacy serialization/result/exception contains no signature; real recorded response semantic replay uses the exact recorded bytes and no paid recall. Preserve old guard history and old frozen expectations.
