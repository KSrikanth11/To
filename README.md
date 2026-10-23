# Recipient Resolver API

Spring Boot API that interprets recipient requests and searches either the checked-in JSON index or live MySQL rows. Both sources use the same normalization, alias expansion, matching, scoring, and response flow. The LLM extracts intent only; IDs always come from the selected data source.

## Select the recipient source

Configure `RESOLVER_SOURCE` in the environment or `resolver.source` in `application.yml`:

- `index` (default): load `suraksha_recipient_search_index.json` into memory at startup.
- `mysql`: query live MySQL rows where `list_id=173` (configurable with `RESOLVER_LIST_ID`) for each request.

MySQL mode reads `list_members.id` and `list_members.list_username`, plus `list_sub_groups.id`, `list_sub_groups.sub_group_name`, and `list_sub_groups.sub_group_desc`. The `id` columns are assumed to be the member and subgroup primary keys. It queries both tables with `WHERE list_id = ?` using the configured list ID.

The existing JSON file is not changed. In MySQL mode only its verified alias dictionary is loaded; member and subgroup records are not loaded into memory or turned into a local token index. Each resolve request sends parameterized SQL to MySQL first. SQL requires every extracted query concept to have a matching candidate, while allowing the configured aliases within each concept. MySQL streams only those candidate rows to Java, where the existing alias normalization, matching, scoring, threshold, sorting, and pagination logic runs. If a table has no SQL candidates, that table is streamed once as a fuzzy-match fallback. Candidate records are never cached. The configured list ID is used by default; requests may optionally provide `listId` to select another list. In index mode, the JSON supplies both records and aliases and its token index is held in memory; `listId` does not change that source.

To configure MySQL, set `RESOLVER_SOURCE=mysql` and provide `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, and `DB_PASSWORD`. The app creates a database connection only in MySQL mode. With `RESOLVER_SOURCE=index`, no database connection is created or required.

## Run

Requires Java 17+ and Maven. Copy `.env.example` to `.env`, fill in the keys and source settings you need, then load it into the shell. Spring Boot does not load `.env` automatically.

```sh
set -a
source .env
set +a
mvn spring-boot:run
```

OpenAI intent extraction is enabled by default and uses `OPENAI_API_KEY`. Set `LLM_PROVIDER=none` for deterministic local intent extraction. Audio requests require `SONIOX_API_KEY`. OpenAI receives text queries; Soniox receives uploaded audio. Keep provider keys in the server environment.

## Text request

```sh
curl -X POST http://localhost:8080/api/v1/recipients/resolve \
  -H 'Content-Type: application/json' \
  -d '{"query":"Send to all SIs in Nalgonda","limit":50,"offset":0}'
```

`query` is required and limited to 500 characters. `limit` defaults to 50 (maximum 200); `offset` defaults to 0 (maximum 10000). Results contain separate `member_matches` and `subgroup_matches`, with source IDs, match evidence, scores, clarification status, and pagination fields.

## Audio request

```sh
curl -X POST http://localhost:8080/api/v1/recipients/resolve-audio \
  -F 'audio=@./recording.mp3;type=audio/mpeg' \
  -F 'language_hints=en' \
  -F 'listId=120' \
  -F 'limit=50'
```

The app validates the upload and asks Soniox to detect the spoken language automatically, then requests one-way translation into English (`en`). `language_hints` remains accepted for request compatibility but is not sent to Soniox on this endpoint: it describes the source speech and can bias recognition, while translation configuration controls the output language. The app uses Soniox's English translation tokens and does not pass non-English source tokens to the recipient resolver. The response includes the English transcript and recipient resolution. Soniox's `en` target is general English rather than a separate Indian-English locale. Transcripts must contain 1–500 characters; upload size defaults to 25 MB.

## Voice command architecture

`POST /api/voice/command` accepts either an already available transcript as JSON or an audio upload as multipart form data. Both request forms can include the current compose form and a `context` object containing recent user queries from this compose session. Send the `context` returned by each response with the next request. The API keeps the most recent eight non-empty queries (up to 500 characters each). Clear it when the compose component closes or the message is sent.

Text request:

```sh
curl -X POST http://localhost:8080/api/voice/command \
  -H 'Content-Type: application/json' \
  -d '{"transcript":"write a message Hello team","form":{"subject":"","message":"","labels":[],"recipientCount":0},"context":{"userQueries":[]}}'
