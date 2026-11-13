# 08: LLM Gateway

> Every company using AI ends up building one of these. It touches streaming, rate limiting, fairness, failover, caching, and billing, all in one project. It's also a real micro-SaaS idea.

---

## The story

Your company, **Nimbus**, has 50 teams. Each team calls LLM providers directly with its own API key. Things are a mess:

- The monthly AI bill doubled, and nobody knows which team caused it.
- One team's overnight batch job used up the company's provider quota, so the customer-facing chatbot got errors all morning.
- When the provider had an outage, every product broke at once.
- Many teams send the same questions again and again, and you pay every time.

Your job is to build **one gateway** that every team goes through.

## How it works (plain words)

Teams stop calling the provider. They call **your gateway** instead. For each request, the gateway:

1. Checks who is calling (which team, which API key).
2. Checks whether the team has budget and quota left.
3. Checks the cache, in case someone already asked this.
4. Picks a provider and model.
5. **Streams** the answer back word by word, as the model writes it.
6. Records exactly how many tokens were used, and what it cost.

## What you'll learn

Streaming responses (SSE) · long-lived requests · cancelling work when the client leaves · rate limiting by **cost** instead of by request count · fairness between teams · priority (chat vs batch) · provider failover · hedged requests · exact and semantic caching · usage metering · reconciliation against an invoice · budget enforcement.

## Words you'll meet

| Word | Simple meaning |
|---|---|
| **Token** | A small piece of text (about ¾ of a word). Providers charge per token, both for input (your prompt) and output (the answer). |
| **Streaming / SSE** | Sending the answer piece by piece over one open HTTP response, instead of all at once at the end. SSE (Server-Sent Events) is a simple format for this. |
| **TTFT** | Time To First Token: how long until the user sees the first word. This is what users "feel". |
| **Tokens/sec** | How fast the rest of the answer arrives after the first token. |
| **Tenant** | One customer of your gateway, here one team. |
| **Provider** | A company or server that runs the model. |
| **Embedding** | A list of numbers that represents the *meaning* of a text. Similar meanings give similar numbers. |
| **Semantic cache** | A cache that returns a saved answer if a new question *means the same thing* as an old one, even if the words are different. |
| **Metering** | Counting usage so you can bill for it. |

## Scope

| In | Out |
|---|---|
| Gateway, tenants and keys, limits, priority, failover, caching, metering | Training or hosting real models |
| A **fake LLM server** you build (free to run) | Prompt engineering |
| Optional: a real local model (Ollama) for a final demo | Real provider bills while you're learning |

**Tech:** Java or Go. Redis (counters, cache). Postgres (usage, tenants). k6 (it supports streaming responses). Prometheus + Grafana from project 05. Optional: Ollama for a real local model and local embeddings.

---

## Base system spec

### Functional requirements
1. Admins create tenants and API keys, with limits and budgets.
2. Tenants send chat requests to the gateway with their key.
3. The gateway streams the answer back, token by token.
4. At the end of each response, the gateway reports usage: input tokens, output tokens, cost.
5. Tenants can see their usage and spend.

### API contract

```
POST /v1/chat        hdr: X-Api-Key
  body: {model, messages: [{role, content}], maxTokens, stream: true, priority?: "interactive"|"batch"}
  -> 200 text/event-stream
       event: token   data: {"text": "..."}
       event: token   data: {"text": "..."}
       event: done    data: {"inputTokens": N, "outputTokens": M, "costMicros": C, "provider": "..."}
  -> 429 {error: "RATE_LIMITED" | "BUDGET_EXCEEDED", retryAfterMs}

POST /admin/tenants              {name, tokensPerMinute, maxConcurrentStreams, monthlyBudgetMicros}
POST /admin/tenants/{id}/keys    -> {apiKey}
GET  /tenants/{id}/usage?from=&to=
```

### Fake LLM server (build this first; about 80 lines)

Run **2-3 instances** that act as different "providers", each with its own price and speed. Every one streams fake tokens.

