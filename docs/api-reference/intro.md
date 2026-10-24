## About this API

The master data service holds the slow-moving reference data of the travel business - destinations, hotels and their rooms, attractions, vehicles and drivers, guides, restaurants, packages, promotions, road distances, info articles - and the **prices** attached to them. Prices are never overwritten: every change closes the old row and opens a new one, so history is kept and any date can be priced.

It is an internal back-office service. Agents never call it; the quotation service asks it for single prices (`GET /rates/resolve`).

### Where to call it

| From | Base URL |
|---|---|
| Through the API gateway (normal) | `https://<gateway-host>/master-data-service/api/v1/master-data` |
| Directly (local development) | `http://localhost:8082/api/v1/master-data` |

Every path in this reference is shown from `/api/v1/master-data`. Interactive documentation of the same endpoints is served at `/swagger-ui.html` (and the OpenAPI document at `/v3/api-docs`), without a token.

### Authentication and roles

Send a Keycloak access token on every request: `Authorization: Bearer <token>`. The token's realm roles decide access:

| Role | Can do |
|---|---|
| `ADMIN`, `MANAGER` | Everything: read and write. |
| `QUOTATION_SERVICE` | Only `GET /rates/resolve` (a service account used by the quotation service). |
| `AGENT`, or no role | Nothing: `403`. |

A missing, expired or invalid token is `401`. The **Who can call it** line on each endpoint is read from the code, so it is always current. Records written through the API remember who wrote them: a rate's `createdBy` is the token's `preferred_username`.

### Conventions used everywhere

**Data types.** JSON only. Dates are `YYYY-MM-DD`. Ids are integers. A reference to another record is a small object `{ "id": 1, "name": "Sigiriya" }`. Money and percentages are decimals; the unit of a price is stated beside it (`USD`, `LKR` or `PERCENT`).

**Lists** are paged: `?page=0&size=20` (size 1 to 200; alerts default to 50). Every list answers with the same envelope:

```json
{ "content": [ ... ], "totalElements": 42, "totalPages": 3, "number": 0, "size": 20,
  "first": true, "last": false, "empty": false }
```

**Searching and sorting.** `q` is a case-insensitive "contains" search (`%` and `_` are plain text). `sort=field,asc` or `sort=field,desc`, repeatable (`sort=starRating,desc&sort=name,asc`). Each list documents the fields it can be sorted on; an unknown field is a `400`, never silently ignored. Each list also documents its own filters (`destinationId`, `active`, ...).

**Creating** (`POST`) returns `201`, the new record, and a `Location` header. Required fields are listed in each endpoint's description. All problems are reported together, not one at a time.

**Quick edit** (`PATCH`) sends **only the fields that changed**. A field sent as `null` clears an optional field; a field left out stays as it is. Unknown field names are rejected (a typo should never silently do nothing), and an empty body is rejected.

**Replace the whole set** (`PUT` on a child list such as `/properties/{id}/child-rules`) sends the complete list every time; whatever is not in the list is removed. Send `[]` to clear it. A problem inside the list is reported against the item, e.g. `[1].minAge` or `closedDays[1]`.

**Deleting.** Hotels, vehicles, drivers, guides and promotions are never removed: `DELETE` switches `active` to `false`, and `PATCH {"active": true}` brings them back. Everything else is removed for real, and refused with `409 IN_USE` while other data still refers to it. Price history is never deleted.

**Names and codes** that are really a closed list (a vehicle's `method`, a driver's `role`, a promotion's `type`, a day name ...) are accepted in any letter case and stored in capitals.

### Prices (rates)

All prices live in **rate families** (`property`, `vehicle`, `attraction`, `guide-accommodation`, `misc`, `tuk-tuk`, `package`, `tax`, `promotion`, `transport`) and are read and written through the same four calls: `GET /rates/{family}`, `PUT /rates/{family}`, `PATCH /rates/{family}/{id}` and `POST /rates/{family}/adjust`. `GET /rates/families` lists the families, the **keys** each one takes (for example a hotel rate is keyed by property, room type, season, occupancy and nationality) and its unit.

- **Setting a price** closes the row currently in force and opens a new one. `effectiveFrom` defaults to today. The old row ends on the day the new one starts: a rate is valid from `effectiveFrom` **up to, but not including,** `effectiveTo`.
- **"Applies to all".** A key that may be left out (for example `nationalityId`) means "every value". A specific row always wins over an "all" row when a price is resolved.
- **History.** `GET /rates/{family}?history=true` includes closed rows. `isCurrent` on each row is worked out for you.
- **Future prices.** A row that has not started yet can be deleted; a row that has started cannot.
- **Units.** `property`, `attraction`, `misc`, `package`: USD. `vehicle` (per km), `tuk-tuk`, `guide-accommodation`, `transport`: LKR. `tax`: PERCENT. `promotion`: PERCENT for a percentage promotion (never above 100), USD otherwise.
- **Resolving.** `GET /rates/resolve` returns the single price that applies to a fully specified combination and says whether it was an exact match or a fall-back to the "all" row. If nothing applies it is a business `404` with code `NO_RATE`.

### Errors

Every error is `application/problem+json`. The `code` is stable and is what a client should switch on; `detail` is for people; `fieldErrors` maps a field (or a list item such as `closedDays[1]`) to what is wrong with it.

| HTTP | `code` | Meaning |
|---|---|---|
| 400 | `VALIDATION_ERROR` | Bad input; see `fieldErrors`. Also malformed JSON and unknown sort fields. |
| 401 | `UNAUTHORIZED` | No token, or an invalid or expired one. |
| 403 | `FORBIDDEN` | The token's roles do not allow this. |
| 404 | `NOT_FOUND` | No such record (or unknown lookup / rate family). |
| 404 | `RATE_NOT_FOUND`, `NO_RATE` | No such rate row; or nothing is priced for the requested combination. |
| 409 | `DUPLICATE` | The value must be unique and already exists. |
| 409 | `IN_USE` | Cannot be deleted while other data refers to it. |
| 409 | `TYPE_LOCKED` | A promotion's type cannot change once it has a value. |
| 409 | `RATE_STARTS_BEFORE_CURRENT` | A new rate must start after the current one started (use `PATCH` to correct a mistake). |
| 409 | `FUTURE_RATE_EXISTS` | A future rate is already queued for those keys; change or delete it first. |
| 409 | `RATE_NOT_PENDING` | Only a rate that has not started yet can be deleted. |
| 500 | `INTERNAL_ERROR` | Unexpected; the detail is generic on purpose. |
