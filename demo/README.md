# Demonstration claims

Three claims to upload by hand during a demonstration. Each folder holds the documents and a README with the
exact values to type and what Prism should show.

| Folder | Case | Expected band |
|---|---|---|
| [1-blatant-fraud](1-blatant-fraud/README.md) | Plainly inconsistent, with a bill copied from another claim | HIGH |
| [2-clean-claim](2-clean-claim/README.md) | An ordinary, consistent claim | LOW |
| [3-disguised-fraud](3-disguised-fraud/README.md) | Consistent documents, but a light copy of an earlier bill | HIGH |

## Before you start

Run this once, with the stack running (see the project README):

```bash
python scripts/prism.py demo-prepare
```

It publishes every policy and loads 25 claims in every state: waiting, taken, decided, revised,
under investigation, waiting for documents. Several are the earlier claims these three are compared with,
so **the demonstrations only work after demo-prepare**. It prints a table of what each claim raised.

## Upload each case once

The duplicate rules compare every submitted claim. If you upload a case twice, the second copy is compared
with the first and is flagged as a duplicate, even the clean one. After a rehearsal, either withdraw the
rehearsal claims, or start fresh:

```bash
python scripts/prism.py db-reset
python scripts/prism.py demo-prepare
```

Every person, hospital, policy, and amount here is fictional. The documents are watermarked.