| Knob | Meaning |
|---|---|
| `TTFT_MS` | Delay before the first token |
| `TOKENS_PER_SEC` | Streaming speed after that |
| `PRICE_IN` / `PRICE_OUT` | Price per 1k input / output tokens |
| `QUOTA_TPM` | Tokens per minute allowed for **your whole company**. Above it, return 429. |
| `MAX_STREAMS` | Max concurrent streams. Above it, latency grows. |
| `ERROR_RATE` | Fraction of requests failing immediately |
| `STALL_RATE` | Fraction that stop streaming halfway and hang |
| `OUTAGE` | Admin switch: everything fails |

It must keep **its own usage log** (request ID, tokens it generated, cost). That log plays the role of the provider's invoice.

> Counting tokens: pick a simple rule (for example, 1 word = 1 token) and use it in both the fake server and the gateway. In L1 you'll find out why real token counting is harder.

### Billing checker (your referee)

After every test, compare the **gateway's metered usage** per tenant with the **fake providers' usage logs**:

| ID | Invariant | From |
|---|---|---|
| B1 | Every token a provider billed is attributed to exactly one tenant | L0 |
| B2 | Gateway total cost = provider total cost (difference = 0, or a bounded amount you can explain) | L0 |
| B3 | No tenant exceeded its hard budget by more than a bound you set | L5 |

### Metrics to export
TTFT p50/p99 · tokens/sec per stream · active streams · tokens/min per tenant · cost/min · cache hit rate · failovers/min · 429s by reason.

### Results table: `docs/results.md`

| Level | Scenario | Streams | TTFT p50 | TTFT p99 | Gateway overhead | 429s | Cost | B1 | B2 | B3 |
|---|---|---|---|---|---|---|---|---|---|---|

---

## Level 0: A streaming pass-through proxy

**Incident:** None yet. Just forward requests to one fake provider and stream the answer back. Then you'll notice the first surprise: in your first version, tokens probably arrive **all at once at the end**.

**Done when:**
- [ ] Tokens reach the client **as they're generated**. TTFT through the gateway ≈ provider TTFT + small overhead. Measure the overhead.
- [ ] 500 concurrent streams on one gateway instance work. Memory per stream recorded.
- [ ] **Client disconnects mid-stream → the gateway cancels the provider call.** Otherwise you keep paying for tokens nobody reads. Prove it from the provider's log.
- [ ] B1 and B2 pass.

**Predict first:** How much memory does one open stream cost on your gateway? How many streams before it falls over?

**Design questions:**
1. What does an SSE response look like on the wire? What HTTP headers matter?
2. Where can **buffering** happen between the provider and the user: your code, your HTTP library, a reverse proxy, compression? How do you make sure each token is sent right away?
3. A request can last 2 minutes. What timeouts make sense? What's the difference between a **total timeout** and an **idle timeout** (no token for N seconds)?
4. How does your gateway notice the client has gone? How does that cancellation reach the provider call?
5. Thread model: one thread per stream, or async? Look back at project 02 L1 and your `Concurrency/BuildServer` threading notes. What does a stream spend most of its time doing?

**Search:** `Server-Sent Events format`, `HTTP chunked transfer encoding`, `response flush streaming`, `proxy buffering SSE`, `context cancellation` (Go) / `CompletableFuture cancel` (Java), `idle timeout vs request timeout`.

**Self-evaluation:**
- 🟢 You have a TTFT comparison (direct vs through gateway) and a disconnect test that shows the provider stopped.
- 🔴 Your test client reads the whole response before timing it. That hides buffering.

<details><summary>Hint (tokens arrive all at once)</summary>
Look for anything that waits for "enough" bytes before writing to the socket.
</details>

---

## Level 1: Rate limit by tokens, not requests

**Incident:** Team A sends only 10 requests per second, but each includes a 100,000-token document. Your requests-per-second limit lets it through. The company's provider quota runs out, and **every team** starts getting 429s from the provider.

**Done when:**
- [ ] Per-tenant limits in **tokens per minute** (input + output) and **max concurrent streams**.
- [ ] The company-wide provider quota is **never exceeded** by your gateway. The provider log shows 0 provider-side 429s caused by you.
- [ ] Tenants that are limited get a clear 429 from **you**, with a retry hint.
- [ ] Works across **3 gateway instances**.

**Predict first:** With requests-per-second limits only, how many tokens/min can one tenant push through in your test?

