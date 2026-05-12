# Implementation Split (Current Phase)

## P0 done in backend

1. Frozen API contract docs: `API.md`.
2. Frozen metric definitions: `METRICS.md`.
3. Unified response envelope: `code/message/data`.
4. `POST /api/simulation/run` now returns full report payload (snake_case) directly.
5. `GET /api/simulation/report/latest` returns same report payload structure.
6. Input validation + normalization rules implemented.
7. Core simulation invariants enforced after each event.
8. Seed-based deterministic simulation supported.
9. Minute-level timeline output added for frontend charting.
10. Baseline unit + integration tests added.

## Backend next tasks

1. Add report history APIs:
2. `GET /api/simulation/report/list`
3. `GET /api/simulation/report/{reportId}`
4. Add configurable arrival/service distributions (Poisson/exponential).
5. Add OpenAPI/Swagger generation from code annotations.

## Frontend/C++ consumer tasks

1. Parse response envelope:
2. `code == 0` means success, otherwise read `message`.
3. Read report from `data` object (snake_case keys).
4. Build dashboard cards from `data.summary`:
5. arrived, abandoned, served, dine_in, takeaway, avg_wait, queue_peak, seat_utilization.
6. Build charts from `data.summary.timeline`:
7. total_queue_size, occupied_seats, empty_seats, cumulative counters.
8. Add user input controls mapped to request fields in `API.md`.

## Joint alignment checklist

1. Confirm all field names from `API.md` before UI binding.
2. Confirm metric formulas from `METRICS.md` before chart labels.
3. Confirm timeline granularity (minute) for all chart axes.
4. Confirm error handling flow using `code/message`.