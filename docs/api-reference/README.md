# Maintaining the API reference

`docs/API-REFERENCE.md` and `docs/API-REFERENCE.html` are **generated** - do not edit either by hand.

- **`API-REFERENCE.html`** is the one to read: a single self-contained page (no internet needed) with a side rail of areas
  and endpoints, search (press `/`), collapsible field lists, highlighted samples with copy buttons, and light and dark themes.
- **`API-REFERENCE.md`** is the same content as plain text, for diffs in code review and for viewing on GitHub.

Both come from the same model, so they always say the same thing.

## What it is built from

| Part | Where it comes from | You change it by |
|---|---|---|
| Endpoints, parameters and their types, "what it does", request/response field lists | The controllers (`@Operation`, `@Parameter`, `@RequestParam`, DTO records), read from the running app's OpenAPI document | Editing the controller |
| "Who can call it" | `@PreAuthorize` on the method or controller | Editing the annotation |
| Sample requests and responses | The scripted scenario in `src/test/java/.../docs/ApiReferenceTest.java`, which calls every endpoint for real against an in-memory database with a fixed clock | Editing the scenario |
| Conventions, prices, error table at the top | `docs/api-reference/intro.md` | Editing that file |
| Look and feel of the HTML page | `src/test/resources/api-reference/style.css` and `script.js`; the page layout is `ApiReferenceHtml.java` | Editing those files |

Because the samples are *real responses*, they cannot drift from the code.

## It cannot go stale

`ApiReferenceTest` runs with the normal test suite. It regenerates both files in memory and **fails if either differs from the committed file**, and it also fails if

- an endpoint exists that has no sample (every operation needs at least one `.doc()` call), or
- a sample refers to an endpoint that no longer exists.

## When you change the API

1. Change the controller (and the scenario if you added an endpoint: add a `call(...).title("...").expect(...).doc()` for it).
2. Regenerate:

   ```
   mvnw test -Dtest=ApiReferenceTest -Dapi.docs.write=true
   ```
3. Read the diff of `docs/API-REFERENCE.md` (the HTML changes with it), check it says what you intended, and commit both with the code.

## Adding a sample

```java
call("POST", "/promotions")                    // method and the path as the controller declares it
        .title("Create a percentage promotion") // shown in the document
        .body(obj("name", "Early Bird", "type", "PERCENTAGE", "validFrom", "2026-10-01", "validTo", "2027-01-01"))
        .expect(201)                            // the call fails the test if the status differs
        .doc();                                 // record it (use .seed() to just set data up without recording)
```

Use the path **template** (`/rates/{family}`, with the value passed after it), not a concrete path. Keep samples small and realistic; the date in the story is fixed at 18 Oct 2026.
