# master-data-service — API design

Status: **draft v0.1**. Companion file: [`openapi.yaml`](openapi.yaml) (the machine-readable skeleton of everything below).
Data source: the `tour-management` Postgres schema (41 tables, see [Data model → API map](#3-data-model--api-map)).

## 1. Principles

1. **Aggregates for people, tables for storage.** The FE edits a *Property*, a *Vehicle*, an *Attraction* — never `room_type` + `property_child_rule` + `baby_cot_option` separately. Read endpoints return the aggregate; child collections are edited through the parent.
2. **Every price goes through one rate engine.** The ten `*_rate` / `*_value` tables are all "append-only history rows". They share one set of endpoints (`/rates/{family}`) instead of ten bespoke ones.
3. **One bootstrap call for dropdowns.** Cold reference data is fetched once (`GET /lookups`) and cached by the FE.
4. **Quick edit is the default path, forms are the exception.** Single-field `PATCH`, and a rate change is "type a number".
5. **The contract is the OpenAPI file.** The FE generates its types/client from it; nobody hand-writes URLs.

## 2. Decisions taken

| # | Decision | Consequence |
|---|---|---|
| D1 | Vehicle rates keep their `season_id NOT NULL` (3 season rows per vehicle type, same value) | `PUT /rates/vehicle` without a `seasonId` writes **all** season rows in one transaction; the FE only ever sees one rate per vehicle type unless a season is named |
| D2 | Currency: **USD** for all rates **except** vehicle and transport per-km, tuk-tuk fares and guide accommodation, which are **LKR** | Every rate row carries a `unit` (`USD` / `LKR` / `PERCENT`); fixed per family, exposed by `GET /rates/families` |
| D3 | Writes: **ADMIN and MANAGER** | `hasAnyRole('ADMIN','MANAGER')` on every non-GET |
| D4 | This is an **internal back-office service**: AGENT has no access at all | Every endpoint requires ADMIN or MANAGER. Agents only ever see a price inside a quotation, which the quotation service builds by calling `GET /rates/resolve` server-to-server; see [§9](#9-authorization) |
| D5 | `technical_visit_category` is **soft-deleted** (`active=false`) | `DELETE /lookups/technical-visit-categories/{id}` deactivates instead of removing; lookups for it return `active`, and `GET /lookups` returns only active ones unless `?includeInactive=true` |

## 3. Data model → API map

| Tier | Tables | Churn | API surface |
|---|---|---|---|
| **Cold reference** | `destination`, `nationality`, `language`, `dietary_category`, `meal_plan_type`, `hotel_inclusion`, `vehicle_type`, `misc_cost_type`, `tax_rate`, `technical_visit_category`, `season` | Almost never | `/lookups` (bundle) and `/lookups/{name}` (CRUD) |
| **Structural aggregates** | `property` (+`room_type`, `property_child_rule`, `property_inclusion`, `baby_cot_option`, `property_dietary_capability`), `attraction` (+`tuk_tuk_location`), `restaurant` (+`restaurant_closed_day`, `restaurant_dietary_capability`), `site_tour_package` (+`site_tour_package_site`), `location_distance`, `info_article` | When onboarding an entity | `/properties`, `/attractions`, `/restaurants`, `/packages`, `/location-distances`, `/info-articles` |
| **Operational** | `vehicle`, `driver`, `guide` (+`guide_language`), `promotion` | Weekly / monthly | `/vehicles`, `/drivers`, `/guides`, `/promotions`, `/alerts` |
| **Hot — versioned prices** | `property_rate`, `vehicle_rate`, `attraction_rate`, `guide_accommodation_rate`, `misc_cost_rate`, `tuk_tuk_fare_rate`, `site_tour_package_rate`, `tax_rate_value`, `promotion_value`, `transport_rate_config` | Every price revision | `/rates/{family}` + `/properties/{id}/rates` (grid) |

Combined views (read as one): Property, Attraction, Vehicle (+type, driver, assistant, expiry warnings), Guide (+languages), Restaurant (+closed days, dietary), Package (+sites, current rate), Promotion (+current value).
Standalone: every lookup, `location-distances`, `info-articles`.

## 4. Conventions

- **Base path:** `/api/v1/master-data`. Through the gateway: `/master-data-service/api/v1/master-data/…` (service-id prefixed, like user-service).
- **IDs:** integers (the schema uses `integer` identity columns).
- **FK expansion:** every FK is returned as `{ "id": 7, "name": "Habarana" }` (`Ref`), so the FE never does a second lookup to render a label. Writes accept plain ids (`destinationId`).
- **Lists:** Spring `Page` — `content`, `totalElements`, `totalPages`, `number`, `size`, `first`, `last`, `empty`. Query: `page` (0-based), `size`, `sort`.
  **`sort` is `property,direction` and repeatable:** `?sort=name,asc&sort=id,desc`. In the Swagger "Try it out" JSON box it is an array: `"sort": ["name,asc"]`. Sortable properties are the ones on the list-row schema.
  List rows are **summaries** (slim); `GET /{id}` returns the full aggregate. `?q=` is a case-insensitive contains match on name/code.
- **As-of reads:** `?at=2026-12-01` evaluates "current" rates on that date. Default is today. `isCurrent` is computed by the server — the FE never does date maths.
- **Expansion:** `GET /{id}?include=rates,roomTypes` for optional heavy parts (documented per resource).
- **Writes:** `POST` → `201` + body + `Location`. `PATCH` → `200` + updated body (send only changed fields). `PUT` on collections replaces the whole collection. `DELETE` → `204`.
- **Delete semantics:** property, vehicle, driver, guide, promotion and technical visit category are **soft-deleted** (`active=false`). Everything else is a hard delete, refused with `409 IN_USE` if still referenced.
- **Optimistic locking:** mutable aggregates carry `version`. Send it back on `PATCH`/`PUT` to opt in; a mismatch is `409 VERSION_CONFLICT`. Omit it for fire-and-forget quick edits. Rate rows are append-only and need no version. **Not implemented yet:** the existing tables have no `version` column, and adding one is a schema change, so hotel edits are last-write-wins for now.
- **Audit:** `createdBy` always comes from the JWT (`preferred_username`), never from the request body.
- **Money:** JSON numbers with at most 2 decimal places, because every rate column is `numeric(10,2)` (tax percentage is `numeric(5,2)`, max 100). Negative values and anything over the column maximum are rejected with a field error. `unit` states what the number is.
- **Dates:** ISO-8601 `YYYY-MM-DD`; timestamps in UTC.
- **Errors:** RFC 7807 `application/problem+json` — `{type, title, status, detail, code, fieldErrors: {field: message}}`. The FE binds `fieldErrors` straight onto form fields.

## 5. The rate engine

### 5.1 Families

`GET /rates/families` returns this table as data, so the FE can render a generic rate grid/form from metadata.

| `family` | Table | Key fields (`keys`) | Unit | "All" when key is null |
|---|---|---|---|---|
| `property` | `property_rate` | `propertyId`, `roomTypeId`, `seasonId`, `occupancy`, `nationalityId?` | USD | nationality |
| `vehicle` | `vehicle_rate` | `vehicleTypeId`, `seasonId` (optional on write → all seasons) | LKR per km | — |
| `attraction` | `attraction_rate` | `attractionId`, `rateType` (one of `PER_PERSON`, `FIXED_GROUP_BASE`, `EXTRA_PERSON`, `GUEST_ENTRY_FEE`, `GUIDE_ENTRY_FEE`, `CHILD_DISCOUNT`, `JEEP_RATE` - the database's CHECK list) | USD | — |
| `guide-accommodation` | `guide_accommodation_rate` | `seasonId`, `destinationId?` | LKR | destination |
| `misc` | `misc_cost_rate` | `miscCostTypeId`, `nationalityId?` | USD | nationality |
| `tuk-tuk` | `tuk_tuk_fare_rate` | `tukTukLocationId` | LKR | — |
| `package` | `site_tour_package_rate` | `packageId` | USD | — |
| `tax` | `tax_rate_value` | `taxRateId` | PERCENT | — |
| `promotion` | `promotion_value` | `promotionId` | PERCENT for a `PERCENTAGE` promotion (capped at 100), USD otherwise | — |
| `transport` | `transport_rate_config` | *(none — singleton)* | LKR per km | — |

> Confirmed: `misc` and `package` are USD; `guide-accommodation` and `tuk-tuk` are LKR (D2). The unit is a property of the family registry, so changing one later needs no endpoint changes.

### 5.2 Semantics (all families)

Intervals are **half-open `[effectiveFrom, effectiveTo)`** — this matches the existing `CHECK (effective_to > effective_from)`. At most one open row (`effective_to IS NULL`) per key combination (the existing `ux_*_active` indexes).

`PUT /rates/{family}` — **set rate** (the "quick edit"):

```json
{ "keys": { "propertyId": 42, "roomTypeId": 1, "seasonId": 1, "occupancy": 2, "nationalityId": null },
  "rate": 185.00,
  "effectiveFrom": "2026-11-01" }
```

1. `effectiveFrom` defaults to **today**.
2. If no open row exists → insert.
3. If an open row exists → set its `effective_to = effectiveFrom`, insert the new open row. One transaction.
4. `effectiveFrom <= open.effective_from` → `409 RATE_STARTS_BEFORE_CURRENT` (use `PATCH` to correct a mistake instead).
5. A future-dated row already pending for these keys → `409 FUTURE_RATE_EXISTS` (edit or delete that pending row).
6. **Vehicle only:** `seasonId` omitted → steps 2–3 run for every season (D1); `seasonId` present → just that season.
7. Response: the new `RateRow`(s).

`PATCH /rates/{family}/{id}` — **correct** a row in place (typo fix). Allowed fields: `rate`, `effectiveFrom`, `effectiveTo`. `DELETE` only removes a **pending future** row; history is never deleted.
> Gap: the schema has `created_by` but no `updated_by`/audit trail for in-place corrections. Recommended follow-up: a small `rate_audit` table written by the service. Not required for v1.

`POST /rates/{family}/adjust` — **bulk percent/amount change** with preview:

```json
{ "filter": { "seasonId": 1, "propertyId": [42, 43] },
  "mode": "PERCENT", "value": 8, "rounding": "NEAREST_1",
  "effectiveFrom": "2026-11-01", "dryRun": true }
```
`dryRun=true` returns `[{keys, oldRate, newRate}]` and writes nothing; `false` applies it atomically through the same rule as `PUT`.

`GET /rates/{family}?filter[propertyId]=42&filter[seasonId]=1` — rates for a key subset. `at=` for as-of, `history=true` to include closed rows (this is the audit/history view).

`GET /rates/resolve?family=property&propertyId=42&roomTypeId=1&seasonId=1&occupancy=2&nationalityId=9&at=2026-12-01` — **the quote-engine lookup**. Returns one price. For families with a nullable "all" key it tries the exact key first, then falls back to the `NULL` row, and says which it used (`"matched": "FALLBACK_ALL_NATIONALITIES"`). `404 NO_RATE` if neither exists — the booking service must handle that explicitly.

`PUT /properties/{id}/rates` — **grid save**: array of the same `{keys, rate}` items for one property, applied atomically (all-or-nothing). Designed for the room type × season × nationality × occupancy matrix editor.

## 6. Endpoint catalogue

Paths are relative to `/api/v1/master-data`. Every endpoint, read or write, requires ADMIN or MANAGER (the one exception is the quotation service's service account on `GET /rates/resolve`). See [§9](#9-authorization).

### Bootstrap & lookups
| Method & path | Purpose |
|---|---|
| `GET /lookups` | All cold reference data in one response. Sends an `ETag` and `Cache-Control: no-cache`: the FE keeps it and revalidates with `If-None-Match`, getting a cheap `304` until something changes, so an edit shows up immediately |
| `GET /lookups/{name}` | One lookup. `name` ∈ `destinations`, `nationalities`, `languages`, `seasons`, `dietary-categories`, `meal-plans`, `hotel-inclusions`, `misc-cost-types`, `vehicle-types`, `tax-rates`, `technical-visit-categories` |
| `POST /lookups/{name}` · `PATCH /lookups/{name}/{id}` · `DELETE /lookups/{name}/{id}` | Edit (rare). `409 IN_USE` on delete if referenced |

### Aggregates
| Resource | List / create | Item | Child endpoints |
|---|---|---|---|
| **properties** | `GET`/`POST /properties` (`q`, `destinationId`, `active`, `starRating`) | `GET`/`PATCH`/`DELETE /properties/{id}` (`include=rates`) | `POST /properties/{id}/room-types`; `PATCH`/`DELETE /room-types/{id}`; `PUT /properties/{id}/child-rules`; `PUT /properties/{id}/inclusions`; `PUT /properties/{id}/dietary`; `PUT /properties/{id}/baby-cot`; `PUT /properties/{id}/rates` (grid) |
| **attractions** | `GET`/`POST /attractions` (`q`, `destinationId`, `safari`, `pricingMethod`) | `GET`/`PATCH`/`DELETE /attractions/{id}` | `PUT`/`DELETE /attractions/{id}/tuk-tuk` give the attraction a tuk-tuk option (returns the `locationId` the fare is keyed by); the fare itself is a `tuk-tuk` rate |
| **restaurants** | `GET`/`POST /restaurants` (`q`, `destinationId`, `dietaryCategoryId`, `closedOn`, `openOn`) | `GET`/`PATCH`/`DELETE /restaurants/{id}` | `PUT /restaurants/{id}/closed-days` (day names) and `PUT /restaurants/{id}/dietary` (category ids) replace the sets; `POST` also accepts `closedDays` and `dietaryCategoryIds` |
| **packages** | `GET`/`POST /packages` (`q`, `attractionId`, `at`) | `GET`/`PATCH`/`DELETE /packages/{id}` | `PUT /packages/{id}/sites` (attraction ids) replaces the set; `POST` also accepts `attractionIds`. The price is a rate: `PUT /rates/package` |
| **vehicles** | `GET`/`POST /vehicles` (`q`, `vehicleTypeId`, `method`, `active`, `expiringWithinDays`) | `GET`/`PATCH`/`DELETE /vehicles/{id}` | — (the driver and assistant are plain fields: `driverId`, `assistantId`) |
| **drivers** | `GET`/`POST /drivers` (`q` over name / NIC / phone / licence, `role`, `active`, `chauffeur`) | `GET`/`PATCH`/`DELETE /drivers/{id}` | each person lists the vehicles they are assigned to |
| **guides** | `GET`/`POST /guides` (`q` over name / nickname / guide no / NIC / phone / email / town, `guideType`, `languageId`, `active`) | `GET`/`PATCH`/`DELETE /guides/{id}` | `PUT /guides/{id}/languages` replaces the set of language ids; `POST` also accepts `languageIds` to set them in the same call |
| **promotions** | `GET`/`POST /promotions` (`q`, `type`, `active`, `validOn`, `destinationId`, `seasonId`, `at`) | `GET`/`PATCH`/`DELETE /promotions/{id}` (delete = deactivate) | value via family `promotion` (`PUT /rates/promotion`) |
| **location-distances** | `GET`/`POST /location-distances` (`q`, `destinationId`, `fromDestinationId`, `toDestinationId`, `routeType`, `needsReview`) | `GET`/`PATCH`/`DELETE /location-distances/{id}` | `GET /location-distances/between?fromDestinationId&toDestinationId` - every route between two destinations, either direction |
| **info-articles** | `GET`/`POST` (`category`, `destinationId`) | `GET`/`PATCH`/`DELETE` | — |

`PUT` on a child collection **replaces the whole set** (send the full list). That is intentionally simpler for the FE than add/remove diffs; it is only used for collections nothing else references by id (child rules, inclusions, dietary, closed days, languages, package sites). Room types are real CRUD because rates point at them.

### Rates
`GET /rates/families` · `GET /rates/{family}` · `PUT /rates/{family}` · `PATCH`/`DELETE /rates/{family}/{id}` · `POST /rates/{family}/adjust` · `GET /rates/resolve`

### Operations
| Method & path | Purpose |
|---|---|
| `GET /alerts` | Vehicle insurance / revenue-licence expiries (`withinDays`, default 30), vehicles with no driver, rates ending soon, properties with no current rate |
| `GET /import/{table}/template` | Download the `.xlsx` template for a table — same layout as today's `Immediate_Master_Data_Upload_Template.xlsx` sheets |
| `POST /import/{table}?dryRun=true` | Upload `.xlsx`/`.csv`. Dry run returns per-row validation errors and counts; `dryRun=false` commits atomically |
| `GET /export/{table}?format=xlsx\|csv` | Download current data |

`{table}` ∈ `nationalities`, `transport-rates`, `guide-accommodation-rates`, `attractions`, `vehicle-rates`, `misc-cost-rates`, `property-dietary`, `property-child-rules`, `restaurants`, `site-tour-packages`, `promotions`, `properties`, `vehicles`, `drivers`, `guides` (mirrors the two business templates; extend as needed).

## 7. JSON shapes

### Property (detail)
```json
{
  "id": 42, "code": "HTL-0042", "name": "Cinnamon Lodge", "active": true, "starRating": 5,
  "address": "Habarana", "website": null, "email": null, "phone": null,
  "destination": { "id": 7, "name": "Habarana" },
  "roomTypes": [{ "id": 1, "name": "Deluxe", "baseCapacity": 2, "extraBedAllowed": true }],
  "childRules": [{ "id": 11, "minAge": 3, "maxAge": 11, "requiresBed": false, "cost": 25.00 }],
  "inclusions": [{ "inclusion": { "id": 3, "name": "Breakfast" }, "cost": null }],
  "babyCot": { "available": true, "cost": 10.00 },
  "dietary": [{ "category": { "id": 2, "name": "Vegan" }, "supported": true }],
  "rates": null
}
```
`rates` is `null` unless `?include=rates`, then an array of `RateRow`.

### Property (list row)
```json
{ "id": 42, "code": "HTL-0042", "name": "Cinnamon Lodge", "active": true, "starRating": 5,
  "destination": { "id": 7, "name": "Habarana" }, "roomTypeCount": 4,
  "fromRate": { "rate": 120.00, "unit": "USD" } }
```

### RateRow
```json
{ "id": 901, "family": "property",
  "keys": { "propertyId": 42, "roomTypeId": 1, "seasonId": 1, "occupancy": 2, "nationalityId": null },
  "labels": { "propertyId": "Cinnamon Lodge", "roomTypeId": "Deluxe", "seasonId": "Summer", "nationalityId": null },
  "rate": 185.00, "unit": "USD",
  "effectiveFrom": "2026-04-01", "effectiveTo": null, "isCurrent": true,
  "createdAt": "2026-10-18T06:12:44Z", "createdBy": "prabash" }
```
`labels` lets a generic grid render key columns without joining client-side.

### Vehicle
```json
{ "id": 5, "registrationNo": "CAB-1234", "vehicleType": { "id": 2, "name": "Van - Flat Roof" },
  "seatingCapacity": 9, "method": "OWNED", "ownerName": null,
  "insuranceCompany": "Ceylinco", "policyNumber": "P-889", "insuranceExpiryDate": "2026-11-02",
  "revenueLicenseExpiryDate": "2027-01-15",
  "driver": { "id": 3, "name": "Nimal Perera" }, "assistant": null, "active": true,
  "warnings": [{ "code": "INSURANCE_EXPIRING", "daysLeft": 15 }], "version": 2 }
```

### Lookup bundle (`GET /lookups`)
```json
{ "destinations": [{ "id": 7, "name": "Habarana", "region": "North Central", "country": "Sri Lanka" }],
  "nationalities": [{ "id": 9, "name": "India", "isoCode": "IN" }],
  "seasons": [{ "id": 1, "name": "Summer", "startDate": "2026-04-01", "endDate": "2026-10-31" }],
  "vehicleTypes": [{ "id": 1, "name": "Car", "minCapacity": 1, "maxCapacity": 2 }],
  "languages": [], "dietaryCategories": [], "mealPlans": [], "hotelInclusions": [],
  "miscCostTypes": [], "taxRates": [], "technicalVisitCategories": [] }
```

### Problem
```json
{ "type": "about:blank", "title": "Validation failed", "status": 400, "code": "VALIDATION_ERROR",
  "detail": "2 fields are invalid", "fieldErrors": { "rate": "must be greater than 0", "seasonId": "unknown season" } }
```

Stable `code` values: `VALIDATION_ERROR`, `NOT_FOUND`, `RATE_NOT_FOUND`, `IN_USE`, `DUPLICATE`, `VERSION_CONFLICT`, `RATE_STARTS_BEFORE_CURRENT`, `FUTURE_RATE_EXISTS`, `RATE_NOT_PENDING`, `NO_RATE`, `IMPORT_INVALID`, `UNAUTHORIZED`, `FORBIDDEN`, `INTERNAL_ERROR`.

## 8. Making it easy for the FE

- **Generate, don't write:** `openapi-typescript` / `openapi-generator` against `/v3/api-docs` (springdoc) or this file → typed client. Re-generate on each backend change.
- **Uniform URL grammar** — `/{resource}`, `/{resource}/{id}`, `/rates/{family}` — so one generic data-table + one generic rate-grid component covers most screens.
- **`GET /rates/families` and `GET /lookups` are metadata endpoints**: they let the FE build rate forms and dropdowns without hard-coding table knowledge.
- **`labels`, `Ref`, and `isCurrent` pre-computed** — render-ready rows, no joins or date logic in the browser.
- **Inline edit = `PATCH` with one field;** rate edit = `PUT /rates/...` with one number. Neither needs a form dialog.
- **Matrix editor** for property rates saves with one call (`PUT /properties/{id}/rates`), atomically — no half-saved grids.
- **Safe bulk:** adjust and import both have `dryRun`, so the UI can show a diff/preview step before commit.
- **CORS:** the FE should call through the gateway (same pattern as user-service: `app.cors.allowed-origins`).

## 9. Authorization

Master data is an **internal back-office service**. Roles are the ones user-service puts in the Keycloak token: `ADMIN`, `MANAGER` (and `AGENT`, which this service never accepts).

| Capability | ADMIN | MANAGER | AGENT | Quotation service account |
|---|:-:|:-:|:-:|:-:|
| All `GET` (lookups, aggregates, rates, history, alerts) | ✅ | ✅ | ❌ | ❌ |
| Any write (`POST`/`PUT`/`PATCH`/`DELETE`), `adjust`, `import` | ✅ | ✅ | ❌ | ❌ |
| `GET /rates/resolve` | ✅ | ✅ | ❌ | ✅ |

- **Enforcement:** class-level `@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")` on every controller, so a new endpoint is protected by default. `GET /rates/resolve` alone also accepts the service role below. The gateway only validates that a JWT is valid, not roles, so this check must live in the service.
- **How agents get prices:** the agent calls the (future) quotation service; that service calls `GET /rates/resolve` with its **own** token, obtained via the Keycloak client-credentials flow (a dedicated client whose service account carries a `QUOTATION_SERVICE` realm role). The agent's token is never forwarded to master data, and the quotation service decides what an agent may see (e.g. the quoted total, not cost breakdowns).
- Because AGENT is rejected everywhere, there is no need for a separate `sellingPrice` field in this service. Selling price and markup belong to the quotation service.

## 10. Implementation notes (Spring, aligned with user-service)

- Package layout follows user-service: flat layers under `lk.serenetravels.masterdataservice` — `config/ controller/ dto/ entity/ exception/ repository/ service/ service/impl/ util/`. Every service = interface in `service/` + `impl/`.
- Dependencies in use: `web`, `jdbc`, `postgresql`, `validation`, `security`, `oauth2-resource-server`, `springdoc-openapi-starter-webmvc-ui`, `spring-cloud-starter-consul-discovery`, `actuator`. Still to add for import/export: Apache POI (`poi-ooxml`). The service runs on Java 25 (the skeleton's choice, same as the gateway); user-service is on 17.
- Rate engine = **one generic service** (`RateServiceImpl`) driven by the `util/RateFamilies` registry (table, key columns, value column, unit, nullable-key rules, fan-out key) rather than ten services. It uses `JdbcClient` with named parameters rather than JPA entities: all ten tables share one shape, so generic SQL built from the fixed registry (never from request input) is far smaller than ten entities and repositories. Lookups work the same way (`util/LookupDefinitions`). The aggregates (property, vehicle, …) can use JPA or JDBC when they are built.
- DB: reuses the existing `tour-management` schema as is (no ORM schema generation). Adding Flyway with a baseline taken from it is a sensible next step once the service needs to change the schema.
- Gateway: register `lb://master-data-service` route in `GatewayRouteConfig`, add public-paths for `/master-data-service/swagger-ui/**` and `/master-data-service/v3/api-docs/**`, add `springdoc.swagger-ui.urls[1]` for the aggregated UI, and add the same CORS origin config user-service has.
- `server.forward-headers-strategy=framework` (needed for correct Swagger server URLs behind ngrok/https).

## 11. Implementation status

| Area | State |
|---|---|
| Foundation: security (Keycloak realm roles, ADMIN/MANAGER only), problem+json errors, springdoc, Consul registration, CORS | ✅ built |
| Lookups: bundle (ETag), per-lookup list / create / edit / delete, soft delete for technical visit categories | ✅ built |
| Rate engine: families, list, set (close-and-open, vehicle fan-out), correct, delete pending, adjust (dry run), resolve, property grid save | ✅ built |
| Hotels: list / search / sort, one-hotel aggregate (`include=rates`), create, quick edit, soft delete, room types, child rules, inclusions, dietary support, baby cot | ✅ built |
| Attractions: list / search / sort with current prices, one-attraction aggregate, create, quick edit, delete, tuk-tuk option | ✅ built |
| Vehicles and drivers: list / search / sort, create, quick edit, soft delete, expiry and driver warnings, role rules | ✅ built |
| Guides: list / search / sort / filter by language, create (optionally with languages), quick edit, soft delete, replace languages | ✅ built |
| Restaurants: list / search / filter (dietary, closed or open on a day), create (optionally with days and dietary), rename / move, delete, replace closed days and dietary support | ✅ built |
| Packages: list / search / filter by included site, create (optionally with sites), rename, delete, replace sites; current price shown (priced via `/rates/package`) | ✅ built |
| Promotions: list / search / filter, create, quick edit, soft delete, status and current value shown; value unit follows the type (rate engine reports PERCENT and caps at 100 for percentage promotions) | ✅ built |
| Location distances: list / search / filter, between-two-destinations lookup (either direction), create, quick edit, delete, suspect-row filter | ✅ built |
| Aggregates: articles | ⏳ not built |
| Alerts, import / export | ⏳ not built |
| Rate list `sort` | Not supported on `GET /rates/{family}`: rows are always newest-first (`effective_from desc`) |
| Promotion value unit | Follows `promotion.type`: PERCENT for `PERCENTAGE`, USD for `FIXED_AMOUNT`. `COMPLIMENTARY` is treated as USD for now - what its value means (the worth of the freebie? a quantity?) is not defined anywhere, so confirm before relying on it |

Tests: unit and HTTP tests run on in-memory H2 (PostgreSQL mode). `LivePostgresSmokeTest` is an opt-in check against the real schema (`mvnw test -Dtest=LivePostgresSmokeTest -Dlive.db=true`); it reads real data and writes only to session-local `TEMP` copies of the tables, so it cannot change `tour-management`.

## 12. Resolved decisions and open items

**Resolved**

- Guides: `guideNo` is unique and stored upper-case; `nic` is optional but unique when present (case- and spacing-insensitive); a guide's languages come from the `languages` lookup and are replaced as a whole set. Not enforced yet: any link between a guide's `guideType` and the prefix of its `guideNo` (`N-` / `C-` ...), since the data does not state a rule.
- Fleet rules: a vehicle's `driverId` must be an active person whose role is DRIVER and its `assistantId` an active ASSISTANT; seats may not exceed what the vehicle type carries; registration numbers and NICs are unique (case- and spacing-insensitive); a person cannot change role while assigned to a vehicle in the other slot (409 IN_USE). An active vehicle's `warnings` flag insurance / revenue licence that has expired or expires within 30 days, no driver, and an inactive driver or assistant; the `expiringWithinDays` filter finds vehicles to chase (already-expired ones included). Deactivating a driver does not block - the vehicle just shows `DRIVER_INACTIVE` until reassigned.
- Attraction list rows carry a compact `currentRates` (`rateType`, `rate`, `unit`) so a table can show prices without a second call; the single-attraction response carries the full rate rows. `FIXED_GROUP_PLUS_EXTRA` attractions must have a `fixedGroupSize`; `PER_PERSON` ones must not. An attraction name is unique within its destination.
- Currency: `misc` and `package` are USD; `tuk-tuk` is LKR (with vehicle and transport per-km). Tax is PERCENT. **Guide accommodation is LKR** (the supplied data says "LKR 4500") - the rate family in the code still says USD and needs correcting.
- Rate intervals are half-open `[from, to)`, matching the existing `CHECK (effective_to > effective_from)`. Note that existing seasons use inclusive end dates, so no one should hand-enter `effective_to` as an inclusive last day.
- Restaurant closed days are exchanged as **day names** (`MONDAY` … `SUNDAY`, any letter case accepted, returned Monday-first), matching the business template. They are stored in `restaurant_closed_day.day_of_week` as the database documents it: **0 = Sunday … 6 = Saturday** (an earlier draft of this spec wrongly assumed ISO 1-7). A restaurant name is unique within its destination; deleting a restaurant also removes its closed days and dietary links.
- `attraction.pricing_method` (`PER_PERSON` / `FIXED_GROUP_PLUS_EXTRA`) and `attraction_rate.rate_type` (seven values, see §5.1) are closed lists enforced by the database; the API validates them and publishes them (the rate families endpoint lists the allowed rate types). `vehicle.method` (`INTERNAL` / `CONTRACTED`), `driver.role` (`DRIVER` / `ASSISTANT`) and `guide.guide_type` (`DRIVER` / `CHAUFFEUR_GUIDE` / `NATIONAL_GUIDE`) are closed lists enforced by the database and validated by the API. Introduce closed enums later, once the full value sets are known.
- Rate corrections via `PATCH` rely on `created_by` only in v1. A `rate_audit` table (§5.2) is a recommended follow-up, not part of v1.

- Promotions: `type` is a closed list enforced by the database (`PERCENTAGE`, `FIXED_AMOUNT`, `COMPLIMENTARY`), accepted in any letter case. A promotion runs from `validFrom` up to, **not including**, `validTo` (half-open, like rates); the database requires `validTo > validFrom`. A null `destinationId` / `seasonId` / `minGroupSize` means no limit. `status` is worked out for the requested date: `INACTIVE`, `UPCOMING`, `RUNNING` or `ENDED`. The type cannot change once a value exists (409 `TYPE_LOCKED`). Names are not unique (the database does not require it). Stacking and minimum-spend rules are undecided in the source requirements and are not modelled.
- Location distances: a row is one road option between two destinations (`routeType` `ROAD` / `ROADA` / `ROADB` in the legacy data; the database does not restrict it, so the API accepts any 1-30 letters, digits or underscores, upper-cased). Most pairs are stored in **one direction only** (6,321 rows, only 234 have a reverse row), so "how far is A from B" must use `/location-distances/between`, which looks both ways. New rows must have two different destinations, `distanceKm` above zero (max 5000, one decimal place - the column is `numeric(6,1)`) and an optional `durationMinutes` of 1-2880. The same pair with the same route type may be recorded **once, in either direction** (409 `DUPLICATE`); this is only re-checked when a row's ends or route type change, so the few legacy pairs held both ways stay editable. Hard delete - nothing refers to a distance.
- AGENT has no access to this service; agents see prices only through quotations (D4). `technical_visit_category` is soft-deleted (D5).

**Still open**

0. **Suspect rows in `location_distance`.** 222 rows point a destination at itself and 34 have a distance of zero or less (one is -100). They are loaded as they were; `GET /location-distances?needsReview=true` lists them (`needsReview` is also on every row) and `PATCH` fixes one - but only with a full valid row, so a self-pointing row needs its corrected `toDestinationId` (and, if that pair already has the route type, a different `routeType` or deleting it). 6 pairs held both ways also disagree on the distance. None of this has been changed without a decision.

1. **Legacy rate end dates are inclusive.** All 3,074 hotel rates in the database are `legacy_import` rows from 2018-2024 whose `effective_to` is the last valid day (e.g. 2019-10-31, with the next rate starting 2019-11-01). The rate engine treats `effective_to` as exclusive, so a legacy rate appears to end one day early. Decide one convention and either adjust the engine or convert the legacy rows. Also note that every one of those rates has expired (the latest ended 2024-10-31), so no hotel has a current rate and the hotel list's `fromRate` is empty until current rates are entered.
2. **Service-account role name and flow** for `GET /rates/resolve`. `QUOTATION_SERVICE` via Keycloak client-credentials is proposed in §9; it needs a Keycloak client and role created when the quotation service is built.
