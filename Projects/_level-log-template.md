# <Project>: Level N, <Title>

Started: YYYY-MM-DD · Finished: YYYY-MM-DD · Self-score: _/4

---

## 1. Prediction (write BEFORE coding or researching)
- **What will break:**
- **At what load / under what condition:**
- **Why I think so (mechanism):**
- **Confidence:** low / medium / high

## 2. Design answers (BEFORE searching; 2-5 lines each)
> Copy each design question from the project file. Answer it. After research, add a "Revised:" line if your answer changed. Don't delete the original. That gap is where you learned something.

**Q1.**
- First answer:
- Revised:

**Q2.**
- First answer:
- Revised:

## 3. Options considered

| Option | Pros | Cons | Picked? Why / why not |
|---|---|---|---|
| | | | |
| | | | |

## 4. Reproduction (the failure, on demand)
- **Command / test:**
- **What I observed before the fix:**
- **How reliably it fails:** _/_ runs

## 5. Results (at least 3 runs per config)

| Config | Offered load | p50 | p99 | max | Errors | Invariant violations | Notes |
|---|---|---|---|---|---|---|---|
| before fix | | | | | | | |
| option A | | | | | | | |
| option B | | | | | | | |

Machine / setup notes (CPU, what else was running, same-host load generator?):

## 6. Prediction vs reality
- Where I was right:
- Where I was wrong, and why:

## 7. Postmortem (5 lines)
1. **What happened:**
2. **Root cause (mechanism, not tool):**
3. **Fix:**
4. **What breaks this fix next (10× load, or a new failure):**
5. **What I'd tell another engineer in one sentence:**

## 8. Self-score
- Score (1-4):
- Why not one higher:

## 9. Open questions / things to revisit
-
