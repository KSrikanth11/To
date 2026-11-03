# Recipient Resolver API

Spring Boot API that interprets recipient requests and resolves them against the members (`list_members`) and subgroups (`list_sub_groups`) of a MySQL list. The LLM only extracts search terms; a deterministic catalog decides what each term means, and IDs always come from the database. Results are split into CONFIRMED matches, which are safe to select automatically, and SUGGESTED matches, which the user must confirm.

## Select the recipient source

Configure `RESOLVER_SOURCE` (default `mysql`):

- `mysql`: queries `list_members` (with `recipient_member_profile` / `recipient_member_attribute`) and `list_sub_groups` for the requested `listId` (default `RESOLVER_LIST_ID=173`) on every search; member rows are never cached. The query's ranks, places and words become `LIKE` patterns that select every row that could match (never fewer), and only those rows are parsed and matched with the exact rules. Only the list's catalog is cached, for `RESOLVER_CACHE_TTL_SECONDS` (default 120; `0` disables caching). A `%word%` pattern cannot use an index, so each search reads the list's rows once: about 25 ms for 3,000 members and 1–2.5 s for 1,000,000 on a local MySQL 8.0. An index on `list_members (list_id)` keeps other lists out of that read.
- `index`: an offline alternative that parses the records in `suraksha_recipient_search_index.json` the same way. `listId` is ignored.

Both sources use the same parser and matcher. Where the **catalog** (designations, locations, unit spellings and query words) comes from depends on the source:

- `mysql`: only from the `recipient_*` catalog tables for the request's `listId` (see `docs/sql/`). Each list is read with its own catalog; only the catalog is cached. The JSON file and the built-in defaults are not used, and neither are `RESOLVER_CUSTOM_*`: edit the tables instead. A list with no catalog rows returns `503 {"error":"Recipient catalog is not configured for this list"}` rather than guessing.
- `index`: the built-in defaults, plus the JSON alias dictionary and `RESOLVER_CUSTOM_*`. These match the seed in `recipient_catalog.sql`, so both modes give identical answers for list 173.

To configure MySQL, set `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME` and `DB_PASSWORD` (or `DB_URL`). A database connection is created only in MySQL mode.

## Run

Requires Java 17+ and Maven. Copy `.env.example` to `.env` and fill in the keys and database settings. The app reads `.env` automatically at startup, and real environment variables take precedence. `.env` is gitignored: never put credentials in `application.yml`.

