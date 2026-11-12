# Reddit fixtures

These are field projections of private-cache GraphQL responses captured from Reddit Android
2026.44.0 (2644080) on 2026-11-12. Public post/comment IDs, source Markdown, parent references,
timestamps and counts are unchanged. Authentication headers and advertising tracking payloads
are not included.

- `posts.json`: two organic posts and an advertised `ProfilePost` whose
  `isCommercialCommunication` is false. `postStats.shareAllTotal=46` matched the visible 46 shares.
- `thread.json`: ten of the 200 captured comments, including an entire depth 0–7 chain, a hidden
  score, a negative score, and one real null-node `more` placeholder. This is not the full thread.
  `pageInfo.commentCount=318` differs from the post's `commentCount=409`; neither is the local count.
- `home.json`: one organic CellGroup and one ad CellGroup identified by `groupId + adPayload`.

## READ-mode assembly

`postsInfoByIds` hydrates body, media and author fields independently of the sparse
`postInfoById.commentForest` detail response. READ mode therefore parses every organic
fragment into a private ObjectBox staging table, not directly into the visible record list.
An explicit comment tree confirms its thread; that proof persists across synchronization
runs and app restarts. Confirmation releases matching staged fragments, and later fragments
continue updating the same visible records. Staging updates, publication and RawData deletion
share one transaction. Unconfirmed payloads and comment mappings are limited to seven days
and 5,000 rows; confirmed thread markers do not expire. Ordinary saved/PUSH posts are not
implicitly considered READ confirmations.

`RedditReadBufferTest` tests separate buffer instances/calls in both response orders,
Markdown/media/author recovery, unknown comment mappings and private candidate eviction.
The memory-backed tests do not by themselves exercise ObjectBox process restarts or rollback.

The original early diagnostic envelopes have a null request. Later request-capture verification
is recorded separately; these fixtures do not invent an operation name or query variables.
Set `REDDIT_EVIDENCE_DIR` to the retained local diagnostic folder to replay the full 200-comment
sample and all `.bin` payloads without copying private dumps into the repository.
