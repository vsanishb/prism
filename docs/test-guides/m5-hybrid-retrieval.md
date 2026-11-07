# M5 Test Guide: Hybrid Retrieval

Search a published policy version, see how each stage ranked what it found, and open results at their page of the wording. **All data is synthetic.** Indexing and search call real models, at a fraction of a cent each.

## Start

Stop any services still running from earlier milestones. Then run each command in its own PowerShell window, from `D:\prism`. The reset deletes your local data, and the new migrations need it. pgvector must be installed once first (`scripts/prism.py install-pgvector`, done on this machine).

```bash
python -m uv run --project services/ai python scripts/prism.py db-reset
```

```bash
python -m uv run --project services/ai python scripts/prism.py run-temporal
```

```bash
python -m uv run --project services/ai python scripts/prism.py run-ai
```

```bash
python -m uv run --project services/ai python scripts/prism.py run-core
```

```bash
python -m uv run --project services/ai python scripts/prism.py run-web
```

Open http://localhost:3000. Passwords are in `.data/synthetic-users.md`.

## Flows

**1. Publish a version.** Follow the M4 guide to have `policy.manager` confirm every value of Gold version 1, and `policy.approver` publish it.

**2. Indexing.** The version page shows **Waiting to be indexed**, then **Indexing for search**, then **Searchable**, with the number of passages, usually within a minute.

**3. Search.** Choose **Search this version**. Try:
- `clause 3.2`: clause 3.2 is first, marked **Named in your search**.
- `pre existing diseas`: the definition is found despite the spelling, marked **Defined term**.
- `lens replacement for clouding of the eye`: cataract surgery and its waiting period, though neither uses those words.
- `62 year old admitted for appendicitis surgery`: the schedule's co-payment and in-patient cover.

**4. Stages.** Each result shows its rank by **Meaning** (embeddings), **Words** (full text), **Combined**, and **Reranked**. **Compare stages** lists every provision any stage found, with its rank in each.

**5. Related provisions.** Under cataract surgery, the waiting period that limits it is listed. Choose it: its page opens with the clause outlined.

**6. Filters.** Choose **Waiting periods** and search again: only waiting periods come back.

**7. Not yet published.** Open a version in review: the search page says it is not searchable until published.

**8. Permissions.** `REVIEWER`, `CLAIMS_PROCESSOR`, and other policy readers can search. `ADMIN` cannot. Only a policy manager sees **Retry indexing** on a failed index.

**9. Audit.** As `AUDITOR`, filter by Policy search index built.

## Measuring quality

```bash
python -m uv run --project services/ai python services/ai/evals/run_retrieval.py
```

It indexes four synthetic wordings in a scratch database and asks 50 labelled questions. It prints recall@10 for each stage, results from the wrong version (must be 0), and cost, about three cents.

## Automated runs

```bash
python -m uv run --project services/ai python scripts/prism.py e2e-stack
```

Then, from `web`:

```powershell
$env:PRISM_E2E_PORT="3100"; $env:PRISM_E2E_CREDENTIALS="../.data/e2e/synthetic-users.md"; npx playwright test
```

The stack uses a stand-in model provider, whose embeddings only match shared words. It proves the flow, not search quality.