```

Audio request (the `form` part is JSON):

```sh
curl -X POST http://localhost:8080/api/voice/command \
  -F 'audio=@./command.mp3;type=audio/mpeg' \
  -F 'form={"subject":"","message":"","labels":[],"recipientCount":0};type=application/json' \
  -F 'context={"userQueries":["Select all SIs in Nalgonda"]};type=application/json' \
  -F 'language_hints=te'
```

The request flows through these layers:

1. `VoiceCommandController` accepts JSON transcript input or multipart audio. Audio is transcribed and translated to English by `SonioxTranscriptionService`; either path then uses the same transcript routing method.
2. `IntentExtractor` receives the current transcript, compose form, and recent user queries. It uses prior queries only to interpret the current turn and returns an ordered list of current intents, each with its own confidence; prior actions are not replayed. This supports follow-ups such as changing the designation in an earlier recipient query while retaining its location, as well as references to earlier subject, message, or label instructions.
3. `CommandRouter` applies supported updates in order and returns the resulting `form`. Recipient searches run through `AliasService` and `RecipientResolverService`; their full `ResolveRecipientResponse` is returned as `recipientResolution` alongside the UI actions.
4. Angular applies the actions and uses the returned form as the new compose state. Recipient matches are candidate results; only a fully resolved explicit “all” request updates `recipientCount` directly. Otherwise Angular should update that count when the user selects recipients.
5. Spoken label names are returned in `UPDATE_LABELS` actions. Since this API's `form.labels` contains numeric IDs and the backend has no label catalog, Angular must map those names to IDs before updating that field. `SEND` returns `REQUEST_SEND_CONFIRMATION`; this endpoint never sends a message.

For example, a transcript such as “send a message to all SIs in Nalgonda with subject Monthly review and label urgent” can return recipient results, a subject update, a label action, and a send confirmation request in one response. The message body remains unchanged because none was dictated.

Implementation map: `voice/command/VoiceCommandController.java` (HTTP and audio/text convergence), `AudioService/SonioxTranscriptionService.java` (audio-to-English transcript), `voice/command/model/ComposeFormState.java`, `VoiceCommandContext.java`, and `VoiceCommandRequest.java` (request state and bounded query history), `voice/command/CommandRouter.java` (dispatch and history update), `voice/command/IntentExtractor.java` (context-aware intent extraction), and `voice/actions/**` (action output).

## Matching and scores

Queries and record fields are lowercased, punctuation is normalized, and tokens are compared without word-order dependence. Built-in roles include SI, CI, DSP, SDPO, and SHO. Verified aliases come from the JSON dictionary; extra aliases can be set with `RESOLVER_CUSTOM_ALIASES=Canonical=alias1|alias2;Other=alias`.

Candidates are scored with deterministic concept coverage. Multiple identifier-shaped unit values are treated as alternatives, so results can include rows matching any requested value. Multiple recognized designations are grouped as alternatives, while distinct location filters remain required. The language model preserves terms and does not create aliases; alias expansion comes from configured built-in, verified dictionary, or custom mappings. Exact token coverage gets a higher score than partial coverage; limited edit-distance fuzzy matching is also used. Scores are not probabilities or LLM confidence. The default minimum score is `0.80`, and the implementation returns scores strictly greater than the threshold. Configure it with `RESOLVER_MIN_SCORE`.

## Index updates and logs

In index mode, the JSON loads at startup. To hot reload an external index, set `RECIPIENT_INDEX_PATH=/path/to/suraksha_recipient_search_index.json`, replace the file, and call:

```sh
curl -X POST http://localhost:8080/api/v1/recipients/index/reload
```

The index is validated before an atomic swap. In MySQL mode, no data reload is required because every resolve request filters against live MySQL data. The same endpoint reports current row counts from MySQL; the alias dictionary is loaded at application startup. Logs report how many SQL candidate rows were scored and whether the per-table full-scan fallback ran.

Each request receives an `X-Request-ID`. Logs show request start/end, source load counts, AI extraction or fallback, candidate counts, result counts/top scores, and Soniox transcription states. They do not log query bodies, transcript text, audio data, recipient IDs, or credentials.

## Health and build

```sh
curl http://localhost:8080/actuator/health
mvn test
docker build -t recipient-resolver .
docker run --env-file .env -p 8080:8080 recipient-resolver
```

No bearer header is required. Restrict network access at deployment if the API is exposed outside a trusted environment.
