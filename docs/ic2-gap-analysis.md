# SF Cloud vs Illuminated Cloud 2 — gap analysis

Snapshot: 2026-09-17, SF Cloud 0.1.0 (~10.5k lines of Kotlin). IC2 capabilities come from the IC2 2.4 plugin
descriptors and compiled UI classes; SF Cloud capabilities were checked against `plugin.xml` and the sources.

Legend: **Done** — on par for everyday use. **Partial** — exists, but thinner than IC2. **Missing** — not implemented.

## Covered

| Area | Status | Notes |
| --- | --- | --- |
| Tool windows | Done | Anonymous Apex, SOQL Query, SOSL Query, Log Analyzer and the SF Cloud Problems view, with IC2's layout, toolbars and shortcuts |
| Connections | Done | Status bar widget with Project / Recent / Global sections, connection combo everywhere, OAuth authorization dialog, creating and deleting scratch orgs |
| Deploy / retrieve / delete scope dialog | Done | Local Only / Local + Server / Server Only tree, cached org metadata, Retrieve for Merge, check-only, test levels, purge on delete |
| Compare with server | Done | Current org and any other org |
| Problems view | Done | Per-connection tabs, failure groups, navigation |
| Log Analyzer | Done | Log list, levels, Raw and Tree views, 12 tree views, callers / callees, Configure Logging (debug levels and trace flags) |
| Anonymous Apex | Done | SOAP execution with log levels, result log in Raw / Tree views; runs from a selection in any file, from `.apex` files through the gutter and from Alt+Enter on a selection in Apex |
| SOQL / SOSL | Done | Table / Tree results, console, export, explain, copy, Tooling API, Query All, validation; runs from `.soql` / `.sosl` files, from inline `[SELECT …]` / `[FIND …]` in Apex (gutter and Alt+Enter), from query string literals in any file and from a selection, routing `FIND` to SOSL; queries with Apex binds open without executing |
| SOQL / SOSL completion | Done | Objects after `FROM` / `RETURNING`, fields and parent relationships of the queried object in `SELECT`, `WHERE`, `ORDER BY`, `GROUP BY`, relationship paths (`Owner.Profile.`), child relationships in parent-child subqueries, semi-join subqueries, SOSL `RETURNING Object(…)` field lists; in `.soql` / `.sosl` files, the query tool windows and inline Apex SOQL, backed by the offline symbol table and local `objects/` metadata |
| Apex Unit Tests run configuration | Partial | All Tests, Changed Only (VCS changes plus a name-based dependency scan), test tree, connection, log levels, per-test logs; no test suites, no rerun-failed action, no "Run Dependent Tests" gutter action |
| Code coverage | Done | Coverage only from Run with Coverage, the window opens when that run finishes, line highlighting, and a **Code Coverage** tool window listing all project Apex files with per-file percentage, project total and org-wide coverage |
| Apex editing | Partial | Structure view, method navigation; refactorings come from Salesforce's language server, completion from the server or from SF Cloud's own index when the server is down |
| Offline symbol table | Partial | Generated from the org: the Apex system library through the Tooling API completions endpoint and all SObjects through composite `describe`; feeds SF Cloud's offline resolver, Apex / SOQL / LWC `@salesforce/schema` completion, Quick Documentation and Parameter Info, alongside the language server where one runs. System property types are not published by the endpoint, so properties are typed `Object`; managed-package classes are not included yet |
| Apex navigation and usages | Done | Offline reference resolution and Find Usages over Apex, LWC `@salesforce/apex` imports and metadata XML, with usage counts above declarations |
| LWC import navigation | Done | `c/*` modules resolve through WebStorm's JavaScript support, so Ctrl+Click reaches the component script and the exported functions, constants and inherited members; `@salesforce/*` imports and their bindings jump to the Apex method, custom label, object or field, static resource, message channel or custom permission |
| Bundle file tabs | Done | LWC and Aura bundles plus source / `-meta.xml` pairs get IC2's tabs under the editor, each bundle held in one editor tab whose content the tabs swap |
| Live templates | Done | Apex and LWC abbreviation sets (`sd`, `soql`, `tm`, `wire`, `lfor`, …) with their own Apex / LWC JavaScript / LWC template contexts |
| New-file wizards | Done | New \| Salesforce: Apex class / test class / trigger, LWC, Aura, Visualforce page and component, each with its `-meta.xml` |

## Remaining gaps

1. **Apex debugging.** IC2 has the Apex offline (log replay) debugger, checkpoints and aer executors. SF Cloud has
   no debugger, so the Log Analyzer has no Debug / Register Checkpoints actions and Anonymous Apex has no Debug action.
2. **Real Apex PSI.** IC2's own parser drives inspections, intentions, formatter, type
   hierarchy and offline completion. SF Cloud resolves declarations, members and usages from its own lexer-based
   structure index, but completion, inspections, the formatter and rename still come from the language server.
3. **Static resource wizard.** IC2's New Static Resource dialog (content type, zip or single file).
4. **Visualforce and Aura support** beyond bundle file tabs, and `-meta.xml` completion.
5. **Connection management tab.** Removing stale authorizations.
6. **Metadata subscription.** IC2's per-module subscription (Subscribed Only / Subscribed by Parent filters); SF Cloud
   works with SFDX package directories instead.
7. **SOQL "Open as GraphQL"** action.
8. **Test runner extras.** Test suites, rerun failed tests, dependent-test gutter actions, asynchronous run policies.
