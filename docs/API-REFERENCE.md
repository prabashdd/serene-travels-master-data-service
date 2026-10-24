# Master Data Service - API reference

> **This file is generated.** Do not edit it by hand: the next test run would fail. It is built from the controllers (endpoints, parameters, descriptions), a scripted scenario that calls every endpoint for real (the request and response samples) and `docs/api-reference/intro.md` (the conventions at the top). To change it, change the code or the intro, then regenerate - see `docs/api-reference/README.md`.

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

## Contents

- [Common error responses](#common-error-responses)
- [Lookups](#lookups) - 5 endpoints
- [Rates](#rates) - 8 endpoints
- [Properties](#properties) - 12 endpoints
- [Attractions](#attractions) - 7 endpoints
- [Vehicles](#vehicles) - 5 endpoints
- [Drivers](#drivers) - 5 endpoints
- [Guides](#guides) - 6 endpoints
- [Restaurants](#restaurants) - 7 endpoints
- [Packages](#packages) - 6 endpoints
- [Promotions](#promotions) - 5 endpoints
- [Distances & Articles](#distances-articles) - 11 endpoints
- [Operations](#operations) - 2 endpoints

## Common error responses

Every error is `application/problem+json` with a stable `code` (see the intro). Real examples:

**No token**

```http
GET /api/v1/master-data/lookups
```

`401 Unauthorized`

```json
{
  "type": "about:blank",
  "title": "Unauthorized",
  "status": 401,
  "code": "UNAUTHORIZED",
  "detail": "A valid access token is required"
}
```

**A token without the ADMIN or MANAGER role (agents are never allowed)**

```http
GET /api/v1/master-data/lookups
```

`403 Forbidden`

```json
{
  "type": "about:blank",
  "title": "Forbidden",
  "status": 403,
  "code": "FORBIDDEN",
  "detail": "This service is restricted to ADMIN and MANAGER users"
}
```

**Validation failed: every problem is listed against its field**

```http
POST /api/v1/master-data/properties
Content-Type: application/json

{}
```

`400 Bad Request`

```json
{
  "detail": "Validation failed",
  "instance": "/api/v1/master-data/properties",
  "status": 400,
  "title": "Bad Request",
  "code": "VALIDATION_ERROR",
  "fieldErrors": {
    "code": "is required",
    "destinationId": "is required",
    "name": "is required"
  }
}
```

**Malformed JSON**

```http
POST /api/v1/master-data/properties
Content-Type: application/json

{not json
```

`400 Bad Request`

```json
{
  "detail": "Request body or parameter is malformed",
  "instance": "/api/v1/master-data/properties",
  "status": 400,
  "title": "Bad Request",
  "code": "VALIDATION_ERROR"
}
```

**Not found**

```http
GET /api/v1/master-data/properties/999999
```

`404 Not Found`

```json
{
  "detail": "No property found with id '999999'",
  "instance": "/api/v1/master-data/properties/999999",
  "status": 404,
  "title": "Not Found",
  "code": "NOT_FOUND"
}
```

**Duplicate**

```http
POST /api/v1/master-data/properties
Content-Type: application/json

{
  "code": "LGH-SGR",
  "name": "Another Lotus",
  "destinationId": 1
}
```

`409 Conflict`

```json
{
  "detail": "Property code 'LGH-SGR' is already used by another hotel",
  "instance": "/api/v1/master-data/properties",
  "status": 409,
  "title": "Conflict",
  "code": "DUPLICATE"
}
```

---

## Lookups

Cold reference data. Fetch the bundle once and cache it.

| Method | Path | What it does |
|---|---|---|
| `GET` | [`/lookups`](#get-lookups) | All reference lookups in one response (bootstrap for dropdowns) |
| `GET` | [`/lookups/{name}`](#get-lookups-name) | One lookup list |
| `POST` | [`/lookups/{name}`](#post-lookups-name) | Add a lookup item |
| `PATCH` | [`/lookups/{name}/{id}`](#patch-lookups-name-id) | Edit a lookup item (send only the changed fields) |
| `DELETE` | [`/lookups/{name}/{id}`](#delete-lookups-name-id) | Delete a lookup item |

<a id="get-lookups"></a>

### `GET /lookups` - All reference lookups in one response (bootstrap for dropdowns)

Returns an ETag and Cache-Control: no-cache, so the FE revalidates with If-None-Match and gets a cheap 304 until something actually changes.

**Who can call it:** ADMIN, MANAGER

| Parameter | In | Type | Required | Default | Notes |
|---|---|---|---|---|---|
| `includeInactive` | query | boolean | no | `false` | Include soft-deleted items |

**Example - Fetch every lookup in one call**

```http
GET /api/v1/master-data/lookups
```

`200 OK` - `ETag: "7bcefc5c"`

```json
{
  "destinations": [
    {
      "id": 4,
      "name": "Colombo",
      "region": "Western Province",
      "country": "Sri Lanka"
    },
    {
      "id": 3,
      "name": "Galle",
      "region": "Southern Province",
      "country": "Sri Lanka"
    },
    {
      "id": 2,
      "name": "Kandy",
      "region": "Central Province",
      "country": "Sri Lanka"
    },
    {
      "id": 1,
      "name": "Sigiriya",
      "region": "Cultural Triangle",
      "country": "Sri Lanka"
    },
    {
      "id": 5,
      "name": "Yala",
      "region": "Southern Province",
      "country": "Sri Lanka"
    }
  ],
  "nationalities": [
    {
      "id": 1,
      "name": "India",
      "isoCode": "IN"
    },
    {
      "id": 2,
      "name": "United Kingdom",
      "isoCode": "GB"
    }
  ],
  "languages": [
    {
      "id": 1,
      "name": "English"
    },
    {
      "id": 2,
      "name": "German"
    }
  ],
  "seasons": [
    {
      "id": 1,
      "name": "Summer",
      "startDate": "2026-04-01",
      "endDate": "2026-10-31"
    },
    {
      "id": 2,
      "name": "Winter",
      "startDate": "2026-11-01",
      "endDate": "2027-03-31"
    }
  ],
  "vehicleTypes": [
    {
      "id": 1,
      "name": "Car",
      "minCapacity": 1,
      "maxCapacity": 2
    },
    {
      "id": 2,
      "name": "Van",
      "minCapacity": 3,
      "maxCapacity": 9
    }
  ],
  "dietaryCategories": [
    {
      "id": 2,
      "name": "Halal"
    },
    {
      "id": 1,
      "name": "Vegetarian"
    }
  ],
  "mealPlans": [
    {
      "id": 1,
      "code": "BB",
      "name": "Bed and breakfast"
    }
  ],
  "hotelInclusions": [
    {
      "id": 1,
      "name": "Airport transfer",
      "description": "One-way, up to 3 guests"
    },
    {
      "id": 2,
      "name": "Welcome drink",
      "description": null
    }
  ],
  "miscCostTypes": [
    {
      "id": 1,
      "name": "Camera fee"
    }
  ],
  "taxRates": [
    {
      "id": 1,
      "name": "VAT"
    }
  ],
  "technicalVisitCategories": [
    {
      "id": 1,
      "name": "Tea factory",
      "active": true
    }
  ]
}
```

**Example - Revalidate the cached bundle (nothing changed)**

```http
GET /api/v1/master-data/lookups
If-None-Match: "7bcefc5c"
```

`304 Not Modified` - `ETag: "7bcefc5c"`

<a id="get-lookups-name"></a>

### `GET /lookups/{name}` - One lookup list

**Who can call it:** ADMIN, MANAGER

| Parameter | In | Type | Required | Default | Notes |
|---|---|---|---|---|---|
| `name` | path | string | yes |  |  |
| `includeInactive` | query | boolean | no | `false` | Include soft-deleted items |

**Example - Fetch one lookup list**

```http
GET /api/v1/master-data/lookups/seasons
```

`200 OK`

```json
[
  {
    "id": 1,
    "name": "Summer",
    "startDate": "2026-04-01",
    "endDate": "2026-10-31"
  },
  {
    "id": 2,
    "name": "Winter",
    "startDate": "2026-11-01",
    "endDate": "2027-03-31"
  }
]
```

<a id="post-lookups-name"></a>

### `POST /lookups/{name}` - Add a lookup item

**Who can call it:** ADMIN, MANAGER

| Parameter | In | Type | Required | Default | Notes |
|---|---|---|---|---|---|
| `name` | path | string | yes |  |  |

**Request body** (`application/json`): a JSON object - the fields are described above and shown in the examples.

**Example - Add a destination**

```http
POST /api/v1/master-data/lookups/destinations
Content-Type: application/json

{
  "name": "Sigiriya",
  "region": "Cultural Triangle",
  "country": "Sri Lanka"
}
```

`201 Created` - `Location: /api/v1/master-data/lookups/destinations/1`

```json
{
  "id": 1,
  "name": "Sigiriya",
  "region": "Cultural Triangle",
  "country": "Sri Lanka"
}
```

**Example - Add a season**

```http
POST /api/v1/master-data/lookups/seasons
Content-Type: application/json

{
  "name": "Summer",
  "startDate": "2026-04-01",
  "endDate": "2026-10-31"
}
```

`201 Created` - `Location: /api/v1/master-data/lookups/seasons/1`

```json
{
  "id": 1,
  "name": "Summer",
  "startDate": "2026-04-01",
  "endDate": "2026-10-31"
}
```

<a id="patch-lookups-name-id"></a>

### `PATCH /lookups/{name}/{id}` - Edit a lookup item (send only the changed fields)

**Who can call it:** ADMIN, MANAGER

| Parameter | In | Type | Required | Default | Notes |
|---|---|---|---|---|---|
| `name` | path | string | yes |  |  |
| `id` | path | integer | yes |  | The record's id |

**Request body** (`application/json`): a JSON object - the fields are described above and shown in the examples.

**Example - Edit a lookup item (only the changed fields)**

```http
PATCH /api/v1/master-data/lookups/destinations/1
Content-Type: application/json

{
  "region": "North Central Province"
}
```

`200 OK`

```json
{
  "id": 1,
  "name": "Sigiriya",
  "region": "North Central Province",
  "country": "Sri Lanka"
}
```

<a id="delete-lookups-name-id"></a>

### `DELETE /lookups/{name}/{id}` - Delete a lookup item

409 IN_USE if other master data references it. technical-visit-categories is soft-deleted (active=false) instead.

**Who can call it:** ADMIN, MANAGER

| Parameter | In | Type | Required | Default | Notes |
|---|---|---|---|---|---|
| `name` | path | string | yes |  |  |
| `id` | path | integer | yes |  | The record's id |

**Example - Delete an unused lookup item**

```http
DELETE /api/v1/master-data/lookups/destinations/6
```

`204 No Content`

**Example - Rejected: the item is still used by other master data**

```http
DELETE /api/v1/master-data/lookups/destinations/1
```

`409 Conflict`

```json
{
  "detail": "This destination is still referenced by other master data and cannot be deleted",
  "instance": "/api/v1/master-data/lookups/destinations/1",
  "status": 409,
  "title": "Conflict",
  "code": "IN_USE"
}
```

---

## Rates

One engine for all price tables. A change closes the open row and inserts a new one; history is never rewritten.

| Method | Path | What it does |
|---|---|---|
| `PUT` | [`/properties/{id}/rates`](#put-properties-id-rates) | Save a property's rate grid atomically |
| `GET` | [`/rates/families`](#get-rates-families) | Rate family registry (keys, unit, nullable-key meaning) - drives generic rate UIs |
| `GET` | [`/rates/resolve`](#get-rates-resolve) | Resolve the single applicable price (used by the quotation service) |
| `GET` | [`/rates/{family}`](#get-rates-family) | Rates for a subset of keys |
| `PUT` | [`/rates/{family}`](#put-rates-family) | Set a rate (closes the open row, inserts a new one) |
| `POST` | [`/rates/{family}/adjust`](#post-rates-family-adjust) | Bulk percent/amount change with preview |
| `PATCH` | [`/rates/{family}/{id}`](#patch-rates-family-id) | Correct a row in place (typo fix) |
| `DELETE` | [`/rates/{family}/{id}`](#delete-rates-family-id) | Delete a pending future-dated row (history is never deleted) |

<a id="put-properties-id-rates"></a>

### `PUT /properties/{id}/rates` - Save a property's rate grid atomically

Each item follows the PUT /rates/property rule; the whole request is all-or-nothing.

**Who can call it:** ADMIN, MANAGER

| Parameter | In | Type | Required | Default | Notes |
|---|---|---|---|---|---|
| `id` | path | integer | yes |  | The record's id |

**Request body** (`application/json`): a JSON array of object.

| Field | Type | Required |
|---|---|---|
| `[].keys` | object | yes |
| `[].rate` | number | yes |
| `[].effectiveFrom` | string (date) | no |

<details><summary>Response fields</summary>

| Field | Type |
|---|---|
| `[].id` | integer |
| `[].family` | string |
| `[].keys` | object |
| `[].labels` | object |
| `[].rate` | number |
| `[].unit` | enum: USD / LKR / PERCENT |
| `[].effectiveFrom` | string (date) |
| `[].effectiveTo` | string (date) |
| `[].isCurrent` | boolean |
| `[].createdAt` | string (date-time) |
| `[].createdBy` | string |

</details>

**Example - Save a hotel's whole rate grid atomically**

```http
PUT /api/v1/master-data/properties/1/rates
Content-Type: application/json

[
  {
    "keys": {
      "roomTypeId": 1,
      "seasonId": 1,
      "occupancy": 2
    },
    "rate": 85
  },
  {
    "keys": {
      "roomTypeId": 1,
      "seasonId": 1,
      "occupancy": 1
    },
    "rate": 70
  },
  {
    "keys": {
      "roomTypeId": 1,
      "seasonId": 2,
      "occupancy": 2,
      "nationalityId": 1
    },
    "rate": 110
  }
]
```

`200 OK`

```json
[
  {
    "id": 1,
    "family": "property",
    "keys": {
      "propertyId": 1,
      "roomTypeId": 1,
      "seasonId": 1,
      "occupancy": 2,
      "nationalityId": null
    },
    "labels": {
      "propertyId": "Lotus Garden Hotel",
      "roomTypeId": "Deluxe Double",
      "seasonId": "Summer",
      "nationalityId": null
    },
    "rate": 85.00,
    "unit": "USD",
    "effectiveFrom": "2026-10-18",
    "effectiveTo": null,
    "isCurrent": true,
    "createdAt": "2026-10-18T03:00:00Z",
    "createdBy": "nimali.manager"
  },
  {
    "id": 2,
    "family": "property",
    "keys": {
      "propertyId": 1,
      "roomTypeId": 1,
      "seasonId": 1,
      "occupancy": 1,
      "nationalityId": null
    },
    "labels": {
      "propertyId": "Lotus Garden Hotel",
      "roomTypeId": "Deluxe Double",
      "seasonId": "Summer",
      "nationalityId": null
    },
    "rate": 70.00,
    "unit": "USD",
    "effectiveFrom": "2026-10-18",
    "effectiveTo": null,
    "isCurrent": true,
    "createdAt": "2026-10-18T03:00:00Z",
    "createdBy": "nimali.manager"
  },
  {
    "id": 3,
    "family": "property",
    "keys": {
      "propertyId": 1,
      "roomTypeId": 1,
      "seasonId": 2,
      "occupancy": 2,
      "nationalityId": 1
    },
    "labels": {
      "propertyId": "Lotus Garden Hotel",
      "roomTypeId": "Deluxe Double",
      "seasonId": "Winter",
      "nationalityId": "India"
    },
    "rate": 110.00,
    "unit": "USD",
    "effectiveFrom": "2026-10-18",
    "effectiveTo": null,
    "isCurrent": true,
    "createdAt": "2026-10-18T03:00:00Z",
    "createdBy": "nimali.manager"
  }
]
```

<a id="get-rates-families"></a>

### `GET /rates/families` - Rate family registry (keys, unit, nullable-key meaning) - drives generic rate UIs

**Who can call it:** ADMIN, MANAGER

<details><summary>Response fields</summary>

| Field | Type |
|---|---|
| `[].name` | string |
| `[].label` | string |
| `[].unit` | enum: USD / LKR / PERCENT |
| `[].perKm` | boolean |
| `[].keys` | array of object |
| `[].keys[].name` | string |
| `[].keys[].label` | string |
| `[].keys[].lookup` | string |
| `[].keys[].required` | boolean |
| `[].keys[].nullMeansAll` | boolean |
| `[].keys[].allowedValues` | array of string |

</details>

**Example - Which rate families exist and what keys they take**

```http
GET /api/v1/master-data/rates/families
```

`200 OK`

```json
[
  {
    "name": "property",
    "label": "Property room rates",
    "unit": "USD",
    "perKm": false,
    "keys": [
      {
        "name": "propertyId",
        "label": "Property",
        "lookup": "properties",
        "required": true,
        "nullMeansAll": false,
        "allowedValues": null
      },
      {
        "name": "roomTypeId",
        "label": "Room type",
        "lookup": null,
        "required": true,
        "nullMeansAll": false,
        "allowedValues": null
      },
      {
        "name": "seasonId",
        "label": "Season",
        "lookup": "seasons",
        "required": true,
        "nullMeansAll": false,
        "allowedValues": null
      },
      {
        "name": "occupancy",
        "label": "Occupancy",
        "lookup": null,
        "required": true,
        "nullMeansAll": false,
        "allowedValues": null
      },
      {
        "name": "nationalityId",
        "label": "Nationality",
        "lookup": "nationalities",
        "required": false,
        "nullMeansAll": true,
        "allowedValues": null
      }
    ]
  },
  {
    "name": "vehicle",
    "label": "Vehicle rates (per km)",
    "unit": "LKR",
    "perKm": true,
    "keys": [
      {
        "name": "vehicleTypeId",
        "label": "Vehicle type",
        "lookup": "vehicle-types",
        "required": true,
        "nullMeansAll": false,
        "allowedValues": null
      },
      {
        "name": "seasonId",
        "label": "Season",
        "lookup": "seasons",
        "required": false,
        "nullMeansAll": false,
        "allowedValues": null
      }
    ]
  },
  {
    "name": "attraction",
    "label": "Attraction rates",
    "unit": "USD",
    "perKm": false,
    "keys": [
      {
        "name": "attractionId",
        "label": "Attraction",
        "lookup": "attractions",
        "required": true,
        "nullMeansAll": false,
        "allowedValues": null
      },
      {
        "name": "rateType",
        "label": "Rate type",
        "lookup": null,
        "required": true,
        "nullMeansAll": false,
        "allowedValues": [
          "PER_PERSON",
          "FIXED_GROUP_BASE",
          "EXTRA_PERSON",
          "GUEST_ENTRY_FEE",
          "GUIDE_ENTRY_FEE",
          "CHILD_DISCOUNT",
          "JEEP_RATE"
        ]
      }
    ]
  },
  {
    "name": "guide-accommodation",
    "label": "Guide accommodation rates",
    "unit": "LKR",
    "perKm": false,
    "keys": [
      {
        "name": "seasonId",
        "label": "Season",
        "lookup": "seasons",
        "required": true,
        "nullMeansAll": false,
        "allowedValues": null
      },
      {
        "name": "destinationId",
        "label": "Destination",
        "lookup": "destinations",
        "required": false,
        "nullMeansAll": true,
        "allowedValues": null
      }
    ]
  },
  {
    "name": "misc",
    "label": "Miscellaneous cost rates",
    "unit": "USD",
    "perKm": false,
    "keys": [
      {
        "name": "miscCostTypeId",
        "label": "Cost type",
        "lookup": "misc-cost-types",
        "required": true,
        "nullMeansAll": false,
        "allowedValues": null
      },
      {
        "name": "nationalityId",
        "label": "Nationality",
        "lookup": "nationalities",
        "required": false,
        "nullMeansAll": true,
        "allowedValues": null
      }
    ]
  },
  {
    "name": "tuk-tuk",
    "label": "Tuk-tuk fares",
    "unit": "LKR",
    "perKm": false,
    "keys": [
      {
        "name": "tukTukLocationId",
        "label": "Tuk-tuk location",
        "lookup": null,
        "required": true,
        "nullMeansAll": false,
        "allowedValues": null
      }
    ]
  },
  {
    "name": "package",
    "label": "Site tour package rates",
    "unit": "USD",
    "perKm": false,
    "keys": [
      {
        "name": "packageId",
        "label": "Package",
        "lookup": "packages",
        "required": true,
        "nullMeansAll": false,
        "allowedValues": null
      }
    ]
  },
  {
    "name": "tax",
    "label": "Tax rates",
    "unit": "PERCENT",
    "perKm": false,
    "keys": [
      {
        "name": "taxRateId",
        "label": "Tax",
        "lookup": "tax-rates",
        "required": true,
        "nullMeansAll": false,
        "allowedValues": null
      }
    ]
  },
  {
    "name": "promotion",
    "label": "Promotion values (USD, or % for a percentage promotion)",
    "unit": "USD",
    "perKm": false,
    "keys": [
      {
        "name": "promotionId",
        "label": "Promotion",
        "lookup": "promotions",
        "required": true,
        "nullMeansAll": false,
        "allowedValues": null
      }
    ]
  },
  {
    "name": "transport",
    "label": "Transport rate (per km)",
    "unit": "LKR",
    "perKm": true,
    "keys": []
  }
]
```

<a id="get-rates-resolve"></a>

### `GET /rates/resolve` - Resolve the single applicable price (used by the quotation service)

Pass the family's keys as keys[name]=value, e.g. keys[propertyId]=42&keys[roomTypeId]=1&keys[seasonId]=1&keys[occupancy]=2&keys[nationalityId]=9. Falls back to the 'applies to all' row; 404 NO_RATE if neither exists.

**Who can call it:** ADMIN, MANAGER, QUOTATION_SERVICE

| Parameter | In | Type | Required | Default | Notes |
|---|---|---|---|---|---|
| `family` | query | string | yes |  |  |
| `at` | query | string (date) | no |  | Evaluate as of this date (default today) |

<details><summary>Response fields</summary>

| Field | Type |
|---|---|
| `rate` | number |
| `unit` | enum: USD / LKR / PERCENT |
| `matched` | string |
| `row` | object |
| `row.id` | integer |
| `row.family` | string |
| `row.keys` | object |
| `row.labels` | object |
| `row.rate` | number |
| `row.unit` | enum: USD / LKR / PERCENT |
| `row.effectiveFrom` | string (date) |
| `row.effectiveTo` | string (date) |
| `row.isCurrent` | boolean |
| `row.createdAt` | string (date-time) |
| `row.createdBy` | string |

</details>

**Example - Resolve one price (falls back to the 'all nationalities' row)**

```http
GET /api/v1/master-data/rates/resolve?family=property&keys[propertyId]=1&keys[roomTypeId]=1&keys[seasonId]=1&keys[occupancy]=2&keys[nationalityId]=2
```

`200 OK`

```json
{
  "rate": 85.00,
  "unit": "USD",
  "matched": "FALLBACK_ALL_NATIONALITIES",
  "row": {
    "id": 1,
    "family": "property",
    "keys": {
      "propertyId": 1,
      "roomTypeId": 1,
      "seasonId": 1,
      "occupancy": 2,
      "nationalityId": null
    },
    "labels": {
      "propertyId": "Lotus Garden Hotel",
      "roomTypeId": "Deluxe Double",
      "seasonId": "Summer",
      "nationalityId": null
    },
    "rate": 85.00,
    "unit": "USD",
    "effectiveFrom": "2026-10-18",
    "effectiveTo": null,
    "isCurrent": true,
    "createdAt": "2026-10-18T03:00:00Z",
    "createdBy": "nimali.manager"
  }
}
```

**Example - Nothing priced for that combination**

```http
GET /api/v1/master-data/rates/resolve?family=vehicle&keys[vehicleTypeId]=1&keys[seasonId]=1
```

`404 Not Found`

```json
{
  "detail": "No vehicle rate applies on 2026-10-18 for {vehicleTypeId=1, seasonId=1}",
  "instance": "/api/v1/master-data/rates/resolve",
  "status": 404,
  "title": "Not Found",
  "code": "NO_RATE"
}
```

<a id="get-rates-family"></a>

### `GET /rates/{family}` - Rates for a subset of keys

Filter with filter[propertyId]=42&filter[seasonId]=1 (filter[nationalityId]=null selects the 'all nationalities' row). history=true includes closed rows (the audit view).

**Who can call it:** ADMIN, MANAGER

| Parameter | In | Type | Required | Default | Notes |
|---|---|---|---|---|---|
| `family` | path | string | yes |  |  |
| `at` | query | string (date) | no |  | Evaluate 'current' as of this date (default today) |
| `history` | query | boolean | no | `false` |  |
| `page` | query | integer | no | `0` | Page number, starting at 0 |
| `size` | query | integer | no | `20` | Rows per page (1 to 200) |

<details><summary>Response fields</summary>

| Field | Type |
|---|---|
| `content` | array of object |
| `content[].id` | integer |
| `content[].family` | string |
| `content[].keys` | object |
| `content[].labels` | object |
| `content[].rate` | number |
| `content[].unit` | enum: USD / LKR / PERCENT |
| `content[].effectiveFrom` | string (date) |
| `content[].effectiveTo` | string (date) |
| `content[].isCurrent` | boolean |
| `content[].createdAt` | string (date-time) |
| `content[].createdBy` | string |
| `totalElements` | integer |
| `totalPages` | integer |
| `number` | integer |
| `size` | integer |
| `first` | boolean |
| `last` | boolean |
| `empty` | boolean |

</details>

**Example - Rate history for one attraction (history=true includes closed rows)**

```http
GET /api/v1/master-data/rates/attraction?filter[attractionId]=1&history=true
```

`200 OK`

```json
{
  "content": [
    {
      "id": 2,
      "family": "attraction",
      "keys": {
        "attractionId": 1,
        "rateType": "PER_PERSON"
      },
      "labels": {
        "attractionId": "Sigiriya Rock Fortress (Lion Rock)"
      },
      "rate": 40.00,
      "unit": "USD",
      "effectiveFrom": "2026-12-01",
      "effectiveTo": null,
      "isCurrent": false,
      "createdAt": "2026-10-18T03:00:00Z",
      "createdBy": "nimali.manager"
    },
    {
      "id": 3,
      "family": "attraction",
      "keys": {
        "attractionId": 1,
        "rateType": "CHILD_DISCOUNT"
      },
      "labels": {
        "attractionId": "Sigiriya Rock Fortress (Lion Rock)"
      },
      "rate": 17.50,
      "unit": "USD",
      "effectiveFrom": "2026-10-18",
      "effectiveTo": null,
      "isCurrent": true,
      "createdAt": "2026-10-18T03:00:00Z",
      "createdBy": "nimali.manager"
    },
    {
      "id": 1,
      "family": "attraction",
      "keys": {
        "attractionId": 1,
        "rateType": "PER_PERSON"
      },
      "labels": {
        "attractionId": "Sigiriya Rock Fortress (Lion Rock)"
      },
      "rate": 35.00,
      "unit": "USD",
      "effectiveFrom": "2026-10-18",
      "effectiveTo": "2026-12-01",
      "isCurrent": true,
      "createdAt": "2026-10-18T03:00:00Z",
      "createdBy": "nimali.manager"
    }
  ],
  "totalElements": 3,
  "totalPages": 1,
  "number": 0,
  "size": 20,
  "first": true,
  "last": true,
  "empty": false
}
```

**Example - Current rates of one hotel in one season**

```http
GET /api/v1/master-data/rates/property?filter[propertyId]=1&filter[seasonId]=1
```

`200 OK`

```json
{
  "content": [
    {
      "id": 2,
      "family": "property",
      "keys": {
        "propertyId": 1,
        "roomTypeId": 1,
        "seasonId": 1,
        "occupancy": 1,
        "nationalityId": null
      },
      "labels": {
        "propertyId": "Lotus Garden Hotel",
        "roomTypeId": "Deluxe Double",
        "seasonId": "Summer",
        "nationalityId": null
      },
      "rate": 70.00,
      "unit": "USD",
      "effectiveFrom": "2026-10-18",
      "effectiveTo": null,
      "isCurrent": true,
      "createdAt": "2026-10-18T03:00:00Z",
      "createdBy": "nimali.manager"
    },
    {
      "id": 1,
      "family": "property",
      "keys": {
        "propertyId": 1,
        "roomTypeId": 1,
        "seasonId": 1,
        "occupancy": 2,
        "nationalityId": null
      },
      "labels": {
        "propertyId": "Lotus Garden Hotel",
        "roomTypeId": "Deluxe Double",
        "seasonId": "Summer",
        "nationalityId": null
      },
      "rate": 85.00,
      "unit": "USD",
      "effectiveFrom": "2026-10-18",
      "effectiveTo": null,
      "isCurrent": true,
      "createdAt": "2026-10-18T03:00:00Z",
      "createdBy": "nimali.manager"
    }
  ],
  "totalElements": 2,
  "totalPages": 1,
  "number": 0,
  "size": 20,
  "first": true,
  "last": true,
  "empty": false
}
```

<a id="put-rates-family"></a>

### `PUT /rates/{family}` - Set a rate (closes the open row, inserts a new one)

effectiveFrom defaults to today. 409 RATE_STARTS_BEFORE_CURRENT if it is not after the current row's start; 409 FUTURE_RATE_EXISTS if a pending future row exists. For the vehicle family, omit keys.seasonId to write every season in one transaction.

**Who can call it:** ADMIN, MANAGER

| Parameter | In | Type | Required | Default | Notes |
|---|---|---|---|---|---|
| `family` | path | string | yes |  |  |

**Request body** (`application/json`): object.

| Field | Type | Required |
|---|---|---|
| `keys` | object | yes |
| `rate` | number | yes |
| `effectiveFrom` | string (date) | no |

<details><summary>Response fields</summary>

| Field | Type |
|---|---|
| `[].id` | integer |
| `[].family` | string |
| `[].keys` | object |
| `[].labels` | object |
| `[].rate` | number |
| `[].unit` | enum: USD / LKR / PERCENT |
| `[].effectiveFrom` | string (date) |
| `[].effectiveTo` | string (date) |
| `[].isCurrent` | boolean |
| `[].createdAt` | string (date-time) |
| `[].createdBy` | string |

</details>

**Example - Set a rate for every season at once (the season key is left out)**

```http
PUT /api/v1/master-data/rates/vehicle
Content-Type: application/json

{
  "keys": {
    "vehicleTypeId": 2
  },
  "rate": 95
}
```

`200 OK`

```json
[
  {
    "id": 1,
    "family": "vehicle",
    "keys": {
      "vehicleTypeId": 2,
      "seasonId": 1
    },
    "labels": {
      "vehicleTypeId": "Van",
      "seasonId": "Summer"
    },
    "rate": 95.00,
    "unit": "LKR",
    "effectiveFrom": "2026-10-18",
    "effectiveTo": null,
    "isCurrent": true,
    "createdAt": "2026-10-18T03:00:00Z",
    "createdBy": "nimali.manager"
  },
  {
    "id": 2,
    "family": "vehicle",
    "keys": {
      "vehicleTypeId": 2,
      "seasonId": 2
    },
    "labels": {
      "vehicleTypeId": "Van",
      "seasonId": "Winter"
    },
    "rate": 95.00,
    "unit": "LKR",
    "effectiveFrom": "2026-10-18",
    "effectiveTo": null,
    "isCurrent": true,
    "createdAt": "2026-10-18T03:00:00Z",
    "createdBy": "nimali.manager"
  }
]
```

**Example - Set a rate (starts today unless effectiveFrom is given)**

```http
PUT /api/v1/master-data/rates/attraction
Content-Type: application/json

{
  "keys": {
    "attractionId": 1,
    "rateType": "PER_PERSON"
  },
  "rate": 35
}
```

`200 OK`

```json
[
  {
    "id": 1,
    "family": "attraction",
    "keys": {
      "attractionId": 1,
      "rateType": "PER_PERSON"
    },
    "labels": {
      "attractionId": "Sigiriya Rock Fortress (Lion Rock)"
    },
    "rate": 35.00,
    "unit": "USD",
    "effectiveFrom": "2026-10-18",
    "effectiveTo": null,
    "isCurrent": true,
    "createdAt": "2026-10-18T03:00:00Z",
    "createdBy": "nimali.manager"
  }
]
```

**Example - Rejected: a new rate must start after the current one started**

```http
PUT /api/v1/master-data/rates/attraction
Content-Type: application/json

{
  "keys": {
    "attractionId": 1,
    "rateType": "PER_PERSON"
  },
  "rate": 36
}
```

`409 Conflict`

```json
{
  "detail": "The current rate started on 2026-10-18; effectiveFrom must be later (use PATCH to correct a mistake)",
  "instance": "/api/v1/master-data/rates/attraction",
  "status": 409,
  "title": "Conflict",
  "code": "RATE_STARTS_BEFORE_CURRENT"
}
```

**Example - Rejected: a rate cannot be negative**

```http
PUT /api/v1/master-data/rates/attraction
Content-Type: application/json

{
  "keys": {
    "attractionId": 1,
    "rateType": "PER_PERSON"
  },
  "rate": -5
}
```

`400 Bad Request`

```json
{
  "detail": "Validation failed",
  "instance": "/api/v1/master-data/rates/attraction",
  "status": 400,
  "title": "Bad Request",
  "code": "VALIDATION_ERROR",
  "fieldErrors": {
    "rate": "must not be negative"
  }
}
```

**Example - Schedule a future price change**

```http
PUT /api/v1/master-data/rates/attraction
Content-Type: application/json

{
  "keys": {
    "attractionId": 1,
    "rateType": "PER_PERSON"
  },
  "rate": 40,
  "effectiveFrom": "2026-12-01"
}
```

`200 OK`

```json
[
  {
    "id": 2,
    "family": "attraction",
    "keys": {
      "attractionId": 1,
      "rateType": "PER_PERSON"
    },
    "labels": {
      "attractionId": "Sigiriya Rock Fortress (Lion Rock)"
    },
    "rate": 40.00,
    "unit": "USD",
    "effectiveFrom": "2026-12-01",
    "effectiveTo": null,
    "isCurrent": false,
    "createdAt": "2026-10-18T03:00:00Z",
    "createdBy": "nimali.manager"
  }
]
```

**Example - Set a tuk-tuk fare (LKR)**

```http
PUT /api/v1/master-data/rates/tuk-tuk
Content-Type: application/json

{
  "keys": {
    "tukTukLocationId": 1
  },
  "rate": 1500
}
```

`200 OK`

```json
[
  {
    "id": 1,
    "family": "tuk-tuk",
    "keys": {
      "tukTukLocationId": 1
    },
    "labels": {
      "tukTukLocationId": "Sigiriya Rock Fortress (Lion Rock)"
    },
    "rate": 1500.00,
    "unit": "LKR",
    "effectiveFrom": "2026-10-18",
    "effectiveTo": null,
    "isCurrent": true,
    "createdAt": "2026-10-18T03:00:00Z",
    "createdBy": "nimali.manager"
  }
]
```

**Example - Price a package**

```http
PUT /api/v1/master-data/rates/package
Content-Type: application/json

{
  "keys": {
    "packageId": 1
  },
  "rate": 120
}
```

`200 OK`

```json
[
  {
    "id": 1,
    "family": "package",
    "keys": {
      "packageId": 1
    },
    "labels": {
      "packageId": "Cultural Triangle Two-Rock Day Tour"
    },
    "rate": 120.00,
    "unit": "USD",
    "effectiveFrom": "2026-10-18",
    "effectiveTo": null,
    "isCurrent": true,
    "createdAt": "2026-10-18T03:00:00Z",
    "createdBy": "nimali.manager"
  }
]
```

**Example - Set a promotion's value (a percentage here, because of its type)**

```http
PUT /api/v1/master-data/rates/promotion
Content-Type: application/json

{
  "keys": {
    "promotionId": 1
  },
  "rate": 10
}
```

`200 OK`

```json
[
  {
    "id": 1,
    "family": "promotion",
    "keys": {
      "promotionId": 1
    },
    "labels": {
      "promotionId": "Early Bird 2027"
    },
    "rate": 10.00,
    "unit": "PERCENT",
    "effectiveFrom": "2026-10-18",
    "effectiveTo": null,
    "isCurrent": true,
    "createdAt": "2026-10-18T03:00:00Z",
    "createdBy": "nimali.manager"
  }
]
```

<a id="post-rates-family-adjust"></a>

### `POST /rates/{family}/adjust` - Bulk percent/amount change with preview

dryRun=true returns old -> new for every affected row and writes nothing; dryRun=false applies it atomically through the same rule as PUT.

**Who can call it:** ADMIN, MANAGER

| Parameter | In | Type | Required | Default | Notes |
|---|---|---|---|---|---|
| `family` | path | string | yes |  |  |

**Request body** (`application/json`): object.

| Field | Type | Required |
|---|---|---|
| `filter` | object | yes |
| `mode` | enum: PERCENT / AMOUNT | yes |
| `value` | number | yes |
| `rounding` | enum: NEAREST_0_01 / NEAREST_1 | no |
| `effectiveFrom` | string (date) | no |
| `dryRun` | boolean | yes |

<details><summary>Response fields</summary>

| Field | Type |
|---|---|
| `[].keys` | object |
| `[].oldRate` | number |
| `[].newRate` | number |
| `[].unit` | enum: USD / LKR / PERCENT |

</details>

**Example - Preview a bulk +8% change (dry run writes nothing)**

```http
POST /api/v1/master-data/rates/vehicle/adjust
Content-Type: application/json

{
  "filter": {
    "vehicleTypeId": 2
  },
  "mode": "PERCENT",
  "value": 8,
  "rounding": "NEAREST_1",
  "effectiveFrom": "2026-11-01",
  "dryRun": true
}
```

`200 OK`

```json
[
  {
    "keys": {
      "vehicleTypeId": 2,
      "seasonId": 1
    },
    "oldRate": 95.00,
    "newRate": 103.00,
    "unit": "LKR"
  },
  {
    "keys": {
      "vehicleTypeId": 2,
      "seasonId": 2
    },
    "oldRate": 95.00,
    "newRate": 103.00,
    "unit": "LKR"
  }
]
```

**Example - Apply it**

```http
POST /api/v1/master-data/rates/vehicle/adjust
Content-Type: application/json

{
  "filter": {
    "vehicleTypeId": 2
  },
  "mode": "PERCENT",
  "value": 8,
  "rounding": "NEAREST_1",
  "effectiveFrom": "2026-11-01",
  "dryRun": false
}
```

`200 OK`

```json
[
  {
    "keys": {
      "vehicleTypeId": 2,
      "seasonId": 1
    },
    "oldRate": 95.00,
    "newRate": 103.00,
    "unit": "LKR"
  },
  {
    "keys": {
      "vehicleTypeId": 2,
      "seasonId": 2
    },
    "oldRate": 95.00,
    "newRate": 103.00,
    "unit": "LKR"
  }
]
```

<a id="patch-rates-family-id"></a>

### `PATCH /rates/{family}/{id}` - Correct a row in place (typo fix)

**Who can call it:** ADMIN, MANAGER

| Parameter | In | Type | Required | Default | Notes |
|---|---|---|---|---|---|
| `family` | path | string | yes |  |  |
| `id` | path | integer | yes |  | The record's id |

**Request body** (`application/json`): object.

| Field | Type | Required |
|---|---|---|
| `rate` | number | no |
| `effectiveFrom` | string (date) | no |
| `effectiveTo` | string (date) | no |

<details><summary>Response fields</summary>

| Field | Type |
|---|---|
| `id` | integer |
| `family` | string |
| `keys` | object |
| `labels` | object |
| `rate` | number |
| `unit` | enum: USD / LKR / PERCENT |
| `effectiveFrom` | string (date) |
| `effectiveTo` | string (date) |
| `isCurrent` | boolean |
| `createdAt` | string (date-time) |
| `createdBy` | string |

</details>

**Example - Correct a typo in place**

```http
PATCH /api/v1/master-data/rates/attraction/1
Content-Type: application/json

{
  "rate": 35.5
}
```

`200 OK`

```json
{
  "id": 1,
  "family": "attraction",
  "keys": {
    "attractionId": 1,
    "rateType": "PER_PERSON"
  },
  "labels": {
    "attractionId": "Sigiriya Rock Fortress (Lion Rock)"
  },
  "rate": 35.50,
  "unit": "USD",
  "effectiveFrom": "2026-10-18",
  "effectiveTo": "2026-12-01",
  "isCurrent": true,
  "createdAt": "2026-10-18T03:00:00Z",
  "createdBy": "nimali.manager"
}
```

**Example - Set an end date on a rate (it will then show up in the alerts)**

```http
PATCH /api/v1/master-data/rates/package/1
Content-Type: application/json

{
  "effectiveTo": "2026-10-30"
}
```

`200 OK`

```json
{
  "id": 1,
  "family": "package",
  "keys": {
    "packageId": 1
  },
  "labels": {
    "packageId": "Cultural Triangle Two-Rock Day Tour"
  },
  "rate": 120.00,
  "unit": "USD",
  "effectiveFrom": "2026-10-18",
  "effectiveTo": "2026-10-30",
  "isCurrent": true,
  "createdAt": "2026-10-18T03:00:00Z",
  "createdBy": "nimali.manager"
}
```

<a id="delete-rates-family-id"></a>

### `DELETE /rates/{family}/{id}` - Delete a pending future-dated row (history is never deleted)

**Who can call it:** ADMIN, MANAGER

| Parameter | In | Type | Required | Default | Notes |
|---|---|---|---|---|---|
| `family` | path | string | yes |  |  |
| `id` | path | integer | yes |  | The record's id |

**Example - Delete a rate that has not started yet (history is never deleted)**

```http
DELETE /api/v1/master-data/rates/attraction/2
```

`204 No Content`

---

## Properties

Hotels: the hotel record and everything attached to it. Room prices are edited through the Rates endpoints (family 'property').

| Method | Path | What it does |
|---|---|---|
| `GET` | [`/properties`](#get-properties) | List hotels |
| `POST` | [`/properties`](#post-properties) | Create a hotel |
| `GET` | [`/properties/{id}`](#get-properties-id) | One hotel with room types, child rules, inclusions, baby cot and dietary support |
| `PATCH` | [`/properties/{id}`](#patch-properties-id) | Quick edit: send only the fields that changed |
| `DELETE` | [`/properties/{id}`](#delete-properties-id) | Deactivate a hotel (soft delete) |
| `PUT` | [`/properties/{id}/baby-cot`](#put-properties-id-baby-cot) | Set the hotel's baby cot option |
| `PUT` | [`/properties/{id}/child-rules`](#put-properties-id-child-rules) | Replace the hotel's child pricing rules |
| `PUT` | [`/properties/{id}/dietary`](#put-properties-id-dietary) | Replace the hotel's dietary support |
| `PUT` | [`/properties/{id}/inclusions`](#put-properties-id-inclusions) | Replace the hotel's inclusions |
| `POST` | [`/properties/{id}/room-types`](#post-properties-id-room-types) | Add a room type to a hotel |
| `PATCH` | [`/room-types/{id}`](#patch-room-types-id) | Edit a room type (send only the changed fields) |
| `DELETE` | [`/room-types/{id}`](#delete-room-types-id) | Delete a room type |

<a id="get-properties"></a>

### `GET /properties` - List hotels

One slim row per hotel, with its cheapest current rate. Filter with q (name or code), destinationId, active and starRating. Sort with sort=name,asc (repeatable); allowed fields: name, code, starRating, destination, id.

**Who can call it:** ADMIN, MANAGER

| Parameter | In | Type | Required | Default | Notes |
|---|---|---|---|---|---|
| `q` | query | string | no |  | Case-insensitive text search |
| `destinationId` | query | integer | no |  |  |
| `active` | query | boolean | no |  | Keep only active (true) or inactive (false) records |
| `starRating` | query | integer | no |  |  |
| `page` | query | integer | no | `0` | Page number, starting at 0 |
| `size` | query | integer | no | `20` | Rows per page (1 to 200) |
| `sort` | query | array of string | no |  | field,direction - repeatable, e.g. sort=starRating,desc&sort=name,asc |

<details><summary>Response fields</summary>

| Field | Type |
|---|---|
| `content` | array of object |
| `content[].id` | integer |
| `content[].code` | string |
| `content[].name` | string |
| `content[].active` | boolean |
| `content[].starRating` | integer |
| `content[].destination` | object |
| `content[].destination.id` | integer |
| `content[].destination.name` | string |
| `content[].roomTypeCount` | integer |
| `content[].fromRate` | object |
| `content[].fromRate.rate` | number |
| `content[].fromRate.unit` | enum: USD / LKR / PERCENT |
| `totalElements` | integer |
| `totalPages` | integer |
| `number` | integer |
| `size` | integer |
| `first` | boolean |
| `last` | boolean |
| `empty` | boolean |

</details>

**Example - Search hotels**

```http
GET /api/v1/master-data/properties?q=lotus&sort=name,asc&size=5
```

`200 OK`

```json
{
  "content": [
    {
      "id": 1,
      "code": "LGH-SGR",
      "name": "Lotus Garden Hotel",
      "active": true,
      "starRating": 4,
      "destination": {
        "id": 1,
        "name": "Sigiriya"
      },
      "roomTypeCount": 2,
      "fromRate": null
    }
  ],
  "totalElements": 1,
  "totalPages": 1,
  "number": 0,
  "size": 5,
  "first": true,
  "last": true,
  "empty": false
}
```

<a id="post-properties"></a>

### `POST /properties` - Create a hotel

Required: code (unique), name, destinationId. Optional: address, starRating (1-5), website, email, phone, active (default true).

**Who can call it:** ADMIN, MANAGER

**Request body** (`application/json`): a JSON object - the fields are described above and shown in the examples.

<details><summary>Response fields</summary>

| Field | Type |
|---|---|
| `id` | integer |
| `code` | string |
| `name` | string |
| `active` | boolean |
| `starRating` | integer |
| `address` | string |
| `website` | string |
| `email` | string |
| `phone` | string |
| `destination` | object |
| `destination.id` | integer |
| `destination.name` | string |
| `roomTypes` | array of object |
| `roomTypes[].id` | integer |
| `roomTypes[].name` | string |
| `roomTypes[].baseCapacity` | integer |
| `roomTypes[].extraBedAllowed` | boolean |
| `childRules` | array of object |
| `childRules[].id` | integer |
| `childRules[].minAge` | integer |
| `childRules[].maxAge` | integer |
| `childRules[].requiresBed` | boolean |
| `childRules[].cost` | number |
| `inclusions` | array of object |
| `inclusions[].inclusion` | object |
| `inclusions[].inclusion.id` | integer |
| `inclusions[].inclusion.name` | string |
| `inclusions[].cost` | number |
| `babyCot` | object |
| `babyCot.available` | boolean |
| `babyCot.cost` | number |
| `dietary` | array of object |
| `dietary[].category` | object |
| `dietary[].category.id` | integer |
| `dietary[].category.name` | string |
| `dietary[].supported` | boolean |
| `rates` | array of object |
| `rates[].id` | integer |
| `rates[].family` | string |
| `rates[].keys` | object |
| `rates[].labels` | object |
| `rates[].rate` | number |
| `rates[].unit` | enum: USD / LKR / PERCENT |
| `rates[].effectiveFrom` | string (date) |
| `rates[].effectiveTo` | string (date) |
| `rates[].isCurrent` | boolean |
| `rates[].createdAt` | string (date-time) |
| `rates[].createdBy` | string |

</details>

**Example - Create a hotel**

```http
POST /api/v1/master-data/properties
Content-Type: application/json

{
  "code": "LGH-SGR",
  "name": "Lotus Garden Hotel",
  "destinationId": 1,
  "address": "Inamaluwa Road, Sigiriya",
  "starRating": 4,
  "website": "https://lotusgarden.example",
  "email": "reservations@lotusgarden.example",
  "phone": "+94 66 228 0000"
}
```

`201 Created` - `Location: /api/v1/master-data/properties/1`

```json
{
  "id": 1,
  "code": "LGH-SGR",
  "name": "Lotus Garden Hotel",
  "active": true,
  "starRating": 4,
  "address": "Inamaluwa Road, Sigiriya",
  "website": "https://lotusgarden.example",
  "email": "reservations@lotusgarden.example",
  "phone": "+94 66 228 0000",
  "destination": {
    "id": 1,
    "name": "Sigiriya"
  },
  "roomTypes": [],
  "childRules": [],
  "inclusions": [],
  "babyCot": null,
  "dietary": [],
  "rates": null
}
```

<a id="get-properties-id"></a>

### `GET /properties/{id}` - One hotel with room types, child rules, inclusions, baby cot and dietary support

Add include=rates to also get every rate in force on 'at' (default today).

**Who can call it:** ADMIN, MANAGER

| Parameter | In | Type | Required | Default | Notes |
|---|---|---|---|---|---|
| `id` | path | integer | yes |  | The record's id |
| `include` | query | array of string | no |  |  |
| `at` | query | string (date) | no |  | Evaluate included rates as of this date (default today) |

<details><summary>Response fields</summary>

| Field | Type |
|---|---|
| `id` | integer |
| `code` | string |
| `name` | string |
| `active` | boolean |
| `starRating` | integer |
| `address` | string |
| `website` | string |
| `email` | string |
| `phone` | string |
| `destination` | object |
| `destination.id` | integer |
| `destination.name` | string |
| `roomTypes` | array of object |
| `roomTypes[].id` | integer |
| `roomTypes[].name` | string |
| `roomTypes[].baseCapacity` | integer |
| `roomTypes[].extraBedAllowed` | boolean |
| `childRules` | array of object |
| `childRules[].id` | integer |
| `childRules[].minAge` | integer |
| `childRules[].maxAge` | integer |
| `childRules[].requiresBed` | boolean |
| `childRules[].cost` | number |
| `inclusions` | array of object |
| `inclusions[].inclusion` | object |
| `inclusions[].inclusion.id` | integer |
| `inclusions[].inclusion.name` | string |
| `inclusions[].cost` | number |
| `babyCot` | object |
| `babyCot.available` | boolean |
| `babyCot.cost` | number |
| `dietary` | array of object |
| `dietary[].category` | object |
| `dietary[].category.id` | integer |
| `dietary[].category.name` | string |
| `dietary[].supported` | boolean |
| `rates` | array of object |
| `rates[].id` | integer |
| `rates[].family` | string |
| `rates[].keys` | object |
| `rates[].labels` | object |
| `rates[].rate` | number |
| `rates[].unit` | enum: USD / LKR / PERCENT |
| `rates[].effectiveFrom` | string (date) |
| `rates[].effectiveTo` | string (date) |
| `rates[].isCurrent` | boolean |
| `rates[].createdAt` | string (date-time) |
| `rates[].createdBy` | string |

</details>

**Example - One hotel with everything attached**

```http
GET /api/v1/master-data/properties/1
```

`200 OK`

```json
{
  "id": 1,
  "code": "LGH-SGR",
  "name": "Lotus Garden Hotel",
  "active": true,
  "starRating": 4,
  "address": "Inamaluwa Road, Sigiriya",
  "website": null,
  "email": "reservations@lotusgarden.example",
  "phone": "+94 66 228 1111",
  "destination": {
    "id": 1,
    "name": "Sigiriya"
  },
  "roomTypes": [
    {
      "id": 1,
      "name": "Deluxe Double",
      "baseCapacity": 2,
      "extraBedAllowed": true
    },
    {
      "id": 2,
      "name": "Family Suite",
      "baseCapacity": 4,
      "extraBedAllowed": true
    }
  ],
  "childRules": [
    {
      "id": 1,
      "minAge": 0,
      "maxAge": 5,
      "requiresBed": false,
      "cost": 0
    },
    {
      "id": 2,
      "minAge": 6,
      "maxAge": 11,
      "requiresBed": true,
      "cost": 20
    }
  ],
  "inclusions": [
    {
      "inclusion": {
        "id": 1,
        "name": "Airport transfer"
      },
      "cost": 15
    },
    {
      "inclusion": {
        "id": 2,
        "name": "Welcome drink"
      },
      "cost": null
    }
  ],
  "babyCot": {
    "available": true,
    "cost": 10
  },
  "dietary": [
    {
      "category": {
        "id": 1,
        "name": "Vegetarian"
      },
      "supported": true
    },
    {
      "category": {
        "id": 2,
        "name": "Halal"
      },
      "supported": false
    }
  ],
  "rates": null
}
```

**Example - The same hotel with its current rates (include=rates)**

```http
GET /api/v1/master-data/properties/1?include=rates
```

`200 OK`

```json
{
  "id": 1,
  "code": "LGH-SGR",
  "name": "Lotus Garden Hotel",
  "active": true,
  "starRating": 4,
  "address": "Inamaluwa Road, Sigiriya",
  "website": null,
  "email": "reservations@lotusgarden.example",
  "phone": "+94 66 228 1111",
  "destination": {
    "id": 1,
    "name": "Sigiriya"
  },
  "roomTypes": [
    {
      "id": 1,
      "name": "Deluxe Double",
      "baseCapacity": 2,
      "extraBedAllowed": true
    },
    {
      "id": 2,
      "name": "Family Suite",
      "baseCapacity": 4,
      "extraBedAllowed": true
    }
  ],
  "childRules": [
    {
      "id": 1,
      "minAge": 0,
      "maxAge": 5,
      "requiresBed": false,
      "cost": 0
    },
    {
      "id": 2,
      "minAge": 6,
      "maxAge": 11,
      "requiresBed": true,
      "cost": 20
    }
  ],
  "inclusions": [
    {
      "inclusion": {
        "id": 1,
        "name": "Airport transfer"
      },
      "cost": 15
    },
    {
      "inclusion": {
        "id": 2,
        "name": "Welcome drink"
      },
      "cost": null
    }
  ],
  "babyCot": {
    "available": true,
    "cost": 10
  },
  "dietary": [
    {
      "category": {
        "id": 1,
        "name": "Vegetarian"
      },
      "supported": true
    },
    {
      "category": {
        "id": 2,
        "name": "Halal"
      },
      "supported": false
    }
  ],
  "rates": [
    {
      "id": 1,
      "family": "property",
      "keys": {
        "propertyId": 1,
        "roomTypeId": 1,
        "seasonId": 1,
        "occupancy": 2,
        "nationalityId": null
      },
      "labels": {
        "propertyId": "Lotus Garden Hotel",
        "roomTypeId": "Deluxe Double",
        "seasonId": "Summer",
        "nationalityId": null
      },
      "rate": 85.00,
      "unit": "USD",
      "effectiveFrom": "2026-10-18",
      "effectiveTo": null,
      "isCurrent": true,
      "createdAt": "2026-10-18T03:00:00Z",
      "createdBy": "nimali.manager"
    },
    {
      "id": 2,
      "family": "property",
      "keys": {
        "propertyId": 1,
        "roomTypeId": 1,
        "seasonId": 1,
        "occupancy": 1,
        "nationalityId": null
      },
      "labels": {
        "propertyId": "Lotus Garden Hotel",
        "roomTypeId": "Deluxe Double",
        "seasonId": "Summer",
        "nationalityId": null
      },
      "rate": 70.00,
      "unit": "USD",
      "effectiveFrom": "2026-10-18",
      "effectiveTo": null,
      "isCurrent": true,
      "createdAt": "2026-10-18T03:00:00Z",
      "createdBy": "nimali.manager"
    },
    {
      "id": 3,
      "family": "property",
      "keys": {
        "propertyId": 1,
        "roomTypeId": 1,
        "seasonId": 2,
        "occupancy": 2,
        "nationalityId": 1
      },
      "labels": {
        "propertyId": "Lotus Garden Hotel",
        "roomTypeId": "Deluxe Double",
        "seasonId": "Winter",
        "nationalityId": "India"
      },
      "rate": 110.00,
      "unit": "USD",
      "effectiveFrom": "2026-10-18",
      "effectiveTo": null,
      "isCurrent": true,
      "createdAt": "2026-10-18T03:00:00Z",
      "createdBy": "nimali.manager"
    }
  ]
}
```

<a id="patch-properties-id"></a>

### `PATCH /properties/{id}` - Quick edit: send only the fields that changed

Send null to clear an optional field. To bring back a deactivated hotel send {"active": true}.

**Who can call it:** ADMIN, MANAGER

| Parameter | In | Type | Required | Default | Notes |
|---|---|---|---|---|---|
| `id` | path | integer | yes |  | The record's id |

**Request body** (`application/json`): a JSON object - the fields are described above and shown in the examples.

<details><summary>Response fields</summary>

| Field | Type |
|---|---|
| `id` | integer |
| `code` | string |
| `name` | string |
| `active` | boolean |
| `starRating` | integer |
| `address` | string |
| `website` | string |
| `email` | string |
| `phone` | string |
| `destination` | object |
| `destination.id` | integer |
| `destination.name` | string |
| `roomTypes` | array of object |
| `roomTypes[].id` | integer |
| `roomTypes[].name` | string |
| `roomTypes[].baseCapacity` | integer |
| `roomTypes[].extraBedAllowed` | boolean |
| `childRules` | array of object |
| `childRules[].id` | integer |
| `childRules[].minAge` | integer |
| `childRules[].maxAge` | integer |
| `childRules[].requiresBed` | boolean |
| `childRules[].cost` | number |
| `inclusions` | array of object |
| `inclusions[].inclusion` | object |
| `inclusions[].inclusion.id` | integer |
| `inclusions[].inclusion.name` | string |
| `inclusions[].cost` | number |
| `babyCot` | object |
| `babyCot.available` | boolean |
| `babyCot.cost` | number |
| `dietary` | array of object |
| `dietary[].category` | object |
| `dietary[].category.id` | integer |
| `dietary[].category.name` | string |
| `dietary[].supported` | boolean |
| `rates` | array of object |
| `rates[].id` | integer |
| `rates[].family` | string |
| `rates[].keys` | object |
| `rates[].labels` | object |
| `rates[].rate` | number |
| `rates[].unit` | enum: USD / LKR / PERCENT |
| `rates[].effectiveFrom` | string (date) |
| `rates[].effectiveTo` | string (date) |
| `rates[].isCurrent` | boolean |
| `rates[].createdAt` | string (date-time) |
| `rates[].createdBy` | string |

</details>

**Example - Quick edit (only the changed fields; null clears)**

```http
PATCH /api/v1/master-data/properties/1
Content-Type: application/json

{
  "phone": "+94 66 228 1111",
  "website": null
}
```

`200 OK`

```json
{
  "id": 1,
  "code": "LGH-SGR",
  "name": "Lotus Garden Hotel",
  "active": true,
  "starRating": 4,
  "address": "Inamaluwa Road, Sigiriya",
  "website": null,
  "email": "reservations@lotusgarden.example",
  "phone": "+94 66 228 1111",
  "destination": {
    "id": 1,
    "name": "Sigiriya"
  },
  "roomTypes": [
    {
      "id": 1,
      "name": "Deluxe Double",
      "baseCapacity": 2,
      "extraBedAllowed": true
    },
    {
      "id": 2,
      "name": "Family Suite",
      "baseCapacity": 4,
      "extraBedAllowed": true
    }
  ],
  "childRules": [
    {
      "id": 1,
      "minAge": 0,
      "maxAge": 5,
      "requiresBed": false,
      "cost": 0
    },
    {
      "id": 2,
      "minAge": 6,
      "maxAge": 11,
      "requiresBed": true,
      "cost": 20
    }
  ],
  "inclusions": [
    {
      "inclusion": {
        "id": 1,
        "name": "Airport transfer"
      },
      "cost": 15
    },
    {
      "inclusion": {
        "id": 2,
        "name": "Welcome drink"
      },
      "cost": null
    }
  ],
  "babyCot": {
    "available": true,
    "cost": 10
  },
  "dietary": [
    {
      "category": {
        "id": 1,
        "name": "Vegetarian"
      },
      "supported": true
    },
    {
      "category": {
        "id": 2,
        "name": "Halal"
      },
      "supported": false
    }
  ],
  "rates": null
}
```

<a id="delete-properties-id"></a>

### `DELETE /properties/{id}` - Deactivate a hotel (soft delete)

Sets active=false; the hotel and its history stay in the database.

**Who can call it:** ADMIN, MANAGER

| Parameter | In | Type | Required | Default | Notes |
|---|---|---|---|---|---|
| `id` | path | integer | yes |  | The record's id |

**Example - Deactivate a hotel (soft delete)**

```http
DELETE /api/v1/master-data/properties/3
```

`204 No Content`

<a id="put-properties-id-baby-cot"></a>

### `PUT /properties/{id}/baby-cot` - Set the hotel's baby cot option

available=false clears the cost.

**Who can call it:** ADMIN, MANAGER

| Parameter | In | Type | Required | Default | Notes |
|---|---|---|---|---|---|
| `id` | path | integer | yes |  | The record's id |

**Request body** (`application/json`): object.

| Field | Type | Required |
|---|---|---|
| `available` | boolean | no |
| `cost` | number | no |

<details><summary>Response fields</summary>

| Field | Type |
|---|---|
| `available` | boolean |
| `cost` | number |

</details>

**Example - Set the baby cot option**

```http
PUT /api/v1/master-data/properties/1/baby-cot
Content-Type: application/json

{
  "available": true,
  "cost": 10
}
```

`200 OK`

```json
{
  "available": true,
  "cost": 10.00
}
```

<a id="put-properties-id-child-rules"></a>

### `PUT /properties/{id}/child-rules` - Replace the hotel's child pricing rules

Send the complete list (use [] to clear it). Age bands must not overlap; ages are 0-17.

**Who can call it:** ADMIN, MANAGER

| Parameter | In | Type | Required | Default | Notes |
|---|---|---|---|---|---|
| `id` | path | integer | yes |  | The record's id |

**Request body** (`application/json`): a JSON array of object.

| Field | Type | Required |
|---|---|---|
| `[].minAge` | integer | no |
| `[].maxAge` | integer | no |
| `[].requiresBed` | boolean | no |
| `[].cost` | number | no |

<details><summary>Response fields</summary>

| Field | Type |
|---|---|
| `[].id` | integer |
| `[].minAge` | integer |
| `[].maxAge` | integer |
| `[].requiresBed` | boolean |
| `[].cost` | number |

</details>

**Example - Rejected: overlapping age bands are reported per item**

```http
PUT /api/v1/master-data/properties/1/child-rules
Content-Type: application/json

[
  {
    "minAge": 0,
    "maxAge": 6,
    "requiresBed": false,
    "cost": 0
  },
  {
    "minAge": 5,
    "maxAge": 11,
    "requiresBed": true,
    "cost": 20
  }
]
```

`400 Bad Request`

```json
{
  "detail": "Validation failed",
  "instance": "/api/v1/master-data/properties/1/child-rules",
  "status": 400,
  "title": "Bad Request",
  "code": "VALIDATION_ERROR",
  "fieldErrors": {
    "[1].minAge": "age range overlaps the rule at [0]"
  }
}
```

**Example - Replace the child pricing rules**

```http
PUT /api/v1/master-data/properties/1/child-rules
Content-Type: application/json

[
  {
    "minAge": 0,
    "maxAge": 5,
    "requiresBed": false,
    "cost": 0
  },
  {
    "minAge": 6,
    "maxAge": 11,
    "requiresBed": true,
    "cost": 20
  }
]
```

`200 OK`

```json
[
  {
    "id": 1,
    "minAge": 0,
    "maxAge": 5,
    "requiresBed": false,
    "cost": 0
  },
  {
    "id": 2,
    "minAge": 6,
    "maxAge": 11,
    "requiresBed": true,
    "cost": 20
  }
]
```

<a id="put-properties-id-dietary"></a>

### `PUT /properties/{id}/dietary` - Replace the hotel's dietary support

Send the complete list of dietary categories with supported true/false.

**Who can call it:** ADMIN, MANAGER

| Parameter | In | Type | Required | Default | Notes |
|---|---|---|---|---|---|
| `id` | path | integer | yes |  | The record's id |

**Request body** (`application/json`): a JSON array of object.

| Field | Type | Required |
|---|---|---|
| `[].dietaryCategoryId` | integer | no |
| `[].supported` | boolean | no |

<details><summary>Response fields</summary>

| Field | Type |
|---|---|
| `[].category` | object |
| `[].category.id` | integer |
| `[].category.name` | string |
| `[].supported` | boolean |

</details>

**Example - Replace the dietary support**

```http
PUT /api/v1/master-data/properties/1/dietary
Content-Type: application/json

[
  {
    "dietaryCategoryId": 1,
    "supported": true
  },
  {
    "dietaryCategoryId": 2,
    "supported": false
  }
]
```

`200 OK`

```json
[
  {
    "category": {
      "id": 1,
      "name": "Vegetarian"
    },
    "supported": true
  },
  {
    "category": {
      "id": 2,
      "name": "Halal"
    },
    "supported": false
  }
]
```

<a id="put-properties-id-inclusions"></a>

### `PUT /properties/{id}/inclusions` - Replace the hotel's inclusions

Send the complete list. cost null means included free of charge.

**Who can call it:** ADMIN, MANAGER

| Parameter | In | Type | Required | Default | Notes |
|---|---|---|---|---|---|
| `id` | path | integer | yes |  | The record's id |

**Request body** (`application/json`): a JSON array of object.

| Field | Type | Required |
|---|---|---|
| `[].inclusionId` | integer | no |
| `[].cost` | number | no |

<details><summary>Response fields</summary>

| Field | Type |
|---|---|
| `[].inclusion` | object |
| `[].inclusion.id` | integer |
| `[].inclusion.name` | string |
| `[].cost` | number |

</details>

**Example - Replace the inclusions (cost null = included free)**

```http
PUT /api/v1/master-data/properties/1/inclusions
Content-Type: application/json

[
  {
    "inclusionId": 1,
    "cost": 15
  },
  {
    "inclusionId": 2,
    "cost": null
  }
]
```

`200 OK`

```json
[
  {
    "inclusion": {
      "id": 1,
      "name": "Airport transfer"
    },
    "cost": 15
  },
  {
    "inclusion": {
      "id": 2,
      "name": "Welcome drink"
    },
    "cost": null
  }
]
```

<a id="post-properties-id-room-types"></a>

### `POST /properties/{id}/room-types` - Add a room type to a hotel

Required: name (unique within the hotel). Optional: baseCapacity (1-30), extraBedAllowed.

**Who can call it:** ADMIN, MANAGER

| Parameter | In | Type | Required | Default | Notes |
|---|---|---|---|---|---|
| `id` | path | integer | yes |  | The record's id |

**Request body** (`application/json`): a JSON object - the fields are described above and shown in the examples.

<details><summary>Response fields</summary>

| Field | Type |
|---|---|
| `id` | integer |
| `name` | string |
| `baseCapacity` | integer |
| `extraBedAllowed` | boolean |

</details>

**Example - Add a room type**

```http
POST /api/v1/master-data/properties/1/room-types
Content-Type: application/json

{
  "name": "Deluxe Double",
  "baseCapacity": 2,
  "extraBedAllowed": true
}
```

`201 Created` - `Location: /api/v1/master-data/room-types/1`

```json
{
  "id": 1,
  "name": "Deluxe Double",
  "baseCapacity": 2,
  "extraBedAllowed": true
}
```

<a id="patch-room-types-id"></a>

### `PATCH /room-types/{id}` - Edit a room type (send only the changed fields)

**Who can call it:** ADMIN, MANAGER

| Parameter | In | Type | Required | Default | Notes |
|---|---|---|---|---|---|
| `id` | path | integer | yes |  | The record's id |

**Request body** (`application/json`): a JSON object - the fields are described above and shown in the examples.

<details><summary>Response fields</summary>

| Field | Type |
|---|---|
| `id` | integer |
| `name` | string |
| `baseCapacity` | integer |
| `extraBedAllowed` | boolean |

</details>

**Example - Edit a room type (only the changed fields)**

```http
PATCH /api/v1/master-data/room-types/2
Content-Type: application/json

{
  "extraBedAllowed": true
}
```

`200 OK`

```json
{
  "id": 2,
  "name": "Family Suite",
  "baseCapacity": 4,
  "extraBedAllowed": true
}
```

<a id="delete-room-types-id"></a>

### `DELETE /room-types/{id}` - Delete a room type

409 IN_USE while rates still reference it.

**Who can call it:** ADMIN, MANAGER

| Parameter | In | Type | Required | Default | Notes |
|---|---|---|---|---|---|
| `id` | path | integer | yes |  | The record's id |

**Example - Delete a room type**

```http
DELETE /api/v1/master-data/room-types/3
```

`204 No Content`

---

## Attractions

Priced sites, activities and charges. Prices are edited through the Rates endpoints (families 'attraction' and 'tuk-tuk').

| Method | Path | What it does |
|---|---|---|
| `GET` | [`/attractions`](#get-attractions) | List attractions |
| `POST` | [`/attractions`](#post-attractions) | Create an attraction |
| `GET` | [`/attractions/{id}`](#get-attractions-id) | One attraction with its current prices and tuk-tuk option |
| `PATCH` | [`/attractions/{id}`](#patch-attractions-id) | Quick edit: send only the fields that changed |
| `DELETE` | [`/attractions/{id}`](#delete-attractions-id) | Delete an attraction |
| `PUT` | [`/attractions/{id}/tuk-tuk`](#put-attractions-id-tuk-tuk) | Give the attraction a tuk-tuk option |
| `DELETE` | [`/attractions/{id}/tuk-tuk`](#delete-attractions-id-tuk-tuk) | Remove the tuk-tuk option |

<a id="get-attractions"></a>

### `GET /attractions` - List attractions

One row per attraction with the prices in force today. Filter with q (name), destinationId, safari and pricingMethod (PER_PERSON or FIXED_GROUP_PLUS_EXTRA). Sort with sort=name,asc (repeatable); allowed fields: name, destination, pricingMethod, id.

**Who can call it:** ADMIN, MANAGER

| Parameter | In | Type | Required | Default | Notes |
|---|---|---|---|---|---|
| `q` | query | string | no |  | Case-insensitive text search |
| `destinationId` | query | integer | no |  |  |
| `safari` | query | boolean | no |  |  |
| `pricingMethod` | query | string | no |  |  |
| `page` | query | integer | no | `0` | Page number, starting at 0 |
| `size` | query | integer | no | `20` | Rows per page (1 to 200) |
| `sort` | query | array of string | no |  | field,direction - repeatable, e.g. sort=destination,asc&sort=name,asc |

<details><summary>Response fields</summary>

| Field | Type |
|---|---|
| `content` | array of object |
| `content[].id` | integer |
| `content[].name` | string |
| `content[].destination` | object |
| `content[].destination.id` | integer |
| `content[].destination.name` | string |
| `content[].pricingMethod` | string |
| `content[].isSafari` | boolean |
| `content[].jeepCapacity` | integer |
| `content[].fixedGroupSize` | integer |
| `content[].currentRates` | array of object |
| `content[].currentRates[].rateType` | string |
| `content[].currentRates[].rate` | number |
| `content[].currentRates[].unit` | enum: USD / LKR / PERCENT |
| `totalElements` | integer |
| `totalPages` | integer |
| `number` | integer |
| `size` | integer |
| `first` | boolean |
| `last` | boolean |
| `empty` | boolean |

</details>

**Example - Search attractions**

```http
GET /api/v1/master-data/attractions?q=sigiriya&sort=name,asc
```

`200 OK`

```json
{
  "content": [
    {
      "id": 1,
      "name": "Sigiriya Rock Fortress (Lion Rock)",
      "destination": {
        "id": 1,
        "name": "Sigiriya"
      },
      "pricingMethod": "PER_PERSON",
      "isSafari": false,
      "jeepCapacity": null,
      "fixedGroupSize": null,
      "currentRates": []
    }
  ],
  "totalElements": 1,
  "totalPages": 1,
  "number": 0,
  "size": 20,
  "first": true,
  "last": true,
  "empty": false
}
```

<a id="post-attractions"></a>

### `POST /attractions` - Create an attraction

Required: name (unique within its destination), destinationId, pricingMethod (PER_PERSON or FIXED_GROUP_PLUS_EXTRA). Optional: isSafari (default false), jeepCapacity (1-50), fixedGroupSize (required for FIXED_GROUP_PLUS_EXTRA, not allowed for PER_PERSON).

**Who can call it:** ADMIN, MANAGER

**Request body** (`application/json`): a JSON object - the fields are described above and shown in the examples.

<details><summary>Response fields</summary>

| Field | Type |
|---|---|
| `id` | integer |
| `name` | string |
| `destination` | object |
| `destination.id` | integer |
| `destination.name` | string |
| `pricingMethod` | string |
| `isSafari` | boolean |
| `jeepCapacity` | integer |
| `fixedGroupSize` | integer |
| `currentRates` | array of object |
| `currentRates[].id` | integer |
| `currentRates[].family` | string |
| `currentRates[].keys` | object |
| `currentRates[].labels` | object |
| `currentRates[].rate` | number |
| `currentRates[].unit` | enum: USD / LKR / PERCENT |
| `currentRates[].effectiveFrom` | string (date) |
| `currentRates[].effectiveTo` | string (date) |
| `currentRates[].isCurrent` | boolean |
| `currentRates[].createdAt` | string (date-time) |
| `currentRates[].createdBy` | string |
| `tukTuk` | object |
| `tukTuk.locationId` | integer |
| `tukTuk.currentFare` | object |
| `tukTuk.currentFare.rate` | number |
| `tukTuk.currentFare.unit` | enum: USD / LKR / PERCENT |

</details>

**Example - Create an attraction**

```http
POST /api/v1/master-data/attractions
Content-Type: application/json

{
  "name": "Sigiriya Rock Fortress",
  "destinationId": 1,
  "pricingMethod": "PER_PERSON",
  "isSafari": false
}
```

`201 Created` - `Location: /api/v1/master-data/attractions/1`

```json
{
  "id": 1,
  "name": "Sigiriya Rock Fortress",
  "destination": {
    "id": 1,
    "name": "Sigiriya"
  },
  "pricingMethod": "PER_PERSON",
  "isSafari": false,
  "jeepCapacity": null,
  "fixedGroupSize": null,
  "currentRates": [],
  "tukTuk": null
}
```

**Example - Create a safari priced as a fixed group plus extras**

```http
POST /api/v1/master-data/attractions
Content-Type: application/json

{
  "name": "Yala Jeep Safari",
  "destinationId": 5,
  "pricingMethod": "FIXED_GROUP_PLUS_EXTRA",
  "isSafari": true,
  "jeepCapacity": 6,
  "fixedGroupSize": 6
}
```

`201 Created` - `Location: /api/v1/master-data/attractions/2`

```json
{
  "id": 2,
  "name": "Yala Jeep Safari",
  "destination": {
    "id": 5,
    "name": "Yala"
  },
  "pricingMethod": "FIXED_GROUP_PLUS_EXTRA",
  "isSafari": true,
  "jeepCapacity": 6,
  "fixedGroupSize": 6,
  "currentRates": [],
  "tukTuk": null
}
```

<a id="get-attractions-id"></a>

### `GET /attractions/{id}` - One attraction with its current prices and tuk-tuk option

'at' evaluates the prices as of that date (default today).

**Who can call it:** ADMIN, MANAGER

| Parameter | In | Type | Required | Default | Notes |
|---|---|---|---|---|---|
| `id` | path | integer | yes |  | The record's id |
| `at` | query | string (date) | no |  | Evaluate as of this date (default today) |

<details><summary>Response fields</summary>

| Field | Type |
|---|---|
| `id` | integer |
| `name` | string |
| `destination` | object |
| `destination.id` | integer |
| `destination.name` | string |
| `pricingMethod` | string |
| `isSafari` | boolean |
| `jeepCapacity` | integer |
| `fixedGroupSize` | integer |
| `currentRates` | array of object |
| `currentRates[].id` | integer |
| `currentRates[].family` | string |
| `currentRates[].keys` | object |
| `currentRates[].labels` | object |
| `currentRates[].rate` | number |
| `currentRates[].unit` | enum: USD / LKR / PERCENT |
| `currentRates[].effectiveFrom` | string (date) |
| `currentRates[].effectiveTo` | string (date) |
| `currentRates[].isCurrent` | boolean |
| `currentRates[].createdAt` | string (date-time) |
| `currentRates[].createdBy` | string |
| `tukTuk` | object |
| `tukTuk.locationId` | integer |
| `tukTuk.currentFare` | object |
| `tukTuk.currentFare.rate` | number |
| `tukTuk.currentFare.unit` | enum: USD / LKR / PERCENT |

</details>

**Example - One attraction with its rates and tuk-tuk fare**

```http
GET /api/v1/master-data/attractions/1
```

`200 OK`

```json
{
  "id": 1,
  "name": "Sigiriya Rock Fortress (Lion Rock)",
  "destination": {
    "id": 1,
    "name": "Sigiriya"
  },
  "pricingMethod": "PER_PERSON",
  "isSafari": false,
  "jeepCapacity": null,
  "fixedGroupSize": null,
  "currentRates": [
    {
      "id": 1,
      "family": "attraction",
      "keys": {
        "attractionId": 1,
        "rateType": "PER_PERSON"
      },
      "labels": {
        "attractionId": "Sigiriya Rock Fortress (Lion Rock)"
      },
      "rate": 35.50,
      "unit": "USD",
      "effectiveFrom": "2026-10-18",
      "effectiveTo": null,
      "isCurrent": true,
      "createdAt": "2026-10-18T03:00:00Z",
      "createdBy": "nimali.manager"
    },
    {
      "id": 3,
      "family": "attraction",
      "keys": {
        "attractionId": 1,
        "rateType": "CHILD_DISCOUNT"
      },
      "labels": {
        "attractionId": "Sigiriya Rock Fortress (Lion Rock)"
      },
      "rate": 17.50,
      "unit": "USD",
      "effectiveFrom": "2026-10-18",
      "effectiveTo": null,
      "isCurrent": true,
      "createdAt": "2026-10-18T03:00:00Z",
      "createdBy": "nimali.manager"
    }
  ],
  "tukTuk": {
    "locationId": 1,
    "currentFare": {
      "rate": 1500.00,
      "unit": "LKR"
    }
  }
}
```

<a id="patch-attractions-id"></a>

### `PATCH /attractions/{id}` - Quick edit: send only the fields that changed

Send null to clear jeepCapacity or fixedGroupSize. The result must still be valid, e.g. switching to FIXED_GROUP_PLUS_EXTRA needs a fixedGroupSize in the same request or already set.

**Who can call it:** ADMIN, MANAGER

| Parameter | In | Type | Required | Default | Notes |
|---|---|---|---|---|---|
| `id` | path | integer | yes |  | The record's id |

**Request body** (`application/json`): a JSON object - the fields are described above and shown in the examples.

<details><summary>Response fields</summary>

| Field | Type |
|---|---|
| `id` | integer |
| `name` | string |
| `destination` | object |
| `destination.id` | integer |
| `destination.name` | string |
| `pricingMethod` | string |
| `isSafari` | boolean |
| `jeepCapacity` | integer |
| `fixedGroupSize` | integer |
| `currentRates` | array of object |
| `currentRates[].id` | integer |
| `currentRates[].family` | string |
| `currentRates[].keys` | object |
| `currentRates[].labels` | object |
| `currentRates[].rate` | number |
| `currentRates[].unit` | enum: USD / LKR / PERCENT |
| `currentRates[].effectiveFrom` | string (date) |
| `currentRates[].effectiveTo` | string (date) |
| `currentRates[].isCurrent` | boolean |
| `currentRates[].createdAt` | string (date-time) |
| `currentRates[].createdBy` | string |
| `tukTuk` | object |
| `tukTuk.locationId` | integer |
| `tukTuk.currentFare` | object |
| `tukTuk.currentFare.rate` | number |
| `tukTuk.currentFare.unit` | enum: USD / LKR / PERCENT |

</details>

**Example - Quick edit**

```http
PATCH /api/v1/master-data/attractions/1
Content-Type: application/json

{
  "name": "Sigiriya Rock Fortress (Lion Rock)"
}
```

`200 OK`

```json
{
  "id": 1,
  "name": "Sigiriya Rock Fortress (Lion Rock)",
  "destination": {
    "id": 1,
    "name": "Sigiriya"
  },
  "pricingMethod": "PER_PERSON",
  "isSafari": false,
  "jeepCapacity": null,
  "fixedGroupSize": null,
  "currentRates": [],
  "tukTuk": null
}
```

<a id="delete-attractions-id"></a>

### `DELETE /attractions/{id}` - Delete an attraction

409 IN_USE while it has rates, a tuk-tuk option or belongs to a package.

**Who can call it:** ADMIN, MANAGER

| Parameter | In | Type | Required | Default | Notes |
|---|---|---|---|---|---|
| `id` | path | integer | yes |  | The record's id |

**Example - Delete an attraction nothing refers to**

```http
DELETE /api/v1/master-data/attractions/4
```

`204 No Content`

<a id="put-attractions-id-tuk-tuk"></a>

### `PUT /attractions/{id}/tuk-tuk` - Give the attraction a tuk-tuk option

Idempotent. The returned locationId is the key for tuk-tuk fares (PUT /rates/tuk-tuk with keys.tukTukLocationId).

**Who can call it:** ADMIN, MANAGER

| Parameter | In | Type | Required | Default | Notes |
|---|---|---|---|---|---|
| `id` | path | integer | yes |  | The record's id |

<details><summary>Response fields</summary>

| Field | Type |
|---|---|
| `locationId` | integer |
| `currentFare` | object |
| `currentFare.rate` | number |
| `currentFare.unit` | enum: USD / LKR / PERCENT |

</details>

**Example - Enable the tuk-tuk option**

```http
PUT /api/v1/master-data/attractions/1/tuk-tuk
```

`200 OK`

```json
{
  "locationId": 1,
  "currentFare": null
}
```

<a id="delete-attractions-id-tuk-tuk"></a>

### `DELETE /attractions/{id}/tuk-tuk` - Remove the tuk-tuk option

409 IN_USE while fares exist for it.

**Who can call it:** ADMIN, MANAGER

| Parameter | In | Type | Required | Default | Notes |
|---|---|---|---|---|---|
| `id` | path | integer | yes |  | The record's id |

**Example - Remove the tuk-tuk option**

```http
DELETE /api/v1/master-data/attractions/3/tuk-tuk
```

`204 No Content`

---

## Vehicles

The fleet. A vehicle's per-km price comes from its vehicle TYPE (Rates endpoints, family 'vehicle'), not from the vehicle.

| Method | Path | What it does |
|---|---|---|
| `GET` | [`/vehicles`](#get-vehicles) | List vehicles |
| `POST` | [`/vehicles`](#post-vehicles) | Add a vehicle |
| `GET` | [`/vehicles/{id}`](#get-vehicles-id) | One vehicle |
| `PATCH` | [`/vehicles/{id}`](#patch-vehicles-id) | Quick edit: send only the fields that changed |
| `DELETE` | [`/vehicles/{id}`](#delete-vehicles-id) | Take a vehicle out of service (soft delete) |

<a id="get-vehicles"></a>

### `GET /vehicles` - List vehicles

Each vehicle comes with its type, driver, assistant and a list of warnings (expired or expiring insurance / revenue licence, no driver, inactive driver). Filter with q (registration or owner), vehicleTypeId, method (INTERNAL or CONTRACTED), active and expiringWithinDays (insurance or licence expires within N days - already-expired ones are included). Sort with sort=registrationNo,asc (repeatable); fields: registrationNo, type, seatingCapacity, insuranceExpiry, licenseExpiry, id.

**Who can call it:** ADMIN, MANAGER

| Parameter | In | Type | Required | Default | Notes |
|---|---|---|---|---|---|
| `q` | query | string | no |  | Case-insensitive text search |
| `vehicleTypeId` | query | integer | no |  |  |
| `method` | query | string | no |  |  |
| `active` | query | boolean | no |  | Keep only active (true) or inactive (false) records |
| `expiringWithinDays` | query | integer | no |  |  |
| `page` | query | integer | no | `0` | Page number, starting at 0 |
| `size` | query | integer | no | `20` | Rows per page (1 to 200) |
| `sort` | query | array of string | no |  | field,direction - repeatable, e.g. sort=insuranceExpiry,asc |

<details><summary>Response fields</summary>

| Field | Type |
|---|---|
| `content` | array of object |
| `content[].id` | integer |
| `content[].registrationNo` | string |
| `content[].vehicleType` | object |
| `content[].vehicleType.id` | integer |
| `content[].vehicleType.name` | string |
| `content[].seatingCapacity` | integer |
| `content[].method` | string |
| `content[].ownerName` | string |
| `content[].insuranceCompany` | string |
| `content[].policyNumber` | string |
| `content[].insuranceExpiryDate` | string (date) |
| `content[].revenueLicenseExpiryDate` | string (date) |
| `content[].driver` | object |
| `content[].driver.id` | integer |
| `content[].driver.name` | string |
| `content[].assistant` | object |
| `content[].assistant.id` | integer |
| `content[].assistant.name` | string |
| `content[].active` | boolean |
| `content[].warnings` | array of object |
| `content[].warnings[].code` | string |
| `content[].warnings[].daysLeft` | integer |
| `totalElements` | integer |
| `totalPages` | integer |
| `number` | integer |
| `size` | integer |
| `first` | boolean |
| `last` | boolean |
| `empty` | boolean |

</details>

**Example - Vehicles whose documents expire within 30 days**

```http
GET /api/v1/master-data/vehicles?expiringWithinDays=30&sort=insuranceExpiry,asc
```

`200 OK`

```json
{
  "content": [
    {
      "id": 2,
      "registrationNo": "WP-9999",
      "vehicleType": {
        "id": 2,
        "name": "Van"
      },
      "seatingCapacity": 8,
      "method": "CONTRACTED",
      "ownerName": "K. Silva",
      "insuranceCompany": null,
      "policyNumber": null,
      "insuranceExpiryDate": "2026-10-01",
      "revenueLicenseExpiryDate": "2027-06-30",
      "driver": null,
      "assistant": null,
      "active": true,
      "warnings": [
        {
          "code": "INSURANCE_EXPIRED",
          "daysLeft": -17
        },
        {
          "code": "NO_DRIVER",
          "daysLeft": null
        }
      ]
    },
    {
      "id": 1,
      "registrationNo": "CAB-1234",
      "vehicleType": {
        "id": 2,
        "name": "Van"
      },
      "seatingCapacity": 7,
      "method": "INTERNAL",
      "ownerName": "Serene Travels (Pvt) Ltd",
      "insuranceCompany": "Ceylinco",
      "policyNumber": "P-77881",
      "insuranceExpiryDate": "2026-11-02",
      "revenueLicenseExpiryDate": "2027-03-31",
      "driver": {
        "id": 1,
        "name": "Nimal Perera"
      },
      "assistant": {
        "id": 2,
        "name": "Sunil Fernando"
      },
      "active": true,
      "warnings": [
        {
          "code": "INSURANCE_EXPIRING",
          "daysLeft": 15
        }
      ]
    }
  ],
  "totalElements": 2,
  "totalPages": 1,
  "number": 0,
  "size": 20,
  "first": true,
  "last": true,
  "empty": false
}
```

<a id="post-vehicles"></a>

### `POST /vehicles` - Add a vehicle

Required: registrationNo (unique; stored in upper case), vehicleTypeId, seatingCapacity (at most what the type carries), method (INTERNAL or CONTRACTED). Optional: ownerName, insuranceCompany, policyNumber, insuranceExpiryDate, revenueLicenseExpiryDate, driverId (an active DRIVER), assistantId (an active ASSISTANT), active (default true).

**Who can call it:** ADMIN, MANAGER

**Request body** (`application/json`): a JSON object - the fields are described above and shown in the examples.

<details><summary>Response fields</summary>

| Field | Type |
|---|---|
| `id` | integer |
| `registrationNo` | string |
| `vehicleType` | object |
| `vehicleType.id` | integer |
| `vehicleType.name` | string |
| `seatingCapacity` | integer |
| `method` | string |
| `ownerName` | string |
| `insuranceCompany` | string |
| `policyNumber` | string |
| `insuranceExpiryDate` | string (date) |
| `revenueLicenseExpiryDate` | string (date) |
| `driver` | object |
| `driver.id` | integer |
| `driver.name` | string |
| `assistant` | object |
| `assistant.id` | integer |
| `assistant.name` | string |
| `active` | boolean |
| `warnings` | array of object |
| `warnings[].code` | string |
| `warnings[].daysLeft` | integer |

</details>

**Example - Create a vehicle with its driver and assistant**

```http
POST /api/v1/master-data/vehicles
Content-Type: application/json

{
  "registrationNo": "CAB-1234",
  "vehicleTypeId": 2,
  "seatingCapacity": 7,
  "method": "INTERNAL",
  "ownerName": "Serene Travels",
  "insuranceCompany": "Ceylinco",
  "policyNumber": "P-77881",
  "insuranceExpiryDate": "2026-11-02",
  "revenueLicenseExpiryDate": "2027-03-31",
  "driverId": 1,
  "assistantId": 2
}
```

`201 Created` - `Location: /api/v1/master-data/vehicles/1`

```json
{
  "id": 1,
  "registrationNo": "CAB-1234",
  "vehicleType": {
    "id": 2,
    "name": "Van"
  },
  "seatingCapacity": 7,
  "method": "INTERNAL",
  "ownerName": "Serene Travels",
  "insuranceCompany": "Ceylinco",
  "policyNumber": "P-77881",
  "insuranceExpiryDate": "2026-11-02",
  "revenueLicenseExpiryDate": "2027-03-31",
  "driver": {
    "id": 1,
    "name": "Nimal Perera"
  },
  "assistant": {
    "id": 2,
    "name": "Sunil Fernando"
  },
  "active": true,
  "warnings": [
    {
      "code": "INSURANCE_EXPIRING",
      "daysLeft": 15
    }
  ]
}
```

**Example - Create a vehicle with no driver and an expired insurance**

```http
POST /api/v1/master-data/vehicles
Content-Type: application/json

{
  "registrationNo": "WP-9999",
  "vehicleTypeId": 2,
  "seatingCapacity": 8,
  "method": "CONTRACTED",
  "ownerName": "K. Silva",
  "insuranceExpiryDate": "2026-10-01",
  "revenueLicenseExpiryDate": "2027-06-30"
}
```

`201 Created` - `Location: /api/v1/master-data/vehicles/2`

```json
{
  "id": 2,
  "registrationNo": "WP-9999",
  "vehicleType": {
    "id": 2,
    "name": "Van"
  },
  "seatingCapacity": 8,
  "method": "CONTRACTED",
  "ownerName": "K. Silva",
  "insuranceCompany": null,
  "policyNumber": null,
  "insuranceExpiryDate": "2026-10-01",
  "revenueLicenseExpiryDate": "2027-06-30",
  "driver": null,
  "assistant": null,
  "active": true,
  "warnings": [
    {
      "code": "INSURANCE_EXPIRED",
      "daysLeft": -17
    },
    {
      "code": "NO_DRIVER",
      "daysLeft": null
    }
  ]
}
```

<a id="get-vehicles-id"></a>

### `GET /vehicles/{id}` - One vehicle

**Who can call it:** ADMIN, MANAGER

| Parameter | In | Type | Required | Default | Notes |
|---|---|---|---|---|---|
| `id` | path | integer | yes |  | The record's id |

<details><summary>Response fields</summary>

| Field | Type |
|---|---|
| `id` | integer |
| `registrationNo` | string |
| `vehicleType` | object |
| `vehicleType.id` | integer |
| `vehicleType.name` | string |
| `seatingCapacity` | integer |
| `method` | string |
| `ownerName` | string |
| `insuranceCompany` | string |
| `policyNumber` | string |
| `insuranceExpiryDate` | string (date) |
| `revenueLicenseExpiryDate` | string (date) |
| `driver` | object |
| `driver.id` | integer |
| `driver.name` | string |
| `assistant` | object |
| `assistant.id` | integer |
| `assistant.name` | string |
| `active` | boolean |
| `warnings` | array of object |
| `warnings[].code` | string |
| `warnings[].daysLeft` | integer |

</details>

**Example - One vehicle, with its warnings**

```http
GET /api/v1/master-data/vehicles/1
```

`200 OK`

```json
{
  "id": 1,
  "registrationNo": "CAB-1234",
  "vehicleType": {
    "id": 2,
    "name": "Van"
  },
  "seatingCapacity": 7,
  "method": "INTERNAL",
  "ownerName": "Serene Travels (Pvt) Ltd",
  "insuranceCompany": "Ceylinco",
  "policyNumber": "P-77881",
  "insuranceExpiryDate": "2026-11-02",
  "revenueLicenseExpiryDate": "2027-03-31",
  "driver": {
    "id": 1,
    "name": "Nimal Perera"
  },
  "assistant": {
    "id": 2,
    "name": "Sunil Fernando"
  },
  "active": true,
  "warnings": [
    {
      "code": "INSURANCE_EXPIRING",
      "daysLeft": 15
    }
  ]
}
```

<a id="patch-vehicles-id"></a>

### `PATCH /vehicles/{id}` - Quick edit: send only the fields that changed

Typical edits: renew an expiry date, change the driver, bring a vehicle back with {"active": true}. Send null to clear an optional field such as assistantId.

**Who can call it:** ADMIN, MANAGER

| Parameter | In | Type | Required | Default | Notes |
|---|---|---|---|---|---|
| `id` | path | integer | yes |  | The record's id |

**Request body** (`application/json`): a JSON object - the fields are described above and shown in the examples.

<details><summary>Response fields</summary>

| Field | Type |
|---|---|
| `id` | integer |
| `registrationNo` | string |
| `vehicleType` | object |
| `vehicleType.id` | integer |
| `vehicleType.name` | string |
| `seatingCapacity` | integer |
| `method` | string |
| `ownerName` | string |
| `insuranceCompany` | string |
| `policyNumber` | string |
| `insuranceExpiryDate` | string (date) |
| `revenueLicenseExpiryDate` | string (date) |
| `driver` | object |
| `driver.id` | integer |
| `driver.name` | string |
| `assistant` | object |
| `assistant.id` | integer |
| `assistant.name` | string |
| `active` | boolean |
| `warnings` | array of object |
| `warnings[].code` | string |
| `warnings[].daysLeft` | integer |

</details>

**Example - Quick edit**

```http
PATCH /api/v1/master-data/vehicles/1
Content-Type: application/json

{
  "ownerName": "Serene Travels (Pvt) Ltd"
}
```

`200 OK`

```json
{
  "id": 1,
  "registrationNo": "CAB-1234",
  "vehicleType": {
    "id": 2,
    "name": "Van"
  },
  "seatingCapacity": 7,
  "method": "INTERNAL",
  "ownerName": "Serene Travels (Pvt) Ltd",
  "insuranceCompany": "Ceylinco",
  "policyNumber": "P-77881",
  "insuranceExpiryDate": "2026-11-02",
  "revenueLicenseExpiryDate": "2027-03-31",
  "driver": {
    "id": 1,
    "name": "Nimal Perera"
  },
  "assistant": {
    "id": 2,
    "name": "Sunil Fernando"
  },
  "active": true,
  "warnings": [
    {
      "code": "INSURANCE_EXPIRING",
      "daysLeft": 15
    }
  ]
}
```

<a id="delete-vehicles-id"></a>

### `DELETE /vehicles/{id}` - Take a vehicle out of service (soft delete)

Sets active=false; the vehicle stays in the database.

**Who can call it:** ADMIN, MANAGER

| Parameter | In | Type | Required | Default | Notes |
|---|---|---|---|---|---|
| `id` | path | integer | yes |  | The record's id |

**Example - Take a vehicle out of service (soft delete)**

```http
DELETE /api/v1/master-data/vehicles/3
```

`204 No Content`

---

## Drivers

Drivers and their assistants.

| Method | Path | What it does |
|---|---|---|
| `GET` | [`/drivers`](#get-drivers) | List drivers and assistants |
| `POST` | [`/drivers`](#post-drivers) | Add a driver or assistant |
| `GET` | [`/drivers/{id}`](#get-drivers-id) | One driver or assistant |
| `PATCH` | [`/drivers/{id}`](#patch-drivers-id) | Quick edit: send only the fields that changed |
| `DELETE` | [`/drivers/{id}`](#delete-drivers-id) | Deactivate a driver (soft delete) |

<a id="get-drivers"></a>

### `GET /drivers` - List drivers and assistants

Each person comes with the vehicles they are assigned to. Filter with q (name, NIC, phone or licence number), role (DRIVER or ASSISTANT), active and chauffeur. Sort with sort=name,asc (repeatable); fields: name, role, nic, id.

**Who can call it:** ADMIN, MANAGER

| Parameter | In | Type | Required | Default | Notes |
|---|---|---|---|---|---|
| `q` | query | string | no |  | Case-insensitive text search |
| `role` | query | string | no |  |  |
| `active` | query | boolean | no |  | Keep only active (true) or inactive (false) records |
| `chauffeur` | query | boolean | no |  |  |
| `page` | query | integer | no | `0` | Page number, starting at 0 |
| `size` | query | integer | no | `20` | Rows per page (1 to 200) |
| `sort` | query | array of string | no |  | field,direction - repeatable, e.g. sort=name,asc |

<details><summary>Response fields</summary>

| Field | Type |
|---|---|
| `content` | array of object |
| `content[].id` | integer |
| `content[].title` | string |
| `content[].name` | string |
| `content[].nameWithInitials` | string |
| `content[].shortname` | string |
| `content[].gender` | string |
| `content[].nic` | string |
| `content[].dateOfBirth` | string (date) |
| `content[].address` | string |
| `content[].contact` | string |
| `content[].mobile2` | string |
| `content[].licenseNo` | string |
| `content[].role` | string |
| `content[].isChauffeur` | boolean |
| `content[].active` | boolean |
| `content[].vehicles` | array of object |
| `content[].vehicles[].id` | integer |
| `content[].vehicles[].name` | string |
| `totalElements` | integer |
| `totalPages` | integer |
| `number` | integer |
| `size` | integer |
| `first` | boolean |
| `last` | boolean |
| `empty` | boolean |

</details>

**Example - Search drivers**

```http
GET /api/v1/master-data/drivers?role=DRIVER&sort=name,asc
```

`200 OK`

```json
{
  "content": [
    {
      "id": 1,
      "title": null,
      "name": "Nimal Perera",
      "nameWithInitials": null,
      "shortname": null,
      "gender": null,
      "nic": "851234567V",
      "dateOfBirth": null,
      "address": null,
      "contact": "0779876543",
      "mobile2": "0112223344",
      "licenseNo": "B1234567",
      "role": "DRIVER",
      "isChauffeur": false,
      "active": true,
      "vehicles": [
        {
          "id": 1,
          "name": "CAB-1234"
        }
      ]
    },
    {
      "id": 3,
      "title": null,
      "name": "Retired Driver",
      "nameWithInitials": null,
      "shortname": null,
      "gender": null,
      "nic": "700000000V",
      "dateOfBirth": null,
      "address": null,
      "contact": null,
      "mobile2": null,
      "licenseNo": null,
      "role": "DRIVER",
      "isChauffeur": false,
      "active": false,
      "vehicles": []
    }
  ],
  "totalElements": 2,
  "totalPages": 1,
  "number": 0,
  "size": 20,
  "first": true,
  "last": true,
  "empty": false
}
```

<a id="post-drivers"></a>

### `POST /drivers` - Add a driver or assistant

Required: name, nic (unique). Optional: title, nameWithInitials, shortname, gender (MALE, FEMALE, OTHER), dateOfBirth, address, contact, mobile2, licenseNo, role (DRIVER by default, or ASSISTANT), isChauffeur (default false; DRIVER only), active (default true).

**Who can call it:** ADMIN, MANAGER

**Request body** (`application/json`): a JSON object - the fields are described above and shown in the examples.

<details><summary>Response fields</summary>

| Field | Type |
|---|---|
| `id` | integer |
| `title` | string |
| `name` | string |
| `nameWithInitials` | string |
| `shortname` | string |
| `gender` | string |
| `nic` | string |
| `dateOfBirth` | string (date) |
| `address` | string |
| `contact` | string |
| `mobile2` | string |
| `licenseNo` | string |
| `role` | string |
| `isChauffeur` | boolean |
| `active` | boolean |
| `vehicles` | array of object |
| `vehicles[].id` | integer |
| `vehicles[].name` | string |

</details>

**Example - Create a driver**

```http
POST /api/v1/master-data/drivers
Content-Type: application/json

{
  "name": "Nimal Perera",
  "nic": "851234567V",
  "role": "DRIVER",
  "contact": "0771234567",
  "licenseNo": "B1234567",
  "isChauffeur": false
}
```

`201 Created` - `Location: /api/v1/master-data/drivers/1`

```json
{
  "id": 1,
  "title": null,
  "name": "Nimal Perera",
  "nameWithInitials": null,
  "shortname": null,
  "gender": null,
  "nic": "851234567V",
  "dateOfBirth": null,
  "address": null,
  "contact": "0771234567",
  "mobile2": null,
  "licenseNo": "B1234567",
  "role": "DRIVER",
  "isChauffeur": false,
  "active": true,
  "vehicles": []
}
```

**Example - Create an assistant**

```http
POST /api/v1/master-data/drivers
Content-Type: application/json

{
  "name": "Sunil Fernando",
  "nic": "902345678V",
  "role": "ASSISTANT"
}
```

`201 Created` - `Location: /api/v1/master-data/drivers/2`

```json
{
  "id": 2,
  "title": null,
  "name": "Sunil Fernando",
  "nameWithInitials": null,
  "shortname": null,
  "gender": null,
  "nic": "902345678V",
  "dateOfBirth": null,
  "address": null,
  "contact": null,
  "mobile2": null,
  "licenseNo": null,
  "role": "ASSISTANT",
  "isChauffeur": false,
  "active": true,
  "vehicles": []
}
```

<a id="get-drivers-id"></a>

### `GET /drivers/{id}` - One driver or assistant

**Who can call it:** ADMIN, MANAGER

| Parameter | In | Type | Required | Default | Notes |
|---|---|---|---|---|---|
| `id` | path | integer | yes |  | The record's id |

<details><summary>Response fields</summary>

| Field | Type |
|---|---|
| `id` | integer |
| `title` | string |
| `name` | string |
| `nameWithInitials` | string |
| `shortname` | string |
| `gender` | string |
| `nic` | string |
| `dateOfBirth` | string (date) |
| `address` | string |
| `contact` | string |
| `mobile2` | string |
| `licenseNo` | string |
| `role` | string |
| `isChauffeur` | boolean |
| `active` | boolean |
| `vehicles` | array of object |
| `vehicles[].id` | integer |
| `vehicles[].name` | string |

</details>

**Example - One driver**

```http
GET /api/v1/master-data/drivers/1
```

`200 OK`

```json
{
  "id": 1,
  "title": null,
  "name": "Nimal Perera",
  "nameWithInitials": null,
  "shortname": null,
  "gender": null,
  "nic": "851234567V",
  "dateOfBirth": null,
  "address": null,
  "contact": "0779876543",
  "mobile2": "0112223344",
  "licenseNo": "B1234567",
  "role": "DRIVER",
  "isChauffeur": false,
  "active": true,
  "vehicles": [
    {
      "id": 1,
      "name": "CAB-1234"
    }
  ]
}
```

<a id="patch-drivers-id"></a>

### `PATCH /drivers/{id}` - Quick edit: send only the fields that changed

Send null to clear an optional field. A person cannot change role while assigned to a vehicle in the other slot (409 IN_USE).

**Who can call it:** ADMIN, MANAGER

| Parameter | In | Type | Required | Default | Notes |
|---|---|---|---|---|---|
| `id` | path | integer | yes |  | The record's id |

**Request body** (`application/json`): a JSON object - the fields are described above and shown in the examples.

<details><summary>Response fields</summary>

| Field | Type |
|---|---|
| `id` | integer |
| `title` | string |
| `name` | string |
| `nameWithInitials` | string |
| `shortname` | string |
| `gender` | string |
| `nic` | string |
| `dateOfBirth` | string (date) |
| `address` | string |
| `contact` | string |
| `mobile2` | string |
| `licenseNo` | string |
| `role` | string |
| `isChauffeur` | boolean |
| `active` | boolean |
| `vehicles` | array of object |
| `vehicles[].id` | integer |
| `vehicles[].name` | string |

</details>

**Example - Quick edit**

```http
PATCH /api/v1/master-data/drivers/1
Content-Type: application/json

{
  "contact": "0779876543",
  "mobile2": "0112223344"
}
```

`200 OK`

```json
{
  "id": 1,
  "title": null,
  "name": "Nimal Perera",
  "nameWithInitials": null,
  "shortname": null,
  "gender": null,
  "nic": "851234567V",
  "dateOfBirth": null,
  "address": null,
  "contact": "0779876543",
  "mobile2": "0112223344",
  "licenseNo": "B1234567",
  "role": "DRIVER",
  "isChauffeur": false,
  "active": true,
  "vehicles": []
}
```

<a id="delete-drivers-id"></a>

### `DELETE /drivers/{id}` - Deactivate a driver (soft delete)

Sets active=false. Vehicles they were assigned to show a DRIVER_INACTIVE warning until reassigned.

**Who can call it:** ADMIN, MANAGER

| Parameter | In | Type | Required | Default | Notes |
|---|---|---|---|---|---|
| `id` | path | integer | yes |  | The record's id |

**Example - Deactivate a driver (soft delete)**

```http
DELETE /api/v1/master-data/drivers/3
```

`204 No Content`

---

## Guides

Guides and the languages each speaks. Guide accommodation costs are Rates endpoints (family 'guide-accommodation').

| Method | Path | What it does |
|---|---|---|
| `GET` | [`/guides`](#get-guides) | List guides |
| `POST` | [`/guides`](#post-guides) | Add a guide |
| `GET` | [`/guides/{id}`](#get-guides-id) | One guide with their languages |
| `PATCH` | [`/guides/{id}`](#patch-guides-id) | Quick edit: send only the fields that changed |
| `DELETE` | [`/guides/{id}`](#delete-guides-id) | Deactivate a guide (soft delete) |
| `PUT` | [`/guides/{id}/languages`](#put-guides-id-languages) | Replace the languages the guide speaks |

<a id="get-guides"></a>

### `GET /guides` - List guides

Each guide comes with the languages they speak. Filter with q (name, nickname, guide number, NIC, phone, email or town), guideType (DRIVER, CHAUFFEUR_GUIDE or NATIONAL_GUIDE), languageId and active. Sort with sort=name,asc (repeatable); fields: name, guideNo, guideType, town, id.

**Who can call it:** ADMIN, MANAGER

| Parameter | In | Type | Required | Default | Notes |
|---|---|---|---|---|---|
| `q` | query | string | no |  | Case-insensitive text search |
| `guideType` | query | string | no |  |  |
| `languageId` | query | integer | no |  |  |
| `active` | query | boolean | no |  | Keep only active (true) or inactive (false) records |
| `page` | query | integer | no | `0` | Page number, starting at 0 |
| `size` | query | integer | no | `20` | Rows per page (1 to 200) |
| `sort` | query | array of string | no |  | field,direction - repeatable, e.g. sort=town,asc&sort=name,asc |

<details><summary>Response fields</summary>

| Field | Type |
|---|---|
| `content` | array of object |
| `content[].id` | integer |
| `content[].guideNo` | string |
| `content[].name` | string |
| `content[].nickname` | string |
| `content[].guideType` | string |
| `content[].nic` | string |
| `content[].contact` | string |
| `content[].email` | string |
| `content[].address` | string |
| `content[].town` | string |
| `content[].active` | boolean |
| `content[].languages` | array of object |
| `content[].languages[].id` | integer |
| `content[].languages[].name` | string |
| `totalElements` | integer |
| `totalPages` | integer |
| `number` | integer |
| `size` | integer |
| `first` | boolean |
| `last` | boolean |
| `empty` | boolean |

</details>

**Example - Search guides by language**

```http
GET /api/v1/master-data/guides?languageId=1&sort=name,asc
```

`200 OK`

```json
{
  "content": [
    {
      "id": 1,
      "guideNo": "N-0101",
      "name": "Athula Bandara",
      "nickname": null,
      "guideType": "NATIONAL_GUIDE",
      "nic": "781234567V",
      "contact": "0777490642",
      "email": "athula@example.com",
      "address": null,
      "town": "Matale",
      "active": true,
      "languages": [
        {
          "id": 1,
          "name": "English"
        }
      ]
    }
  ],
  "totalElements": 1,
  "totalPages": 1,
  "number": 0,
  "size": 20,
  "first": true,
  "last": true,
  "empty": false
}
```

<a id="post-guides"></a>

### `POST /guides` - Add a guide

Required: guideNo (unique, stored upper-case), name, guideType. Optional: nickname, nic (unique), contact, email, address, town, active (default true), and languageIds - a list of language ids to set the languages in the same call.

**Who can call it:** ADMIN, MANAGER

**Request body** (`application/json`): a JSON object - the fields are described above and shown in the examples.

<details><summary>Response fields</summary>

| Field | Type |
|---|---|
| `id` | integer |
| `guideNo` | string |
| `name` | string |
| `nickname` | string |
| `guideType` | string |
| `nic` | string |
| `contact` | string |
| `email` | string |
| `address` | string |
| `town` | string |
| `active` | boolean |
| `languages` | array of object |
| `languages[].id` | integer |
| `languages[].name` | string |

</details>

**Example - Create a guide with the languages spoken**

```http
POST /api/v1/master-data/guides
Content-Type: application/json

{
  "guideNo": "N-0101",
  "name": "Athula Bandara",
  "guideType": "NATIONAL_GUIDE",
  "nickname": "Athula",
  "nic": "781234567V",
  "contact": "0777490642",
  "email": "athula@example.com",
  "town": "Kandy",
  "languageIds": [1, 2]
}
```

`201 Created` - `Location: /api/v1/master-data/guides/1`

```json
{
  "id": 1,
  "guideNo": "N-0101",
  "name": "Athula Bandara",
  "nickname": "Athula",
  "guideType": "NATIONAL_GUIDE",
  "nic": "781234567V",
  "contact": "0777490642",
  "email": "athula@example.com",
  "address": null,
  "town": "Kandy",
  "active": true,
  "languages": [
    {
      "id": 1,
      "name": "English"
    },
    {
      "id": 2,
      "name": "German"
    }
  ]
}
```

<a id="get-guides-id"></a>

### `GET /guides/{id}` - One guide with their languages

**Who can call it:** ADMIN, MANAGER

| Parameter | In | Type | Required | Default | Notes |
|---|---|---|---|---|---|
| `id` | path | integer | yes |  | The record's id |

<details><summary>Response fields</summary>

| Field | Type |
|---|---|
| `id` | integer |
| `guideNo` | string |
| `name` | string |
| `nickname` | string |
| `guideType` | string |
| `nic` | string |
| `contact` | string |
| `email` | string |
| `address` | string |
| `town` | string |
| `active` | boolean |
| `languages` | array of object |
| `languages[].id` | integer |
| `languages[].name` | string |

</details>

**Example - One guide**

```http
GET /api/v1/master-data/guides/1
```

`200 OK`

```json
{
  "id": 1,
  "guideNo": "N-0101",
  "name": "Athula Bandara",
  "nickname": null,
  "guideType": "NATIONAL_GUIDE",
  "nic": "781234567V",
  "contact": "0777490642",
  "email": "athula@example.com",
  "address": null,
  "town": "Matale",
  "active": true,
  "languages": [
    {
      "id": 1,
      "name": "English"
    }
  ]
}
```

<a id="patch-guides-id"></a>

### `PATCH /guides/{id}` - Quick edit: send only the fields that changed

Send null to clear an optional field. Languages are changed with PUT /guides/{id}/languages.

**Who can call it:** ADMIN, MANAGER

| Parameter | In | Type | Required | Default | Notes |
|---|---|---|---|---|---|
| `id` | path | integer | yes |  | The record's id |

**Request body** (`application/json`): a JSON object - the fields are described above and shown in the examples.

<details><summary>Response fields</summary>

| Field | Type |
|---|---|
| `id` | integer |
| `guideNo` | string |
| `name` | string |
| `nickname` | string |
| `guideType` | string |
| `nic` | string |
| `contact` | string |
| `email` | string |
| `address` | string |
| `town` | string |
| `active` | boolean |
| `languages` | array of object |
| `languages[].id` | integer |
| `languages[].name` | string |

</details>

**Example - Quick edit**

```http
PATCH /api/v1/master-data/guides/1
Content-Type: application/json

{
  "town": "Matale",
  "nickname": null
}
```

`200 OK`

```json
{
  "id": 1,
  "guideNo": "N-0101",
  "name": "Athula Bandara",
  "nickname": null,
  "guideType": "NATIONAL_GUIDE",
  "nic": "781234567V",
  "contact": "0777490642",
  "email": "athula@example.com",
  "address": null,
  "town": "Matale",
  "active": true,
  "languages": [
    {
      "id": 1,
      "name": "English"
    },
    {
      "id": 2,
      "name": "German"
    }
  ]
}
```

<a id="delete-guides-id"></a>

### `DELETE /guides/{id}` - Deactivate a guide (soft delete)

Sets active=false; the guide stays in the database. Bring back with {"active": true}.

**Who can call it:** ADMIN, MANAGER

| Parameter | In | Type | Required | Default | Notes |
|---|---|---|---|---|---|
| `id` | path | integer | yes |  | The record's id |

**Example - Deactivate a guide (soft delete)**

```http
DELETE /api/v1/master-data/guides/2
```

`204 No Content`

<a id="put-guides-id-languages"></a>

### `PUT /guides/{id}/languages` - Replace the languages the guide speaks

Send the complete list of language ids (use [] to clear it). The choices come from the 'languages' lookup.

**Who can call it:** ADMIN, MANAGER

| Parameter | In | Type | Required | Default | Notes |
|---|---|---|---|---|---|
| `id` | path | integer | yes |  | The record's id |

**Request body** (`application/json`): a JSON array of integer.

<details><summary>Response fields</summary>

| Field | Type |
|---|---|
| `[].id` | integer |
| `[].name` | string |

</details>

**Example - Replace the languages spoken**

```http
PUT /api/v1/master-data/guides/1/languages
Content-Type: application/json

[1]
```

`200 OK`

```json
[
  {
    "id": 1,
    "name": "English"
  }
]
```

---

## Restaurants

Meal stops, the days each is closed and the dietary needs it can cater for.

| Method | Path | What it does |
|---|---|---|
| `GET` | [`/restaurants`](#get-restaurants) | List restaurants |
| `POST` | [`/restaurants`](#post-restaurants) | Add a restaurant |
| `GET` | [`/restaurants/{id}`](#get-restaurants-id) | One restaurant with its closed days and dietary support |
| `PATCH` | [`/restaurants/{id}`](#patch-restaurants-id) | Quick edit: rename or move a restaurant |
| `DELETE` | [`/restaurants/{id}`](#delete-restaurants-id) | Delete a restaurant |
| `PUT` | [`/restaurants/{id}/closed-days`](#put-restaurants-id-closed-days) | Replace the days the restaurant is closed |
| `PUT` | [`/restaurants/{id}/dietary`](#put-restaurants-id-dietary) | Replace the dietary needs the restaurant can cater for |

<a id="get-restaurants"></a>

### `GET /restaurants` - List restaurants

Each restaurant comes with its closed days (day names, Monday first) and dietary support. Filter with q (name), destinationId, dietaryCategoryId (restaurants that cater for it), closedOn and openOn (a day name such as MONDAY - openOn keeps restaurants NOT closed that day). Sort with sort=name,asc (repeatable); fields: name, destination, id.

**Who can call it:** ADMIN, MANAGER

| Parameter | In | Type | Required | Default | Notes |
|---|---|---|---|---|---|
| `q` | query | string | no |  | Case-insensitive text search |
| `destinationId` | query | integer | no |  |  |
| `dietaryCategoryId` | query | integer | no |  |  |
| `closedOn` | query | string | no |  |  |
| `openOn` | query | string | no |  |  |
| `page` | query | integer | no | `0` | Page number, starting at 0 |
| `size` | query | integer | no | `20` | Rows per page (1 to 200) |
| `sort` | query | array of string | no |  | field,direction - repeatable, e.g. sort=destination,asc&sort=name,asc |

<details><summary>Response fields</summary>

| Field | Type |
|---|---|
| `content` | array of object |
| `content[].id` | integer |
| `content[].name` | string |
| `content[].destination` | object |
| `content[].destination.id` | integer |
| `content[].destination.name` | string |
| `content[].closedDays` | array of string |
| `content[].dietary` | array of object |
| `content[].dietary[].id` | integer |
| `content[].dietary[].name` | string |
| `totalElements` | integer |
| `totalPages` | integer |
| `number` | integer |
| `size` | integer |
| `first` | boolean |
| `last` | boolean |
| `empty` | boolean |

</details>

**Example - Restaurants open on a Monday that cater for halal**

```http
GET /api/v1/master-data/restaurants?openOn=monday&dietaryCategoryId=2
```

`200 OK`

```json
{
  "content": [],
  "totalElements": 0,
  "totalPages": 0,
  "number": 0,
  "size": 20,
  "first": true,
  "last": true,
  "empty": true
}
```

<a id="post-restaurants"></a>

### `POST /restaurants` - Add a restaurant

Required: name (unique within its destination), destinationId. Optional, to set in the same call: closedDays (day names, e.g. ["MONDAY", "TUESDAY"]; any letter case) and dietaryCategoryIds (ids from the 'dietary-categories' lookup).

**Who can call it:** ADMIN, MANAGER

**Request body** (`application/json`): a JSON object - the fields are described above and shown in the examples.

<details><summary>Response fields</summary>

| Field | Type |
|---|---|
| `id` | integer |
| `name` | string |
| `destination` | object |
| `destination.id` | integer |
| `destination.name` | string |
| `closedDays` | array of string |
| `dietary` | array of object |
| `dietary[].id` | integer |
| `dietary[].name` | string |

</details>

**Example - Create a restaurant with closed days and dietary support**

```http
POST /api/v1/master-data/restaurants
Content-Type: application/json

{
  "name": "Kamatha Restaurant",
  "destinationId": 1,
  "closedDays": ["MONDAY"],
  "dietaryCategoryIds": [1]
}
```

`201 Created` - `Location: /api/v1/master-data/restaurants/1`

```json
{
  "id": 1,
  "name": "Kamatha Restaurant",
  "destination": {
    "id": 1,
    "name": "Sigiriya"
  },
  "closedDays": ["MONDAY"],
  "dietary": [
    {
      "id": 1,
      "name": "Vegetarian"
    }
  ]
}
```

<a id="get-restaurants-id"></a>

### `GET /restaurants/{id}` - One restaurant with its closed days and dietary support

**Who can call it:** ADMIN, MANAGER

| Parameter | In | Type | Required | Default | Notes |
|---|---|---|---|---|---|
| `id` | path | integer | yes |  | The record's id |

<details><summary>Response fields</summary>

| Field | Type |
|---|---|
| `id` | integer |
| `name` | string |
| `destination` | object |
| `destination.id` | integer |
| `destination.name` | string |
| `closedDays` | array of string |
| `dietary` | array of object |
| `dietary[].id` | integer |
| `dietary[].name` | string |

</details>

**Example - One restaurant**

```http
GET /api/v1/master-data/restaurants/1
```

`200 OK`

```json
{
  "id": 1,
  "name": "Kamatha Garden Restaurant",
  "destination": {
    "id": 1,
    "name": "Sigiriya"
  },
  "closedDays": ["MONDAY", "TUESDAY"],
  "dietary": [
    {
      "id": 1,
      "name": "Vegetarian"
    },
    {
      "id": 2,
      "name": "Halal"
    }
  ]
}
```

<a id="patch-restaurants-id"></a>

### `PATCH /restaurants/{id}` - Quick edit: rename or move a restaurant

Send name and/or destinationId. Closed days and dietary support have their own calls.

**Who can call it:** ADMIN, MANAGER

| Parameter | In | Type | Required | Default | Notes |
|---|---|---|---|---|---|
| `id` | path | integer | yes |  | The record's id |

**Request body** (`application/json`): a JSON object - the fields are described above and shown in the examples.

<details><summary>Response fields</summary>

| Field | Type |
|---|---|
| `id` | integer |
| `name` | string |
| `destination` | object |
| `destination.id` | integer |
| `destination.name` | string |
| `closedDays` | array of string |
| `dietary` | array of object |
| `dietary[].id` | integer |
| `dietary[].name` | string |

</details>

**Example - Rename or move a restaurant**

```http
PATCH /api/v1/master-data/restaurants/1
Content-Type: application/json

{
  "name": "Kamatha Garden Restaurant"
}
```

`200 OK`

```json
{
  "id": 1,
  "name": "Kamatha Garden Restaurant",
  "destination": {
    "id": 1,
    "name": "Sigiriya"
  },
  "closedDays": ["MONDAY"],
  "dietary": [
    {
      "id": 1,
      "name": "Vegetarian"
    }
  ]
}
```

<a id="delete-restaurants-id"></a>

### `DELETE /restaurants/{id}` - Delete a restaurant

Removes the restaurant together with its closed days and dietary links.

**Who can call it:** ADMIN, MANAGER

| Parameter | In | Type | Required | Default | Notes |
|---|---|---|---|---|---|
| `id` | path | integer | yes |  | The record's id |

**Example - Delete a restaurant**

```http
DELETE /api/v1/master-data/restaurants/2
```

`204 No Content`

<a id="put-restaurants-id-closed-days"></a>

### `PUT /restaurants/{id}/closed-days` - Replace the days the restaurant is closed

Send the complete list of day names (use [] if it is open every day). Returned in week order.

**Who can call it:** ADMIN, MANAGER

| Parameter | In | Type | Required | Default | Notes |
|---|---|---|---|---|---|
| `id` | path | integer | yes |  | The record's id |

**Request body** (`application/json`): a JSON array of string.

**Example - Replace the closed days (day names)**

```http
PUT /api/v1/master-data/restaurants/1/closed-days
Content-Type: application/json

["MONDAY", "TUESDAY"]
```

`200 OK`

```json
["MONDAY", "TUESDAY"]
```

<a id="put-restaurants-id-dietary"></a>

### `PUT /restaurants/{id}/dietary` - Replace the dietary needs the restaurant can cater for

Send the complete list of dietary category ids (use [] to clear it).

**Who can call it:** ADMIN, MANAGER

| Parameter | In | Type | Required | Default | Notes |
|---|---|---|---|---|---|
| `id` | path | integer | yes |  | The record's id |

**Request body** (`application/json`): a JSON array of integer.

<details><summary>Response fields</summary>

| Field | Type |
|---|---|
| `[].id` | integer |
| `[].name` | string |

</details>

**Example - Replace the dietary support**

```http
PUT /api/v1/master-data/restaurants/1/dietary
Content-Type: application/json

[1, 2]
```

`200 OK`

```json
[
  {
    "id": 1,
    "name": "Vegetarian"
  },
  {
    "id": 2,
    "name": "Halal"
  }
]
```

---

## Packages

Site tour packages: a named bundle of attractions with one price.

| Method | Path | What it does |
|---|---|---|
| `GET` | [`/packages`](#get-packages) | List site tour packages |
| `POST` | [`/packages`](#post-packages) | Add a package |
| `GET` | [`/packages/{id}`](#get-packages-id) | One package with its sites and current price |
| `PATCH` | [`/packages/{id}`](#patch-packages-id) | Quick edit: rename a package |
| `DELETE` | [`/packages/{id}`](#delete-packages-id) | Delete a package |
| `PUT` | [`/packages/{id}/sites`](#put-packages-id-sites) | Replace the attractions included in the package |

<a id="get-packages"></a>

### `GET /packages` - List site tour packages

Each package comes with the attractions it includes and its price in force today (or on 'at'). Filter with q (name) and attractionId (packages that include that site). Sort with sort=name,asc (repeatable); fields: name, id.

**Who can call it:** ADMIN, MANAGER

| Parameter | In | Type | Required | Default | Notes |
|---|---|---|---|---|---|
| `q` | query | string | no |  | Case-insensitive text search |
| `attractionId` | query | integer | no |  |  |
| `at` | query | string (date) | no |  | Evaluate as of this date (default today) |
| `page` | query | integer | no | `0` | Page number, starting at 0 |
| `size` | query | integer | no | `20` | Rows per page (1 to 200) |
| `sort` | query | array of string | no |  | field,direction - repeatable, e.g. sort=name,desc |

<details><summary>Response fields</summary>

| Field | Type |
|---|---|
| `content` | array of object |
| `content[].id` | integer |
| `content[].name` | string |
| `content[].sites` | array of object |
| `content[].sites[].id` | integer |
| `content[].sites[].name` | string |
| `content[].currentRate` | object |
| `content[].currentRate.rate` | number |
| `content[].currentRate.unit` | enum: USD / LKR / PERCENT |
| `totalElements` | integer |
| `totalPages` | integer |
| `number` | integer |
| `size` | integer |
| `first` | boolean |
| `last` | boolean |
| `empty` | boolean |

</details>

**Example - Packages that include an attraction**

```http
GET /api/v1/master-data/packages?attractionId=1
```

`200 OK`

```json
{
  "content": [
    {
      "id": 1,
      "name": "Cultural Triangle Two-Rock Day Tour",
      "sites": [
        {
          "id": 3,
          "name": "Pidurangala Rock"
        },
        {
          "id": 1,
          "name": "Sigiriya Rock Fortress (Lion Rock)"
        }
      ],
      "currentRate": null
    }
  ],
  "totalElements": 1,
  "totalPages": 1,
  "number": 0,
  "size": 20,
  "first": true,
  "last": true,
  "empty": false
}
```

<a id="post-packages"></a>

### `POST /packages` - Add a package

Required: name (unique). Optional, to set in the same call: attractionIds (the included sites). Set the price afterwards with PUT /rates/package.

**Who can call it:** ADMIN, MANAGER

**Request body** (`application/json`): a JSON object - the fields are described above and shown in the examples.

<details><summary>Response fields</summary>

| Field | Type |
|---|---|
| `id` | integer |
| `name` | string |
| `sites` | array of object |
| `sites[].id` | integer |
| `sites[].name` | string |
| `currentRate` | object |
| `currentRate.rate` | number |
| `currentRate.unit` | enum: USD / LKR / PERCENT |

</details>

**Example - Create a package with its sites**

```http
POST /api/v1/master-data/packages
Content-Type: application/json

{
  "name": "Cultural Triangle Day Tour",
  "attractionIds": [1]
}
```

`201 Created` - `Location: /api/v1/master-data/packages/1`

```json
{
  "id": 1,
  "name": "Cultural Triangle Day Tour",
  "sites": [
    {
      "id": 1,
      "name": "Sigiriya Rock Fortress (Lion Rock)"
    }
  ],
  "currentRate": null
}
```

<a id="get-packages-id"></a>

### `GET /packages/{id}` - One package with its sites and current price

'at' evaluates the price as of that date (default today).

**Who can call it:** ADMIN, MANAGER

| Parameter | In | Type | Required | Default | Notes |
|---|---|---|---|---|---|
| `id` | path | integer | yes |  | The record's id |
| `at` | query | string (date) | no |  | Evaluate as of this date (default today) |

<details><summary>Response fields</summary>

| Field | Type |
|---|---|
| `id` | integer |
| `name` | string |
| `sites` | array of object |
| `sites[].id` | integer |
| `sites[].name` | string |
| `currentRate` | object |
| `currentRate.rate` | number |
| `currentRate.unit` | enum: USD / LKR / PERCENT |

</details>

**Example - One package with its sites and current price**

```http
GET /api/v1/master-data/packages/1
```

`200 OK`

```json
{
  "id": 1,
  "name": "Cultural Triangle Two-Rock Day Tour",
  "sites": [
    {
      "id": 3,
      "name": "Pidurangala Rock"
    },
    {
      "id": 1,
      "name": "Sigiriya Rock Fortress (Lion Rock)"
    }
  ],
  "currentRate": {
    "rate": 120.00,
    "unit": "USD"
  }
}
```

<a id="patch-packages-id"></a>

### `PATCH /packages/{id}` - Quick edit: rename a package

Send name. The included sites have their own call and the price is a rate.

**Who can call it:** ADMIN, MANAGER

| Parameter | In | Type | Required | Default | Notes |
|---|---|---|---|---|---|
| `id` | path | integer | yes |  | The record's id |

**Request body** (`application/json`): a JSON object - the fields are described above and shown in the examples.

<details><summary>Response fields</summary>

| Field | Type |
|---|---|
| `id` | integer |
| `name` | string |
| `sites` | array of object |
| `sites[].id` | integer |
| `sites[].name` | string |
| `currentRate` | object |
| `currentRate.rate` | number |
| `currentRate.unit` | enum: USD / LKR / PERCENT |

</details>

**Example - Rename a package**

```http
PATCH /api/v1/master-data/packages/1
Content-Type: application/json

{
  "name": "Cultural Triangle Two-Rock Day Tour"
}
```

`200 OK`

```json
{
  "id": 1,
  "name": "Cultural Triangle Two-Rock Day Tour",
  "sites": [
    {
      "id": 3,
      "name": "Pidurangala Rock"
    },
    {
      "id": 1,
      "name": "Sigiriya Rock Fortress (Lion Rock)"
    }
  ],
  "currentRate": null
}
```

<a id="delete-packages-id"></a>

### `DELETE /packages/{id}` - Delete a package

Removes the package and its site links. Refused with 409 once the package has any price history.

**Who can call it:** ADMIN, MANAGER

| Parameter | In | Type | Required | Default | Notes |
|---|---|---|---|---|---|
| `id` | path | integer | yes |  | The record's id |

**Example - Delete a package that was never priced**

```http
DELETE /api/v1/master-data/packages/2
```

`204 No Content`

<a id="put-packages-id-sites"></a>

### `PUT /packages/{id}/sites` - Replace the attractions included in the package

Send the complete list of attraction ids (use [] to clear it). Returned by name.

**Who can call it:** ADMIN, MANAGER

| Parameter | In | Type | Required | Default | Notes |
|---|---|---|---|---|---|
| `id` | path | integer | yes |  | The record's id |

**Request body** (`application/json`): a JSON array of integer.

<details><summary>Response fields</summary>

| Field | Type |
|---|---|
| `[].id` | integer |
| `[].name` | string |

</details>

**Example - Replace the included attractions**

```http
PUT /api/v1/master-data/packages/1/sites
Content-Type: application/json

[1, 3]
```

`200 OK`

```json
[
  {
    "id": 3,
    "name": "Pidurangala Rock"
  },
  {
    "id": 1,
    "name": "Sigiriya Rock Fortress (Lion Rock)"
  }
]
```

---

## Promotions

Discounts with a type, a validity window and optional limits. The amount is a rate.

| Method | Path | What it does |
|---|---|---|
| `GET` | [`/promotions`](#get-promotions) | List promotions |
| `POST` | [`/promotions`](#post-promotions) | Add a promotion |
| `GET` | [`/promotions/{id}`](#get-promotions-id) | One promotion with its status and current value |
| `PATCH` | [`/promotions/{id}`](#patch-promotions-id) | Quick edit: send only the fields that changed |
| `DELETE` | [`/promotions/{id}`](#delete-promotions-id) | Deactivate a promotion |

<a id="get-promotions"></a>

### `GET /promotions` - List promotions

Each promotion comes with a status (INACTIVE, UPCOMING, RUNNING or ENDED) and its value in force today (or on 'at'). Filter with q (name), type, active, validOn (promotions running on that date), destinationId and seasonId. Sort with sort=validFrom,desc (repeatable); fields: name, type, validFrom, validTo, id. Default: newest first.

**Who can call it:** ADMIN, MANAGER

| Parameter | In | Type | Required | Default | Notes |
|---|---|---|---|---|---|
| `q` | query | string | no |  | Case-insensitive text search |
| `type` | query | string | no |  |  |
| `active` | query | boolean | no |  | Keep only active (true) or inactive (false) records |
| `validOn` | query | string (date) | no |  |  |
| `destinationId` | query | integer | no |  |  |
| `seasonId` | query | integer | no |  |  |
| `at` | query | string (date) | no |  | Evaluate as of this date (default today) |
| `page` | query | integer | no | `0` | Page number, starting at 0 |
| `size` | query | integer | no | `20` | Rows per page (1 to 200) |
| `sort` | query | array of string | no |  | field,direction - repeatable, e.g. sort=validFrom,desc&sort=name,asc |

<details><summary>Response fields</summary>

| Field | Type |
|---|---|
| `content` | array of object |
| `content[].id` | integer |
| `content[].name` | string |
| `content[].type` | string |
| `content[].validFrom` | string (date) |
| `content[].validTo` | string (date) |
| `content[].minGroupSize` | integer |
| `content[].destination` | object |
| `content[].destination.id` | integer |
| `content[].destination.name` | string |
| `content[].season` | object |
| `content[].season.id` | integer |
| `content[].season.name` | string |
| `content[].active` | boolean |
| `content[].status` | string |
| `content[].currentValue` | object |
| `content[].currentValue.rate` | number |
| `content[].currentValue.unit` | enum: USD / LKR / PERCENT |
| `totalElements` | integer |
| `totalPages` | integer |
| `number` | integer |
| `size` | integer |
| `first` | boolean |
| `last` | boolean |
| `empty` | boolean |

</details>

**Example - Promotions running today**

```http
GET /api/v1/master-data/promotions?validOn=2026-10-18&active=true
```

`200 OK`

```json
{
  "content": [
    {
      "id": 1,
      "name": "Early Bird 2027",
      "type": "PERCENTAGE",
      "validFrom": "2026-10-01",
      "validTo": "2027-01-01",
      "minGroupSize": 6,
      "destination": null,
      "season": null,
      "active": true,
      "status": "RUNNING",
      "currentValue": null
    }
  ],
  "totalElements": 1,
  "totalPages": 1,
  "number": 0,
  "size": 20,
  "first": true,
  "last": true,
  "empty": false
}
```

<a id="post-promotions"></a>

### `POST /promotions` - Add a promotion

Required: name, type (PERCENTAGE, FIXED_AMOUNT or COMPLIMENTARY), validFrom, validTo (the promotion runs up to, not including, validTo). Optional: minGroupSize, destinationId, seasonId (leave out to apply everywhere), active (default true). Set the value afterwards with PUT /rates/promotion.

**Who can call it:** ADMIN, MANAGER

**Request body** (`application/json`): a JSON object - the fields are described above and shown in the examples.

<details><summary>Response fields</summary>

| Field | Type |
|---|---|
| `id` | integer |
| `name` | string |
| `type` | string |
| `validFrom` | string (date) |
| `validTo` | string (date) |
| `minGroupSize` | integer |
| `destination` | object |
| `destination.id` | integer |
| `destination.name` | string |
| `season` | object |
| `season.id` | integer |
| `season.name` | string |
| `active` | boolean |
| `status` | string |
| `currentValue` | object |
| `currentValue.rate` | number |
| `currentValue.unit` | enum: USD / LKR / PERCENT |

</details>

**Example - Create a percentage promotion**

```http
POST /api/v1/master-data/promotions
Content-Type: application/json

{
  "name": "Early Bird 2027",
  "type": "PERCENTAGE",
  "validFrom": "2026-10-01",
  "validTo": "2027-01-01",
  "minGroupSize": 4
}
```

`201 Created` - `Location: /api/v1/master-data/promotions/1`

```json
{
  "id": 1,
  "name": "Early Bird 2027",
  "type": "PERCENTAGE",
  "validFrom": "2026-10-01",
  "validTo": "2027-01-01",
  "minGroupSize": 4,
  "destination": null,
  "season": null,
  "active": true,
  "status": "RUNNING",
  "currentValue": null
}
```

**Example - Create a fixed-amount promotion for one destination**

```http
POST /api/v1/master-data/promotions
Content-Type: application/json

{
  "name": "Sigiriya Weekend",
  "type": "FIXED_AMOUNT",
  "validFrom": "2026-12-01",
  "validTo": "2027-03-01",
  "destinationId": 1,
  "seasonId": 2
}
```

`201 Created` - `Location: /api/v1/master-data/promotions/2`

```json
{
  "id": 2,
  "name": "Sigiriya Weekend",
  "type": "FIXED_AMOUNT",
  "validFrom": "2026-12-01",
  "validTo": "2027-03-01",
  "minGroupSize": null,
  "destination": {
    "id": 1,
    "name": "Sigiriya"
  },
  "season": {
    "id": 2,
    "name": "Winter"
  },
  "active": true,
  "status": "UPCOMING",
  "currentValue": null
}
```

<a id="get-promotions-id"></a>

### `GET /promotions/{id}` - One promotion with its status and current value

'at' evaluates the status and value as of that date (default today).

**Who can call it:** ADMIN, MANAGER

| Parameter | In | Type | Required | Default | Notes |
|---|---|---|---|---|---|
| `id` | path | integer | yes |  | The record's id |
| `at` | query | string (date) | no |  | Evaluate as of this date (default today) |

<details><summary>Response fields</summary>

| Field | Type |
|---|---|
| `id` | integer |
| `name` | string |
| `type` | string |
| `validFrom` | string (date) |
| `validTo` | string (date) |
| `minGroupSize` | integer |
| `destination` | object |
| `destination.id` | integer |
| `destination.name` | string |
| `season` | object |
| `season.id` | integer |
| `season.name` | string |
| `active` | boolean |
| `status` | string |
| `currentValue` | object |
| `currentValue.rate` | number |
| `currentValue.unit` | enum: USD / LKR / PERCENT |

</details>

**Example - One promotion with its status and current value**

```http
GET /api/v1/master-data/promotions/1
```

`200 OK`

```json
{
  "id": 1,
  "name": "Early Bird 2027",
  "type": "PERCENTAGE",
  "validFrom": "2026-10-01",
  "validTo": "2027-01-01",
  "minGroupSize": 6,
  "destination": null,
  "season": null,
  "active": true,
  "status": "RUNNING",
  "currentValue": {
    "rate": 10.00,
    "unit": "PERCENT"
  }
}
```

<a id="patch-promotions-id"></a>

### `PATCH /promotions/{id}` - Quick edit: send only the fields that changed

Send null for minGroupSize, destinationId or seasonId to clear it. The type cannot change once a value has been set (409 TYPE_LOCKED). Bring back a deactivated promotion with {"active": true}.

**Who can call it:** ADMIN, MANAGER

| Parameter | In | Type | Required | Default | Notes |
|---|---|---|---|---|---|
| `id` | path | integer | yes |  | The record's id |

**Request body** (`application/json`): a JSON object - the fields are described above and shown in the examples.

<details><summary>Response fields</summary>

| Field | Type |
|---|---|
| `id` | integer |
| `name` | string |
| `type` | string |
| `validFrom` | string (date) |
| `validTo` | string (date) |
| `minGroupSize` | integer |
| `destination` | object |
| `destination.id` | integer |
| `destination.name` | string |
| `season` | object |
| `season.id` | integer |
| `season.name` | string |
| `active` | boolean |
| `status` | string |
| `currentValue` | object |
| `currentValue.rate` | number |
| `currentValue.unit` | enum: USD / LKR / PERCENT |

</details>

**Example - Quick edit (null clears a limit)**

```http
PATCH /api/v1/master-data/promotions/1
Content-Type: application/json

{
  "minGroupSize": 6
}
```

`200 OK`

```json
{
  "id": 1,
  "name": "Early Bird 2027",
  "type": "PERCENTAGE",
  "validFrom": "2026-10-01",
  "validTo": "2027-01-01",
  "minGroupSize": 6,
  "destination": null,
  "season": null,
  "active": true,
  "status": "RUNNING",
  "currentValue": null
}
```

<a id="delete-promotions-id"></a>

### `DELETE /promotions/{id}` - Deactivate a promotion

Sets active=false; the promotion and its value history stay.

**Who can call it:** ADMIN, MANAGER

| Parameter | In | Type | Required | Default | Notes |
|---|---|---|---|---|---|
| `id` | path | integer | yes |  | The record's id |

**Example - Deactivate a promotion (soft delete)**

```http
DELETE /api/v1/master-data/promotions/3
```

`204 No Content`

---

## Distances & Articles

Reference text about destinations, plus road distances between them.

| Method | Path | What it does |
|---|---|---|
| `GET` | [`/info-articles`](#get-info-articles) | List info articles |
| `POST` | [`/info-articles`](#post-info-articles) | Add an article |
| `GET` | [`/info-articles/{id}`](#get-info-articles-id) | One article with its full body |
| `PATCH` | [`/info-articles/{id}`](#patch-info-articles-id) | Quick edit: send only the fields that changed |
| `DELETE` | [`/info-articles/{id}`](#delete-info-articles-id) | Delete an article |
| `GET` | [`/location-distances`](#get-location-distances) | List distances between destinations |
| `POST` | [`/location-distances`](#post-location-distances) | Add a distance |
| `GET` | [`/location-distances/between`](#get-location-distances-between) | Every route between two destinations, whichever way round it is stored |
| `GET` | [`/location-distances/{id}`](#get-location-distances-id) | One distance |
| `PATCH` | [`/location-distances/{id}`](#patch-location-distances-id) | Quick edit: send only the fields that changed |
| `DELETE` | [`/location-distances/{id}`](#delete-location-distances-id) | Delete a distance |

<a id="get-info-articles"></a>

### `GET /info-articles` - List info articles

Rows carry a short excerpt and the body length, not the full body (fetch one article for that). Filter with q (searches title and body), category (TRAVEL_TIP, VISA, WEATHER, GENERAL), destinationId (add includeCountryWide=true to also get the articles that apply to the whole country) and countryWide (true = only country-wide, false = only destination-specific). Sort with sort=title,asc (repeatable); fields: title, category, destination, id.

**Who can call it:** ADMIN, MANAGER

| Parameter | In | Type | Required | Default | Notes |
|---|---|---|---|---|---|
| `q` | query | string | no |  | Case-insensitive text search |
| `category` | query | string | no |  |  |
| `destinationId` | query | integer | no |  |  |
| `includeCountryWide` | query | boolean | no | `false` |  |
| `countryWide` | query | boolean | no |  |  |
| `page` | query | integer | no | `0` | Page number, starting at 0 |
| `size` | query | integer | no | `20` | Rows per page (1 to 200) |
| `sort` | query | array of string | no |  | field,direction - repeatable, e.g. sort=category,asc&sort=title,asc |

<details><summary>Response fields</summary>

| Field | Type |
|---|---|
| `content` | array of object |
| `content[].id` | integer |
| `content[].category` | string |
| `content[].destination` | object |
| `content[].destination.id` | integer |
| `content[].destination.name` | string |
| `content[].title` | string |
| `content[].excerpt` | string |
| `content[].bodyLength` | integer |
| `totalElements` | integer |
| `totalPages` | integer |
| `number` | integer |
| `size` | integer |
| `first` | boolean |
| `last` | boolean |
| `empty` | boolean |

</details>

**Example - Articles for a destination plus the country-wide ones**

```http
GET /api/v1/master-data/info-articles?destinationId=1&includeCountryWide=true
```

`200 OK`

```json
{
  "content": [
    {
      "id": 2,
      "category": "VISA",
      "destination": null,
      "title": "Entry requirements",
      "excerpt": "Most visitors need an Electronic Travel Authorisation (ETA) before arrival.",
      "bodyLength": 75
    },
    {
      "id": 1,
      "category": "GENERAL",
      "destination": {
        "id": 1,
        "name": "Sigiriya"
      },
      "title": "Sigiriya Rock Fortress",
      "excerpt": "The 5th-century rock fortress rises 200 m above the jungle plain. Climb early to beat the heat and the crowds.",
      "bodyLength": 119
    }
  ],
  "totalElements": 2,
  "totalPages": 1,
  "number": 0,
  "size": 20,
  "first": true,
  "last": true,
  "empty": false
}
```

<a id="post-info-articles"></a>

### `POST /info-articles` - Add an article

Required: category (TRAVEL_TIP, VISA, WEATHER or GENERAL), title (at most 200 characters), body (plain text, at most 20,000 characters; line breaks are kept). Optional: destinationId - leave it out for an article that applies to the whole country. The same title in the same category for the same destination can only be added once (409 DUPLICATE).

**Who can call it:** ADMIN, MANAGER

**Request body** (`application/json`): a JSON object - the fields are described above and shown in the examples.

<details><summary>Response fields</summary>

| Field | Type |
|---|---|
| `id` | integer |
| `category` | string |
| `destination` | object |
| `destination.id` | integer |
| `destination.name` | string |
| `title` | string |
| `body` | string |

</details>

**Example - Create a destination article**

```http
POST /api/v1/master-data/info-articles
Content-Type: application/json

{
  "category": "GENERAL",
  "destinationId": 1,
  "title": "Sigiriya",
  "body": "SIGIRIYA\nThe 5th-century rock fortress rises 200 m above the jungle plain.\nClimb early to beat the heat and the crowds."
}
```

`201 Created` - `Location: /api/v1/master-data/info-articles/1`

```json
{
  "id": 1,
  "category": "GENERAL",
  "destination": {
    "id": 1,
    "name": "Sigiriya"
  },
  "title": "Sigiriya",
  "body": "SIGIRIYA\nThe 5th-century rock fortress rises 200 m above the jungle plain.\nClimb early to beat the heat and the crowds."
}
```

**Example - Create a country-wide article (no destination)**

```http
POST /api/v1/master-data/info-articles
Content-Type: application/json

{
  "category": "VISA",
  "title": "Entry requirements",
  "body": "Most visitors need an Electronic Travel Authorisation (ETA) before arrival."
}
```

`201 Created` - `Location: /api/v1/master-data/info-articles/2`

```json
{
  "id": 2,
  "category": "VISA",
  "destination": null,
  "title": "Entry requirements",
  "body": "Most visitors need an Electronic Travel Authorisation (ETA) before arrival."
}
```

<a id="get-info-articles-id"></a>

### `GET /info-articles/{id}` - One article with its full body

**Who can call it:** ADMIN, MANAGER

| Parameter | In | Type | Required | Default | Notes |
|---|---|---|---|---|---|
| `id` | path | integer | yes |  | The record's id |

<details><summary>Response fields</summary>

| Field | Type |
|---|---|
| `id` | integer |
| `category` | string |
| `destination` | object |
| `destination.id` | integer |
| `destination.name` | string |
| `title` | string |
| `body` | string |

</details>

**Example - One article with its full body**

```http
GET /api/v1/master-data/info-articles/1
```

`200 OK`

```json
{
  "id": 1,
  "category": "GENERAL",
  "destination": {
    "id": 1,
    "name": "Sigiriya"
  },
  "title": "Sigiriya Rock Fortress",
  "body": "SIGIRIYA\nThe 5th-century rock fortress rises 200 m above the jungle plain.\nClimb early to beat the heat and the crowds."
}
```

<a id="patch-info-articles-id"></a>

### `PATCH /info-articles/{id}` - Quick edit: send only the fields that changed

Send destinationId null to make an article country-wide.

**Who can call it:** ADMIN, MANAGER

| Parameter | In | Type | Required | Default | Notes |
|---|---|---|---|---|---|
| `id` | path | integer | yes |  | The record's id |

**Request body** (`application/json`): a JSON object - the fields are described above and shown in the examples.

<details><summary>Response fields</summary>

| Field | Type |
|---|---|
| `id` | integer |
| `category` | string |
| `destination` | object |
| `destination.id` | integer |
| `destination.name` | string |
| `title` | string |
| `body` | string |

</details>

**Example - Quick edit**

```http
PATCH /api/v1/master-data/info-articles/1
Content-Type: application/json

{
  "title": "Sigiriya Rock Fortress"
}
```

`200 OK`

```json
{
  "id": 1,
  "category": "GENERAL",
  "destination": {
    "id": 1,
    "name": "Sigiriya"
  },
  "title": "Sigiriya Rock Fortress",
  "body": "SIGIRIYA\nThe 5th-century rock fortress rises 200 m above the jungle plain.\nClimb early to beat the heat and the crowds."
}
```

<a id="delete-info-articles-id"></a>

### `DELETE /info-articles/{id}` - Delete an article

**Who can call it:** ADMIN, MANAGER

| Parameter | In | Type | Required | Default | Notes |
|---|---|---|---|---|---|
| `id` | path | integer | yes |  | The record's id |

**Example - Delete an article**

```http
DELETE /api/v1/master-data/info-articles/3
```

`204 No Content`

<a id="get-location-distances"></a>

### `GET /location-distances` - List distances between destinations

Filter with q (either destination's name), destinationId (either end), fromDestinationId, toDestinationId, routeType and needsReview=true (suspect rows: a destination to itself, or a distance that is not above zero). Sort with sort=distanceKm,desc (repeatable); fields: from, to, routeType, distanceKm, durationMinutes, id. To ask "how far is A from B" use /location-distances/between, because most pairs are stored in one direction only.

**Who can call it:** ADMIN, MANAGER

| Parameter | In | Type | Required | Default | Notes |
|---|---|---|---|---|---|
| `q` | query | string | no |  | Case-insensitive text search |
| `fromDestinationId` | query | integer | no |  |  |
| `toDestinationId` | query | integer | no |  |  |
| `destinationId` | query | integer | no |  |  |
| `routeType` | query | string | no |  |  |
| `needsReview` | query | boolean | no |  |  |
| `page` | query | integer | no | `0` | Page number, starting at 0 |
| `size` | query | integer | no | `20` | Rows per page (1 to 200) |
| `sort` | query | array of string | no |  | field,direction - repeatable, e.g. sort=from,asc&sort=distanceKm,asc |

<details><summary>Response fields</summary>

| Field | Type |
|---|---|
| `content` | array of object |
| `content[].id` | integer |
| `content[].from` | object |
| `content[].from.id` | integer |
| `content[].from.name` | string |
| `content[].to` | object |
| `content[].to.id` | integer |
| `content[].to.name` | string |
| `content[].routeType` | string |
| `content[].distanceKm` | number |
| `content[].durationMinutes` | integer |
| `content[].needsReview` | boolean |
| `totalElements` | integer |
| `totalPages` | integer |
| `number` | integer |
| `size` | integer |
| `first` | boolean |
| `last` | boolean |
| `empty` | boolean |

</details>

**Example - Distances from or to a destination**

```http
GET /api/v1/master-data/location-distances?destinationId=1&sort=distanceKm,asc
```

`200 OK`

```json
{
  "content": [
    {
      "id": 2,
      "from": {
        "id": 1,
        "name": "Sigiriya"
      },
      "to": {
        "id": 2,
        "name": "Kandy"
      },
      "routeType": "ROAD",
      "distanceKm": 90.0,
      "durationMinutes": null,
      "needsReview": false
    },
    {
      "id": 1,
      "from": {
        "id": 4,
        "name": "Colombo"
      },
      "to": {
        "id": 1,
        "name": "Sigiriya"
      },
      "routeType": "ROAD",
      "distanceKm": 176.2,
      "durationMinutes": null,
      "needsReview": false
    }
  ],
  "totalElements": 2,
  "totalPages": 1,
  "number": 0,
  "size": 20,
  "first": true,
  "last": true,
  "empty": false
}
```

<a id="post-location-distances"></a>

### `POST /location-distances` - Add a distance

Required: fromDestinationId, toDestinationId (different), routeType (e.g. ROAD, ROADA, ROADB), distanceKm (above zero, one decimal place). Optional: durationMinutes. The same two destinations with the same route type, in either direction, can only be recorded once (409 DUPLICATE).

**Who can call it:** ADMIN, MANAGER

**Request body** (`application/json`): a JSON object - the fields are described above and shown in the examples.

<details><summary>Response fields</summary>

| Field | Type |
|---|---|
| `id` | integer |
| `from` | object |
| `from.id` | integer |
| `from.name` | string |
| `to` | object |
| `to.id` | integer |
| `to.name` | string |
| `routeType` | string |
| `distanceKm` | number |
| `durationMinutes` | integer |
| `needsReview` | boolean |

</details>

**Example - Add a distance**

```http
POST /api/v1/master-data/location-distances
Content-Type: application/json

{
  "fromDestinationId": 4,
  "toDestinationId": 1,
  "routeType": "ROAD",
  "distanceKm": "175.5",
  "durationMinutes": 270
}
```

`201 Created` - `Location: /api/v1/master-data/location-distances/1`

```json
{
  "id": 1,
  "from": {
    "id": 4,
    "name": "Colombo"
  },
  "to": {
    "id": 1,
    "name": "Sigiriya"
  },
  "routeType": "ROAD",
  "distanceKm": 175.5,
  "durationMinutes": 270,
  "needsReview": false
}
```

**Example - Rejected: the same route is already recorded (either direction)**

```http
POST /api/v1/master-data/location-distances
Content-Type: application/json

{
  "fromDestinationId": 1,
  "toDestinationId": 4,
  "routeType": "ROAD",
  "distanceKm": 176
}
```

`409 Conflict`

```json
{
  "detail": "These destinations already have a ROAD route (id 1), in one direction or the other; edit that one instead",
  "instance": "/api/v1/master-data/location-distances",
  "status": 409,
  "title": "Conflict",
  "code": "DUPLICATE"
}
```

<a id="get-location-distances-between"></a>

### `GET /location-distances/between` - Every route between two destinations, whichever way round it is stored

Returns each route option (ROAD, ROADA, ROADB ...) shortest first, with from/to as stored. An empty list means no distance is recorded for the pair.

**Who can call it:** ADMIN, MANAGER

| Parameter | In | Type | Required | Default | Notes |
|---|---|---|---|---|---|
| `fromDestinationId` | query | integer | yes |  |  |
| `toDestinationId` | query | integer | yes |  |  |

<details><summary>Response fields</summary>

| Field | Type |
|---|---|
| `[].id` | integer |
| `[].from` | object |
| `[].from.id` | integer |
| `[].from.name` | string |
| `[].to` | object |
| `[].to.id` | integer |
| `[].to.name` | string |
| `[].routeType` | string |
| `[].distanceKm` | number |
| `[].durationMinutes` | integer |
| `[].needsReview` | boolean |

</details>

**Example - Every route between two destinations, whichever way it is stored**

```http
GET /api/v1/master-data/location-distances/between?fromDestinationId=1&toDestinationId=4
```

`200 OK`

```json
[
  {
    "id": 1,
    "from": {
      "id": 4,
      "name": "Colombo"
    },
    "to": {
      "id": 1,
      "name": "Sigiriya"
    },
    "routeType": "ROAD",
    "distanceKm": 176.2,
    "durationMinutes": null,
    "needsReview": false
  }
]
```

<a id="get-location-distances-id"></a>

### `GET /location-distances/{id}` - One distance

**Who can call it:** ADMIN, MANAGER

| Parameter | In | Type | Required | Default | Notes |
|---|---|---|---|---|---|
| `id` | path | integer | yes |  | The record's id |

<details><summary>Response fields</summary>

| Field | Type |
|---|---|
| `id` | integer |
| `from` | object |
| `from.id` | integer |
| `from.name` | string |
| `to` | object |
| `to.id` | integer |
| `to.name` | string |
| `routeType` | string |
| `distanceKm` | number |
| `durationMinutes` | integer |
| `needsReview` | boolean |

</details>

**Example - One distance**

```http
GET /api/v1/master-data/location-distances/1
```

`200 OK`

```json
{
  "id": 1,
  "from": {
    "id": 4,
    "name": "Colombo"
  },
  "to": {
    "id": 1,
    "name": "Sigiriya"
  },
  "routeType": "ROAD",
  "distanceKm": 176.2,
  "durationMinutes": null,
  "needsReview": false
}
```

<a id="patch-location-distances-id"></a>

### `PATCH /location-distances/{id}` - Quick edit: send only the fields that changed

Send null for durationMinutes to clear it. The row as it will look must be valid, so fixing a row flagged needsReview means sending the corrected field(s) in the same call.

**Who can call it:** ADMIN, MANAGER

| Parameter | In | Type | Required | Default | Notes |
|---|---|---|---|---|---|
| `id` | path | integer | yes |  | The record's id |

**Request body** (`application/json`): a JSON object - the fields are described above and shown in the examples.

<details><summary>Response fields</summary>

| Field | Type |
|---|---|
| `id` | integer |
| `from` | object |
| `from.id` | integer |
| `from.name` | string |
| `to` | object |
| `to.id` | integer |
| `to.name` | string |
| `routeType` | string |
| `distanceKm` | number |
| `durationMinutes` | integer |
| `needsReview` | boolean |

</details>

**Example - Quick edit**

```http
PATCH /api/v1/master-data/location-distances/1
Content-Type: application/json

{
  "distanceKm": 176.2,
  "durationMinutes": null
}
```

`200 OK`

```json
{
  "id": 1,
  "from": {
    "id": 4,
    "name": "Colombo"
  },
  "to": {
    "id": 1,
    "name": "Sigiriya"
  },
  "routeType": "ROAD",
  "distanceKm": 176.2,
  "durationMinutes": null,
  "needsReview": false
}
```

<a id="delete-location-distances-id"></a>

### `DELETE /location-distances/{id}` - Delete a distance

**Who can call it:** ADMIN, MANAGER

| Parameter | In | Type | Required | Default | Notes |
|---|---|---|---|---|---|
| `id` | path | integer | yes |  | The record's id |

**Example - Delete a distance**

```http
DELETE /api/v1/master-data/location-distances/3
```

`204 No Content`

---

## Operations

Alerts, import and export.

| Method | Path | What it does |
|---|---|---|
| `GET` | [`/alerts`](#get-alerts) | Things needing attention, most urgent first |
| `GET` | [`/alerts/summary`](#get-alerts-summary) | Alert counts by severity and by code |

<a id="get-alerts"></a>

### `GET /alerts` - Things needing attention, most urgent first

Worked out from the current data on every call. Covers: vehicle insurance and revenue licence expiry (VEHICLE_INSURANCE_EXPIRING, VEHICLE_LICENSE_EXPIRING), vehicles with no or an inactive driver (VEHICLE_NO_DRIVER, VEHICLE_DRIVER_INACTIVE, VEHICLE_ASSISTANT_INACTIVE), rates that run out with nothing after them (RATE_ENDING_SOON), and active properties and attractions with no rate in force today (PROPERTY_NO_CURRENT_RATE, ATTRACTION_NO_CURRENT_RATE). Severity: CRITICAL = already expired, WARNING = within 7 days or a missing driver/rate, INFO = further ahead. withinDays (default 30) is how far ahead a coming expiry counts; anything already expired always counts. Filter with code and severity.

**Who can call it:** ADMIN, MANAGER

| Parameter | In | Type | Required | Default | Notes |
|---|---|---|---|---|---|
| `withinDays` | query | integer | no |  |  |
| `code` | query | string | no |  |  |
| `severity` | query | string | no |  |  |
| `page` | query | integer | no | `0` | Page number, starting at 0 |
| `size` | query | integer | no | `50` | Rows per page (1 to 200) |

<details><summary>Response fields</summary>

| Field | Type |
|---|---|
| `content` | array of object |
| `content[].code` | string |
| `content[].severity` | string |
| `content[].subjectType` | string |
| `content[].subject` | object |
| `content[].subject.id` | integer |
| `content[].subject.name` | string |
| `content[].dueDate` | string (date) |
| `content[].daysLeft` | integer |
| `content[].message` | string |
| `totalElements` | integer |
| `totalPages` | integer |
| `number` | integer |
| `size` | integer |
| `first` | boolean |
| `last` | boolean |
| `empty` | boolean |

</details>

**Example - Things needing attention (next 30 days)**

```http
GET /api/v1/master-data/alerts?size=10
```

`200 OK`

```json
{
  "content": [
    {
      "code": "VEHICLE_INSURANCE_EXPIRING",
      "severity": "CRITICAL",
      "subjectType": "vehicle",
      "subject": {
        "id": 2,
        "name": "WP-9999"
      },
      "dueDate": "2026-10-01",
      "daysLeft": -17,
      "message": "Insurance of WP-9999 expired 17 days ago (2026-10-01)"
    },
    {
      "code": "PROPERTY_NO_CURRENT_RATE",
      "severity": "WARNING",
      "subjectType": "property",
      "subject": {
        "id": 2,
        "name": "Kandy Hills Resort"
      },
      "dueDate": null,
      "daysLeft": null,
      "message": "Kandy Hills Resort has no room rate in force today"
    },
    {
      "code": "ATTRACTION_NO_CURRENT_RATE",
      "severity": "WARNING",
      "subjectType": "attraction",
      "subject": {
        "id": 3,
        "name": "Pidurangala Rock"
      },
      "dueDate": null,
      "daysLeft": null,
      "message": "Pidurangala Rock has no rate in force today"
    },
    {
      "code": "VEHICLE_NO_DRIVER",
      "severity": "WARNING",
      "subjectType": "vehicle",
      "subject": {
        "id": 2,
        "name": "WP-9999"
      },
      "dueDate": null,
      "daysLeft": null,
      "message": "WP-9999 has no driver assigned"
    },
    {
      "code": "ATTRACTION_NO_CURRENT_RATE",
      "severity": "WARNING",
      "subjectType": "attraction",
      "subject": {
        "id": 2,
        "name": "Yala Jeep Safari"
      },
      "dueDate": null,
      "daysLeft": null,
      "message": "Yala Jeep Safari has no rate in force today"
    },
    {
      "code": "RATE_ENDING_SOON",
      "severity": "INFO",
      "subjectType": "rate:package",
      "subject": {
        "id": 1,
        "name": "Site tour package rates: Cultural Triangle Two-Rock Day Tour"
      },
      "dueDate": "2026-10-30",
      "daysLeft": 12,
      "message": "Site tour package rates (Cultural Triangle Two-Rock Day Tour) ends in 12 days (2026-10-30) and no later rate is set"
    },
    {
      "code": "VEHICLE_INSURANCE_EXPIRING",
      "severity": "INFO",
      "subjectType": "vehicle",
      "subject": {
        "id": 1,
        "name": "CAB-1234"
      },
      "dueDate": "2026-11-02",
      "daysLeft": 15,
      "message": "Insurance of CAB-1234 expires in 15 days (2026-11-02)"
    }
  ],
  "totalElements": 7,
  "totalPages": 1,
  "number": 0,
  "size": 10,
  "first": true,
  "last": true,
  "empty": false
}
```

**Example - Only one kind of alert**

```http
GET /api/v1/master-data/alerts?code=RATE_ENDING_SOON
```

`200 OK`

```json
{
  "content": [
    {
      "code": "RATE_ENDING_SOON",
      "severity": "INFO",
      "subjectType": "rate:package",
      "subject": {
        "id": 1,
        "name": "Site tour package rates: Cultural Triangle Two-Rock Day Tour"
      },
      "dueDate": "2026-10-30",
      "daysLeft": 12,
      "message": "Site tour package rates (Cultural Triangle Two-Rock Day Tour) ends in 12 days (2026-10-30) and no later rate is set"
    }
  ],
  "totalElements": 1,
  "totalPages": 1,
  "number": 0,
  "size": 50,
  "first": true,
  "last": true,
  "empty": false
}
```

<a id="get-alerts-summary"></a>

### `GET /alerts/summary` - Alert counts by severity and by code

For a dashboard badge. Every severity and code is present, with 0 when there are none.

**Who can call it:** ADMIN, MANAGER

| Parameter | In | Type | Required | Default | Notes |
|---|---|---|---|---|---|
| `withinDays` | query | integer | no |  |  |

<details><summary>Response fields</summary>

| Field | Type |
|---|---|
| `total` | integer |
| `bySeverity` | object |
| `byCode` | object |

</details>

**Example - Counts for a dashboard badge**

```http
GET /api/v1/master-data/alerts/summary
```

`200 OK`

```json
{
  "total": 7,
  "bySeverity": {
    "CRITICAL": 1,
    "WARNING": 4,
    "INFO": 2
  },
  "byCode": {
    "VEHICLE_INSURANCE_EXPIRING": 2,
    "VEHICLE_LICENSE_EXPIRING": 0,
    "VEHICLE_NO_DRIVER": 1,
    "VEHICLE_DRIVER_INACTIVE": 0,
    "VEHICLE_ASSISTANT_INACTIVE": 0,
    "RATE_ENDING_SOON": 1,
    "PROPERTY_NO_CURRENT_RATE": 1,
    "ATTRACTION_NO_CURRENT_RATE": 2
  }
}
```