**Design questions:**
1. You don't know the **output** size until the answer is finished. How do you charge a request against the limit *before* sending it? Reserve the maximum and refund the rest? Estimate? What if the answer runs longer than your estimate?
2. How do you count **input** tokens before sending? Different providers split text into tokens differently. What error does that create, and how big is it?
3. All teams share one provider quota. How do you split it: fixed shares, or can idle teams' share be borrowed? What happens when the owner wants it back?
4. Where do counters live so that 3 gateway instances agree? (Reuse what you learned in project 05 L3.)
5. Should a tenant's long-running stream keep "using" quota while it streams, or only when it starts?

**Search:** `token bucket variable cost`, `rate limit by cost`, `reservation and refund rate limiting`, `tokenizer BPE`, `tiktoken`, `hierarchical token bucket`, `quota borrowing`.

**Self-evaluation:**
- 🟢 A tenant sending huge prompts is limited correctly, and you can explain your estimation error.
- 🔴 Your limit only counts requests, or only counts input tokens.

---

## Level 2: Chat vs batch (fairness)

**Incident:** A team starts a "summarize 1M documents" batch job at 9am instead of at night. It's within its token limit, but it uses all the provider capacity. Chat users now wait **30 seconds** for the first word.

**Done when:**
- [ ] Two priority classes: `interactive` and `batch`.
- [ ] While batch runs flat out, **interactive TTFT p99 stays under a target you set**.
- [ ] Batch still uses all spare capacity, and it **never starves forever**. Measure its throughput.
- [ ] A team can't just mark everything as `interactive` to skip the line.

**Predict first:** Without priorities, what's chat TTFT p99 while the batch job runs?

**Design questions:**
1. Where do requests wait when there's no capacity: in the gateway, or should the gateway reject and make the client retry? What does each mean for TTFT?
2. Fair queuing by **tokens** vs by **requests**: why does it matter here? (Compare with project 03 L3.)
3. How do you keep batch from starving when there's always some chat traffic? (Search: aging.)
4. Should you **pause or cut off** a running batch stream to make room for chat? What would the batch user get?
5. How do you stop abuse of the `interactive` label? A quota per class? A different price?
6. Could batch traffic be sent to a cheaper or slower provider instead?

**Search:** `weighted fair queuing`, `priority queue starvation aging`, `admission control`, `batch vs online workloads`, `LLM batch API` (concept).

**Self-evaluation:**
- 🟢 You have a graph of chat TTFT with and without your fix while batch runs.
- 🔴 Batch gets nothing until chat traffic is exactly zero.

---

## Level 3: Provider outage and failover

**Incident:** Three bad days. Day 1: provider A is fully down for 40 minutes. Day 2: provider A is "up" but TTFT went from 500ms to 20 seconds. Day 3: provider A **stops streaming halfway** through 5% of answers.

**Done when:**
- [ ] Automatic failover to another provider or model, following a routing policy you write down.
- [ ] Slow and stalling providers are detected, not just dead ones.
- [ ] A **mid-stream failure** is handled in a defined way that you documented and tested.
- [ ] No flapping between providers. Recovery to the preferred provider happens smoothly.
- [ ] Tenants can **opt out** of failover (some teams need one specific model).

**Predict first:** How long does it take your gateway to notice a "slow, not dead" provider?

**Design questions:**
1. When is a provider "down" versus "slow"? Which signals do you use: errors, TTFT, tokens/sec, stalls?
2. A stream fails after 200 tokens. Options: retry from the start on another provider, return an error, or continue somehow. What does the user see in each case? Could they get duplicated or contradictory text?
3. Different models give different answers. When is failing over to another model **not** OK?
4. Retrying a request with a 100k-token prompt costs real money. How do you limit retry cost? (Reuse your retry budget from project 05 L2.)
5. **Hedging:** send to two providers and take whichever answers first. When is that worth paying for twice?
6. How do you check a provider is healthy again without sending real user traffic to a broken one?

**Search:** `circuit breaker`, `passive health checks`, `hedged requests`, `The Tail at Scale paper`, `fallback model routing`, `graceful degradation`.

**Self-evaluation:**
- 🟢 All three bad days are scripted as chaos scenarios, and you have a graph for each.
- 🔴 Your stall detection is "wait for the total timeout".

---

## Level 4: Caching (exact and semantic)

**Incident:** 30% of requests are the same 200 FAQ questions, from many teams' support bots. The bill is high, and users wait 2 seconds for answers that never change.