```sh
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
3. `CommandRouter` applies supported updates in order and returns the resulting `form`. Recipient searches run through `SearchRecipientsAction` and `RecipientResolverService`; their full `ResolveRecipientResponse` is returned as `recipientResolution` alongside the UI actions.
4. Angular applies the actions and uses the returned form as the new compose state. Recipient searches can return three actions:
   - `SET_RECIPIENTS`: CONFIRMED matches to select. Members are preferred; when members are selected, matching groups are only offered (never both), so nobody receives the message twice.
   - `SUGGEST_RECIPIENTS`: SUGGESTED matches and alternative groups. Show them unchecked; never select them automatically.
   - `CLARIFY_RECIPIENTS`: a question to show the user, e.g. a misheard place ("Did you mean Nalgonda?"), a word that matches nobody, or more than `RESOLVER_MAX_RESULTS` matches. Nothing is auto-selected in the last case.

   Only an explicit "all" request that needs no clarification updates `recipientCount` directly.
5. Spoken label names are returned in `UPDATE_LABELS` actions. Since this API's `form.labels` contains numeric IDs and the backend has no label catalog, Angular must map those names to IDs before updating that field. `SEND` returns `REQUEST_SEND_CONFIRMATION`; this endpoint never sends a message.

For example, a transcript such as “send a message to all SIs in Nalgonda with subject Monthly review and label urgent” can return recipient results, a subject update, a label action, and a send confirmation request in one response. The message body remains unchanged because none was dictated.

Implementation map: `voice/command/VoiceCommandController.java` (HTTP and audio/text convergence), `AudioService/SonioxTranscriptionService.java` (audio-to-English transcript), `voice/command/model/ComposeFormState.java`, `VoiceCommandContext.java`, and `VoiceCommandRequest.java` (request state and bounded query history), `voice/command/CommandRouter.java` (dispatch and history update), `voice/command/IntentExtractor.java` (context-aware intent extraction), and `voice/actions/**` (action output).

## Matching and scores

Each directory row is parsed into fields: a member username such as `si-ahtu-nlg@suraksha` becomes designation `SI`, location `Nalgonda`, and keywords `ahtu`. Subgroup names and descriptions are parsed the same way (for example, `ALLCIs-NLG` gives designation `CI`). Query terms go through the same catalog:

- **Designations**: SI (including WSI), CI, Inspector, SHO, DSP, SDPO, ACP, ADCP, DCP, Addl SP, SP, CP, RI and DI, plus plurals, spelled-out forms and numbered codes (`si1046`, `ci1`, `inspr3`). Several designations in one query are alternatives. In MySQL mode, add them to `recipient_designation` / `recipient_designation_alias`; in index mode, use `RESOLVER_CUSTOM_DESIGNATIONS=CODE=alias1|alias2;...`.
- **Locations**: aliases from `recipient_location_alias` (the JSON dictionary in index mode). Several locations are alternatives. The location hierarchy is temporarily disabled, so each location matches only itself ("Nalgonda" does not include Miryalaguda). It can be re-enabled with `RESOLVER_LOCATION_PARENTS=Child=Parent;...`. Codes listed under `unverified_candidates` (MDK, KNR, NKL, WGL...) only ever produce SUGGESTED matches. To confirm a code, set `verification = 'VERIFIED'` on its `recipient_location_alias` row (or, in index mode, use `RESOLVER_CUSTOM_ALIASES=Medak=MDK`).
- **Units**: spelling variants are folded together (`tfc`/`trf` → traffic, `L&O` → lo). Extend through `recipient_unit_alias` (or, in index mode, `RESOLVER_CUSTOM_UNITS=canonical=form1|form2`).
- **Keywords**: any other word (a name, a unit, a code) is required and must appear in the row. Command words, generic words ("officers", "district", "police", "station") and pronouns are ignored.
- **Exclusions**: "except traffic", "not Suryapet", or the LLM's `exclude_terms` remove matching rows.

A row matches only if it satisfies every filter on the right field. Partial coverage never matches. The match is then classified:

| status | match_type | score | when |
|---|---|---|---|
| CONFIRMED | EXACT_MATCH | 1.0 | The query is exactly the username or group name |
| CONFIRMED | TOKEN_MATCH | 0.95 | Every filter holds through exact, verified evidence |
| SUGGESTED | POSSIBLE_MATCH | 0.50–0.75 | Only through an unverified code, a misheard word, a partial or similar spelling, or a group that also covers other designations |

When a word matches nobody, the resolver first tries to read it as a misheard designation or location ("Nalgoda" → Nalgonda; suggestions only), then offers near spellings. In both cases `needs_clarification` is true and `clarification` holds the question to show. Responses also include `member_total`, `subgroup_total` and an `interpretation` object (designations, locations, keywords, excluded, unresolved terms) so the UI can show how the request was understood.

Catalog SQL in `docs/sql/` (MySQL 8.0.19+). Every table has `list_id`, so each tenant list has its own catalog, and composite foreign keys stop one list's alias from pointing at another list's location. Run these in order for a list:

1. `recipient_catalog.sql`: creates the catalog tables (`recipient_designation` with `parent_code`, `recipient_location`, `recipient_unit`, their `*_alias` tables, `recipient_query_word`), the decoded-member tables (`recipient_member_profile`, `recipient_member_attribute`) and the `recipient_catalog_phrase` view. It seeds the base vocabulary for `@list_id` (default 173): core designations, unit spellings and query words. It adds no locations; those come from step 2. Generated by `scripts/catalog/generate_base.py` from `scripts/catalog/schema.sql`.
2. `recipient_decoded_import.sql`: loads the decoded-usernames workbook (`TG_Police_Usernames_Decoded.xlsx`) into the same list:
   - every rank and post (PC, HC, ASI, WSI, WPC… with `parent_code`, so a WSI also counts as an SI);
   - every location: the districts, commissionerates, zones and stations, with their codes from the abbreviation key. The old JSON alias dictionary is not loaded, because the decoded data contradicts it (MLG = Mulugu District, not Miryalaguda; KMR = Kamareddy, not Karimnagar);
   - one profile per username, with its designation and location attributes.

   Values the decoder marked `?`, and Likely/Needs-review rows, are stored as unverified, so they only give SUGGESTED matches. "Not decoded" values are left out, and Test/system accounts are never matched by rank or place. Regenerate it after the workbook changes:

   ```sh
   python3 -m venv .venv && .venv/bin/pip install openpyxl
   .venv/bin/python scripts/decoded_import/generate.py src/main/resources/TG_Police_Usernames_Decoded.xlsx \
       docs/sql/recipient_decoded_import.sql docs/decoded_review
   ```

   `docs/decoded_review/` lists what needs a person to check:
   - `uncertain_rows.csv`: every `?`, Likely or Needs-review row.
   - `ambiguous_location_aliases.csv`: codes such as MLG, NKL, MDK that name several places. The app asks which one was meant.
   - `designations_not_mapped.csv`: offices and cells. These are searched by keyword, not by rank.
   - `values_kept_as_keywords.csv`: non-place values such as Stores or Training.
   - `json_dictionary_conflicts.csv`: codes the decoded data reads differently from the old JSON dictionary.
3. Optional: `recipient_catalog_discovery.sql` gives read-only coverage reports over `list_members` / `list_sub_groups`, and `recipient_catalog_copy_list.sql` copies one list's catalog (not its profiles) to a new list.

In MySQL mode the application reads these tables (`recipient_catalog_phrase`, `recipient_query_word`, `recipient_member_profile`, `recipient_member_attribute`, filtered by `list_id`). A profile is used only while `list_members.list_username` still equals the profile's `username`. After a rename, the member is read from the new username until it is decoded again. Member and profile edits apply to the next search; after editing the catalog tables, call `POST /api/v1/recipients/index/reload` or wait for the cache TTL.

`docs/evaluation.csv` drives `RecipientEvaluationTest`: add reviewed member/subgroup IDs (separated by `;`) and the build fails if CONFIRMED precision drops below 98% or recall below 95%.

## Index updates and logs

`POST /api/v1/recipients/index/reload` clears the MySQL catalog cache for every list and reloads the default list's catalog immediately (in index mode, it re-reads the JSON file; set `RECIPIENT_INDEX_PATH` to use an external file). The JSON index is validated before an atomic swap.

Each request receives an `X-Request-ID`. Logs show request start/end, list load counts and timing, AI extraction or fallback, filter and result counts, and Soniox transcription states. They do not log query bodies, transcript text, audio data, recipient IDs, or credentials.

## Health and build

```sh
curl http://localhost:8080/actuator/health
mvn test
docker build -t recipient-resolver .
docker run --env-file .env -p 8080:8080 recipient-resolver
```

No bearer header is required. Restrict network access at deployment if the API is exposed outside a trusted environment.



//Never Remove Below explanation//

How POST /api/voice/command works
There's one gap to know about first. A voice command always searches the default list (list_id 173), because the voice request has no listId field. Details are in section 6.

Example used all the way through. The user speaks this, partly in Telugu:

"Nalgonda lo anni SIs ki message pampu, traffic vaallu vaddu. Subject duty change. Message report at 6 am. Send it."

The front end sends the audio together with the current form:


POST /api/voice/command   (multipart/form-data)
  audio    = recording.webm
  form     = {"subject":"","message":"","labels":[],"recipientCount":0}
  context  = {"userQueries":[]}
1. The whole flow

VoiceCommandController
  │  ① audio → SonioxTranscriptionService ──► Soniox API (speech → English text)        [external call 1]
  ▼
CommandRouter.route(transcript, form, context)
  │  ② IntentExtractor.extractCommands ─────► OpenAI /v1/responses (transcript → JSON plan) [external call 2]
  │  ③ for each command in the plan:
  │       SEARCH_RECIPIENTS → SearchRecipientsAction → RecipientResolverService → MySQL
  │       SET_SUBJECT       → SetSubjectAction
  │       SET_MESSAGE       → SetMessageAction
  │       APPEND_MESSAGE / ADD_LABEL / REMOVE_LABEL / CLEAR_FIELD / SEND → handled in the router
  ▼
  ④ VoiceCommandResponse { transcript, form, recipientResolution, actions[], context }
There are only two external AI calls: Soniox for speech and OpenAI for the plan. Recipient search uses the AI's search_terms directly, so the search step makes no second OpenAI call.

2. Step ①: audio to text (VoiceCommandController.java, SonioxTranscriptionService.java)
There are two ways in:

JSON (application/json): the client already has the text and sends {"transcript": "...", "form": {...}, "context": {...}}. Transcripts longer than 500 characters are rejected.
Multipart with audio, as in this example. The controller rejects an empty file, then hands the audio to Soniox:
POST /files uploads the audio.
POST /transcriptions starts the job, with the model (stt-async-v5), automatic language detection and translation: {type: one_way, target_language: en}, so Telugu is translated to English.
The service polls GET /transcriptions/{id} every 750 ms until the job finishes, up to a configured timeout. Then GET /transcriptions/{id}/transcript returns the English text.
Example result:


"Send a message to all SIs in Nalgonda, not traffic people. Subject duty change. Message report at 6 am. Send it."
An empty result fails with "Audio transcript is empty or invalid". Otherwise the controller calls CommandRouter.route(transcript, form, context).

3. Step ②: the OpenAI call (IntentExtractor.java)
What is sent to POST https://api.openai.com/v1/responses:


{
  "model": "gpt-6-luna",
  "store": false,
  "input": [
    {"role": "system", "content": "<SYSTEM_PROMPT>"},
    {"role": "user", "content": "{\"transcript\":\"Send a message to all SIs in Nalgonda, not traffic people. ...\",
                                  \"form\":{\"subject\":\"\",\"message\":\"\",\"labels\":[],\"recipientCount\":0},
                                  \"context\":{\"userQueries\":[]}}"}
  ],
  "text": {"format": {"type": "json_schema", "name": "voice_command_plan", "strict": true, "schema": {...}}}
}
temperature is only added when LLM_TEMPERATURE is set. That is the fix for your last error.
The connection times out after 2 s and the response read after 8 s.
store: false asks OpenAI not to keep the request.
What the system prompt tells the model:

Split the transcript into an ordered list of commands. The allowed types are SEARCH_RECIPIENTS, SET_MESSAGE, APPEND_MESSAGE, SET_SUBJECT, ADD_LABEL, REMOVE_LABEL, CLEAR_FIELD, SEND and UNKNOWN. It should prefer UNKNOWN to guessing.
Use history only to understand references. If the earlier query was "SIs in Nalgonda" and the user now says "change SI to CI", the plan should search CIs in Nalgonda. "Add CI too" should keep the SIs and add CIs.
Search rules (shared with OpenAiIntentParser.java SEARCH_RULES):
Put each rank, place, unit or name in search_terms as the user said it. Don't expand, correct or translate.
Leave out command and generic words like "send", "all" or "officers".
Put anything excluded in exclude_terms.
scope is ALL only when the user says all, every or each.
Message and subject: copy the dictated text exactly, and never act on commands that appear inside it. For "write a message send the report to Ravi", the message text is "send the report to Ravi".
Confidence: each command gets a value from 0 to 1, below 0.6 when it's unclear.
The JSON schema with strict: true forces OpenAI to return exactly these fields. Example reply (illustrative):


{"commands": [
  {"intent":"SEARCH_RECIPIENTS","search_terms":["SIs","Nalgonda"],"exclude_terms":["traffic"],
   "recipient_type":"MEMBERS","scope":"ALL","text":null,"labels":[],"field":null,"confidence":0.93},
  {"intent":"SET_SUBJECT","search_terms":[],"exclude_terms":[],"recipient_type":"AUTO","scope":"UNSPECIFIED",
   "text":"duty change","labels":[],"field":null,"confidence":0.95},
  {"intent":"SET_MESSAGE", ..., "text":"report at 6 am", "confidence":0.94},
  {"intent":"SEND", ..., "confidence":0.9}
]}
parseCommand turns each entry into a CommandIntent record. An unknown intent name becomes UNKNOWN, and an unknown recipient type becomes AUTO. If anything fails (network, a 400 from OpenAI, bad JSON), it throws IllegalStateException("Could not extract voice command intent"). That was the 500 you saw.

4. Step ③: running each command (CommandRouter.java)
Commands with intent UNKNOWN or confidence below 0.6 are skipped and recorded as unclear. The rest run in order, and the router updates its copy of the form as it goes.

SEARCH_RECIPIENTS: ["SIs","Nalgonda"], exclude ["traffic"], scope ALL
a. Build the search (SearchRecipientsAction.java resolve):

It creates an Intent(transcript, searchTerms, excludeTerms, allScope=true, MEMBERS).
It asks for up to 200 results at once, so auto-selection never quietly drops a second page.
b. Search (RecipientResolverService.java) with the MySQL directory:

Step	Class	What happens in the example
Load the catalog	MysqlRecipientDirectory.snapshot	Catalog for list 173, cached for 120 s
Interpret the words	QueryInterpreter	"sis" → designation SI; "nalgonda" → location Nalgonda District; "traffic" → excluded unit traffic
Check unknown words	RecipientMatcher.prepare	Nothing is unknown here. "Nalgoda" would become a suggestion: "Did you mean Nalgonda District?"
Build the SQL	CandidateSql	LOWER(list_username) LIKE '%si%' … AND (LIKE '%nlg%' OR LIKE '%nalgonda%'), plus checks on profile attributes
Query MySQL	MysqlRecipientDirectory.memberCandidates	Fetches only the matching rows of list_members, joined to recipient_member_profile / _attribute; nothing is cached
Read each member	RecipientParser.member	si-ahtu-nlg@suraksha → {SI ✓, Nalgonda ✓}, from the profile if it's current, otherwise from the username
Classify	RecipientMatcher.match	si-tfc-nlg → removed (traffic); si-ahtu-nlg → CONFIRMED; a member with a "?" in the workbook → SUGGESTED
Finish	resolver	Sorts results (CONFIRMED first), pages them, and writes a clarification if one is needed
c. Choose what to select (SearchRecipientsAction.select):

More than 200 matches: nothing is selected, and the user is asked to narrow the search ("N recipients match. Add a location…").
Selected: CONFIRMED members, or CONFIRMED groups if the user asked for groups. Members are preferred so nobody receives the message twice.
Suggested: every SUGGESTED match, plus confirmed groups when members were selected.
Clarification: any question from the resolver.
This becomes the UI actions SET_RECIPIENTS, SUGGEST_RECIPIENTS and, if needed, CLARIFY_RECIPIENTS. Because the scope is ALL and there was no question, the router sets form.recipientCount to the number selected.

SET_SUBJECT and SET_MESSAGE
SetSubjectAction.java and SetMessageAction.java produce SET_FIELD SUBJECT "duty change" and SET_FIELD MESSAGE "report at 6 am", and the router updates the form.

Other commands, handled in the router
Intent	Result
APPEND_MESSAGE	Adds the text to the current message, then SET_FIELD MESSAGE <combined text>
ADD_LABEL / REMOVE_LABEL	One UPDATE_LABELS ADD/REMOVE <label> per label
CLEAR_FIELD	Empties that field in the form, then CLEAR_FIELD <field>
SEND	REQUEST_SEND_CONFIRMATION. The server never sends; the UI must ask the user to confirm
If no action was produced, the response is NOT_UNDERSTOOD "Please repeat". If some commands were skipped, it adds NOT_UNDERSTOOD "Some part of the command was unclear".

5. Step ④: the response

{
  "transcript": "Send a message to all SIs in Nalgonda, not traffic people. Subject duty change. ...",
  "form": {"subject":"duty change","message":"report at 6 am","labels":[],"recipientCount":4},
  "recipientResolution": { "member_matches":[...], "interpretation":{"designations":["Sub-Inspector"],
                           "locations":["Nalgonda District"],"excluded":["traffic"]}, ... },
  "actions": [
    {"type":"SET_RECIPIENTS","candidates":[{"id":7250911,"list_username":"si-ahtu-nlg@suraksha","status":"CONFIRMED",...}, ...]},
    {"type":"SUGGEST_RECIPIENTS","candidates":[{"list_username":"si-x-nlg@suraksha","status":"SUGGESTED",
                                               "match_reason":"Verify before sending: location is not verified..."}]},
    {"type":"SET_FIELD","field":"SUBJECT","value":"duty change","mode":"REPLACE"},
    {"type":"SET_FIELD","field":"MESSAGE","value":"report at 6 am","mode":"REPLACE"},
    {"type":"REQUEST_SEND_CONFIRMATION","message":"Confirm before sending"}
  ],
  "context": {"userQueries":["Send a message to all SIs in Nalgonda, ..."]}
}
The front end applies the actions in order. On the next voice command it sends back the returned form and context. The context keeps the last 8 queries, each cut to 500 characters. That's how a follow-up like "change SI to CI" knows the place was Nalgonda.

6. Things to know
The voice flow ignores list_id. SearchRecipientsAction.resolve passes null, so voice searches always use RESOLVER_LIST_ID (173). For other tenants, VoiceCommandRequest and the multipart request need a listId that gets passed through. I can add that if you want.
Failures:
Soniox failures and timeouts have their own error responses.
An OpenAI failure gives a 500 "Request could not be completed". The log now shows the reason.
A missing catalog gives a 503.
Safety rules: only CONFIRMED matches are auto-selected, more than 200 matches selects nobody, and SEND only asks for confirmation.