# Book Metadata Scraping Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an optional book metadata lookup flow that searches by ISBN or title, lets the user choose a candidate, downloads its cover locally, and fills the book editor without overwriting reading or library-location data.

**Architecture:** A focused `BookMetadataClient` reads Douban Book's public search and detail pages and maps their unstable structures into a small app-owned `BookMetadata` model. `LibraryViewModel` owns search state, cancellation, and validated JPEG cover storage. A Compose child route shows candidates and returns a `BookMetadataSelection` to `BookEditor` for preview and merge.

**Tech Stack:** Kotlin, Jetpack Compose Navigation, Retrofit/Gson, Room migration 1→2, coroutines, JUnit, MockWebServer, Robolectric.

**Spec:** User-approved design in the current task: ISBN exact search and title fuzzy search, candidate child page, explicit fill then local save, offline manual entry remains available.

## Global Constraints

- Existing manual book entry must work without a network connection.
- Never send library location, reading status, current page, notes, cards, or database IDs to the metadata service.
- Search requests contain only the user-entered ISBN or title.
- Download a selected cover to app-private storage before filling the editor.
- Existing Room data must survive migration from version 1 to version 2.
- The user reviews the filled editor and explicitly saves; lookup never writes a book directly.

---

### Task 1: Metadata model, mapping, and network client

**Files:**
- Create: `app/src/main/java/com/guanjian/app/books/BookMetadataClient.kt`
- Create: `app/src/test/java/com/guanjian/app/books/BookMetadataClientTest.kt`

**Interfaces:**
- Produces: `BookMetadata`, `BookMetadataResult`, `BookMetadataClient.search(query)` and `BookMetadataClient.downloadCover(url)`.

- [ ] Write MockWebServer tests for title mapping, ISBN request selection, empty results, HTTP failure, and cover bytes.
- [ ] Run the focused test and confirm missing types fail compilation.
- [x] Read candidates from Douban Book search-page JSON, fetch at most five public detail pages, and use a desktop User-Agent so complete publication fields are present.
- [ ] Run the focused test until all cases pass.

### Task 2: Room metadata fields and lossless migration

**Files:**
- Modify: `app/src/main/java/com/guanjian/app/data/Entities.kt`
- Modify: `app/src/main/java/com/guanjian/app/data/LibraryDatabase.kt`
- Create: `app/src/androidTest/java/com/guanjian/app/data/LibraryMigrationTest.kt`
- Update generated schema: `app/schemas/com.guanjian.app.data.LibraryDatabase/2.json`

**Interfaces:**
- Produces: `BookEntity.isbn`, `publisher`, `publishedDate`, `description`; `MIGRATION_1_2`.

- [ ] Add a migration test that opens a version-1 database and verifies old title/location values after migration.
- [ ] Add nullable-safe text columns with empty-string defaults and migration SQL using the same defaults.
- [ ] Register `MIGRATION_1_2` in `LibraryDatabase.create`.
- [ ] Compile and verify Room schema export version 2.

### Task 3: Search state and candidate child page

**Files:**
- Modify: `app/src/main/java/com/guanjian/app/ui/LibraryViewModel.kt`
- Create: `app/src/main/java/com/guanjian/app/ui/BookLookupScreen.kt`
- Modify: `app/src/main/java/com/guanjian/app/ui/LibraryApp.kt`
- Create: `app/src/test/java/com/guanjian/app/ui/BookLookupViewModelTest.kt`

**Interfaces:**
- Produces: `BookLookupUiState`, `searchBooks(query)`, `cancelBookSearch()`, child route `book-lookup`.

- [ ] Test that search is explicit, a new search cancels the old one, success/empty/error states are readable, and no local book is saved.
- [ ] Implement ViewModel state and cancellation with injected metadata search function.
- [ ] Build a normal child page with query field, search button, loading state, retryable error, empty state, and candidate cards.
- [ ] Add the route and ensure back navigation cancels only the current lookup.

### Task 4: Fill editor and localize cover

**Files:**
- Modify: `app/src/main/java/com/guanjian/app/ui/BooksScreen.kt`
- Modify: `app/src/main/java/com/guanjian/app/ui/LibraryApp.kt`
- Create: `app/src/test/java/com/guanjian/app/ui/BookMetadataMergeTest.kt`

**Interfaces:**
- Produces: `BookMetadataSelection(metadata, overwriteExisting)`, `mergeBookMetadata(book, selection)`.

- [ ] Test default fill-only-empty behavior, optional overwrite behavior, and preservation of location/status/current page/note/id/timestamps.
- [ ] Add ISBN, publisher, publication date, and description editor fields and show metadata in book details.
- [ ] Add “搜索书籍信息” from the editor and an overwrite switch on the candidate page.
- [x] Download the selected cover on an IO dispatcher, decode and re-encode it as a bounded JPEG under `filesDir/covers`, then return the selection to the editor.
- [ ] Keep the editor dirty until the user presses its existing save button.

### Task 5: Documentation and verification

**Files:**
- Modify: `完成.md`
- Modify: `项目说明.md` if its data model section lists book fields.

- [ ] Document the lookup scene, source, privacy boundary, file map, editable fields, and failure behavior for beginners.
- [ ] Run all JVM unit tests and assemble the Debug APK from an ASCII build copy.
- [ ] Inspect the Room v2 schema, final APK, test XML counts, and searches for accidental location/ID transmission.