**Done when:**
- [ ] **Exact cache** with a correct cache key. Cached answers are also streamed.
- [ ] **Semantic cache**: you built a **labeled test set** of at least 200 question pairs, marked "same meaning" or "different meaning". You measured how often your cache returns a **wrong** answer at different similarity thresholds.
- [ ] Caches are **per tenant**. One team never sees another team's cached data. You have a test for it.
- [ ] Money saved and TTFT improvement measured.

**Predict first:** At a similarity threshold of 0.9, what percentage of cache hits will be wrong?

**Design questions:**
1. What must go into the exact cache key? Think about model, temperature, system prompt, max tokens, tenant.
2. Should you cache at all when the request asks for randomness (temperature > 0)?
3. "What's the capital of France?" vs "What's the capital of Germany?" Their embeddings may be very close. How does your test set catch this? How do you pick a threshold, and what does a wrong hit cost compared with a miss?
4. How do you search embeddings in the cache? Is brute force fine for 100k entries? (Project 09 is the deep dive.)
5. When does a cached answer go stale? Who decides the TTL?
6. Some providers can cache the **start of a prompt** on their side (prefix caching) to make repeat requests cheaper. How is that different from your cache? Could your routing help it work better?

**Search:** `semantic caching LLM`, `cosine similarity embeddings`, `precision recall threshold`, `cache key design`, `prompt prefix caching` (concept), `cache poisoning`, `multi-tenant cache isolation`.

**Self-evaluation:**
- 🟢 You have a threshold vs wrong-hit-rate chart from your own test set.
- 🔴 The semantic cache key ignores the tenant.

---

## Level 5: The bill doesn't match

**Incident:** Finance: "Your gateway says we spent **$41,200** last month. The provider invoice says **$43,900**. Where did $2,700 go?" Also: a team set a $500 budget and spent $1,100.

**Done when:**
- [ ] **B1, B2 pass under chaos:** gateway `kill -9` mid-stream, clients disconnecting, retries, failovers.
- [ ] **B3 passes:** a tenant with $10 left that starts 20 streams at once can't overspend by more than your bound.
- [ ] Daily **reconciliation** job compares your usage records with the provider's log and lists every difference with a reason.
- [ ] Usage recording doesn't slow streaming. Measure TTFT with and without it.

**Predict first:** Which chaos event will create the biggest billing gap?

**Design questions:**
1. When do you record usage: at the end of the stream? What if the gateway crashes in the middle, given the provider still bills for tokens it already generated?
2. Who is the **source of truth** for cost, you or the provider? (Same question as project 01 L2, different system.)
3. Usage events are written at high rate. How do you write them without slowing streams and without losing them? (Project 01 L6 outbox again?)
4. Hard budget cap with concurrent streams: how do you avoid overspending? Reservation like L1? What's your overspend bound?
5. Reconciliation: how do you match your records to the provider's? Which ID ties them together?

**Search:** `usage metering`, `idempotent usage events`, `transactional outbox`, `reconciliation`, `budget enforcement reservation`, `at-least-once aggregation dedupe`.

**Self-evaluation:**
- 🟢 Reconciliation explains **every** cent of difference.
- 🔴 Usage is counted only when a stream finishes normally.

---

## Stretch levels
- **Guardrails:** remove personal data (emails, phone numbers) from prompts before they leave the company. Where in the stream path does that run, and what does it do to TTFT?
- **Smart routing:** try a cheap model first, and use the expensive one only if needed. How do you decide "needed"?
- **Prompt logging with privacy:** store prompts for debugging without storing secrets.
- **Tracing:** one trace per request across gateway → cache → provider.

## Graduation: answer out loud
1. Design an LLM gateway for 5,000 engineers. Draw it.
2. Why rate-limit by tokens? How do you handle not knowing the output size up front?
3. How do you keep chat fast while batch jobs run?
4. A provider stalls mid-answer. What do you do, and what does the user see?
5. What are the risks of a semantic cache, and how did you measure them?
6. How do you make your billing match the provider's invoice?

## Taking it further
A hosted gateway for small startups (micro-SaaS) · per-user analytics dashboard · eval-based routing (measure answer quality per model) · plug in real providers and a local Ollama model · open-source it.
