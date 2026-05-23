# Maven MVP

Prototype demonstrating a mixed Maven + Tycho build using **Tycho CI Friendly Versions**.

## Layout

```
.
├── .mvn/extensions.xml  # enables the tycho-build Maven extension (required)
├── pom.xml              # aggregator, extends tycho-parent
├── parent-pom/          # 1st parent — Maven CI Friendly versioning
├── maven-lib/           # plain Maven jar, also an OSGi bundle (via bnd-maven-plugin)
├── maven-app/           # plain Maven jar, depends on maven-lib
├── tycho-parent/        # 2nd parent — inherits parent-pom, configures Tycho 4.0
├── target-platform/     # eclipse-target-definition: .target file w/ Eclipse + maven-lib
├── rcp-plugin/          # POMLESS eclipse-plugin — META-INF/MANIFEST.MF + plugin.xml + sources
└── rcp-product/         # POMLESS eclipse-repository — only .product + category.xml
```

The two Eclipse modules are **pomless**: `tycho-build` (loaded as a Maven extension via `.mvn/extensions.xml`) detects them by their `META-INF/MANIFEST.MF` and `*.product` files and synthesizes their poms at runtime. The synthesized poms inherit from the aggregator (which inherits from `tycho-parent`), so Tycho config flows through.

## Version model

There are two independent version sources:

| Where                | Driven by                       | Default     | How to change                          |
|----------------------|---------------------------------|-------------|----------------------------------------|
| **Maven** side (pom) | `${releaseVersion}${qualifier}` | `1.0.0-SNAPSHOT` | `-DreleaseVersion=…`, `-Dqualifier=…`, or `set-version` |
| **OSGi** side (bundles/product) | `Bundle-Version` in MANIFEST.MF, `version=` in .product | `1.0.0.qualifier` | `set-version` only                  |

`.qualifier` is replaced at build time with a timestamp (`yyyyMMdd-HHmm`). On snapshot builds the resulting jar is marked SNAPSHOT; on release builds (`-Dqualifier=`) it isn't.

## Build commands

### Default snapshot build

```
mvn clean install
```

Produces `1.0.0-SNAPSHOT` (Maven) / `1.0.0.<timestamp>` (OSGi, SNAPSHOT-marked).

### Release at the same OSGi version

```
mvn clean install -Dqualifier=
```

Produces `1.0.0` (Maven) / `1.0.0.<timestamp>` (OSGi, release). No file edits.

### Bump to a new version (e.g. 2.0.0)

The OSGi side can't be overridden by a Maven property — `Bundle-Version` is a literal in MANIFEST.MF. Use `tycho-versions:set-version` to update all three independent sources of truth (Maven property, MANIFEST, .product) atomically:

```
mvn validate org.eclipse.tycho:tycho-versions-plugin:4.0.8:set-version -DnewVersion=2.0.0-SNAPSHOT
mvn clean install                         # 2.0.0-SNAPSHOT everywhere
mvn clean install -Dqualifier=            # 2.0.0 release
```

The `validate` phase runs a `gmavenplus` script that **auto-discovers** the artifactId list `set-version` needs to walk (the aggregator + every `Bundle-SymbolicName` and `.product` `uid` found under the scan roots). No hand-edited list to keep in sync when modules come and go.

Where it scans is controlled by `${rcp.discovery.dirs}` (defaults to `rcp-*`). Override per-build with `-Drcp.discovery.dirs=…` or set it in the aggregator pom. Comma-separated patterns:

| Pattern | Meaning |
|---|---|
| `rcp-*` | direct sibling directories matching `rcp-*` (default) |
| `bundles/*,products/*` | one level under each parent dir |
| `rcp/**` | recursive — every directory under `rcp/`, skipping `target/` and dotdirs |
| `../../abc/apps/rcp/**` | external + recursive; relative `..` works |
| `rcp-*,bundles/**` | mix in-tree and recursive |

In CI/CD this is a transient change in the workspace — no commit needed.

### Build a single module

```
mvn -pl rcp-plugin -am clean install
```

Pomless modules are referenced by their artifactId (the Bundle-SymbolicName for `rcp-plugin`):

```
mvn -pl :com.example.mvp.rcp.plugin -am clean install
```

## How the pieces fit

| Concern                         | Mechanism                                                                |
|---------------------------------|--------------------------------------------------------------------------|
| Plain-Maven CI versioning       | `${releaseVersion}${qualifier}` + `flatten-maven-plugin` to resolve in installed poms |
| Tycho CI versioning             | Same property pair on the Maven side; OSGi side driven independently by MANIFEST/.product |
| Pomless modules                 | `.mvn/extensions.xml` registers `tycho-build`; bundles/products inherit from first ancestor pom (the aggregator) |
| Set-version artifact discovery  | `gmavenplus-plugin` script (bound to `validate`) scans `${rcp.discovery.dirs}` for `MANIFEST.MF` + `.product` files, feeds the resulting artifactId list into `tycho-versions-plugin` |
| MANIFEST/pom version sync       | Not needed — Maven side has no MANIFEST and OSGi side has no pom |
| `maven-lib` → RCP plugin        | `.target` file with `<location type="Maven">`; version filtered via `maven-resources-plugin` |
| Build order                     | Aggregator: parent-pom → maven-lib → maven-app → tycho-parent → target-platform → rcp-plugin → rcp-product |
| No Nexus required               | Reactor installs `maven-lib` to `~/.m2/repository`; Tycho's Maven location resolves from there |

## Importing into Eclipse

Pomless modules import fine into Eclipse — just not as Maven projects. Two options:

- **PDE (recommended):** **File → Open Projects from File System…**, point at the repo root. Eclipse detects `rcp-plugin/` as a Plug-in project from its `MANIFEST.MF`. Activate the target platform via **Window → Preferences → Plug-in Development → Target Platform** pointing at `target-platform/target-platform.target` (after running at least one `mvn install` so the file exists and `maven-lib` is in your local repo).
- **Maven-aware:** install the **m2e-tycho** / "M2E PDE Integration" feature from the Eclipse Marketplace. Then **Import → Existing Maven Projects** will see the pomless modules too.

`.project` / `.classpath` / `.settings/` are gitignored — Eclipse generates them per workspace.

## Notes / gotchas

- First build downloads the Eclipse 2024-03 P2 repo (~80 MB); cached afterwards.
- `target-platform/target-platform.target` at the project root is **generated** during the build (filtered from `src/main/resources/target-platform.target`). `clean` removes it.
- The aggregator pom has `<parent>tycho-parent</parent>` so pomless modules see Tycho's plugin registration via inheritance. This creates a slightly unusual loop (the aggregator's `<modules>` includes its own parent) which Maven resolves fine — parent refs are processed before module lists.
- `rcp-plugin/plugin.xml` declares the product extension referenced by `rcp-product/maven-mvp.product` (`id="com.example.mvp.rcp.plugin.product"`). Without it the product builds but won't actually launch at runtime.
- `set-version` walks cross-references via property expressions only — once it has rewritten `${releaseVersion}${qualifier}` into a literal version on a path it traversed (e.g. in `dependencyManagement`), subsequent runs won't refresh that field. The fields it leaves alone (project `<version>`, parent `<version>` references) stay as expressions across runs.
