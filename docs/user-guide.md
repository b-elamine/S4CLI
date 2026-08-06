# sam4c-cli User Guide

sam4c-cli takes two input files -- an architecture description and a set of security rules -- and merges them into a single unified model. That unified model is the starting point for any downstream work such as validation, IaC generation, or compliance checking.

---

## Table of contents

1. [Build and install](#1-build-and-install)
2. [CLI reference](#2-cli-reference)
3. [Writing the architecture file](#3-writing-the-architecture-file)
4. [Writing the security rules file](#4-writing-the-security-rules-file)
5. [Understanding the output](#5-understanding-the-output)
5b. [Generating a starter security model](#5b-generating-a-starter-security-model---init-secdsl)
6. [Extending the tool](#6-extending-the-tool)
7. [Error messages](#7-error-messages)

---

## 1. Build and install

Requirements: Java 21, Maven 3.8+.

```bash
cd sam4c-cli
mvn package -q
```

This produces `target/sam4c-cli.jar`. That single file contains all dependencies. You can copy it anywhere.

To make the command available globally:

```bash
cp target/sam4c-cli.jar ~/bin/sam4c-cli.jar
alias s4c="java -jar ~/bin/sam4c-cli.jar"
```

---

## 2. CLI reference

```
sam4c-cli <arch-file> <rules-file> [options]
sam4c-cli <arch-file> --init-secdsl
sam4c-cli --metamodel
```

If the `s4c` alias is configured (see the project README), `s4c` can replace
`java -jar sam4c-cli.jar` in every command below.

### Arguments

| Argument | Description |
|---|---|
| `<arch-file>` | Path to the architecture YAML file (`.arch.yaml`) |
| `<rules-file>` | Path to the security rules file (`.secdsl`) |

### Options

| Option | Description |
|---|---|
| `-o <file>` / `--output <file>` | Where to write output. For the merge it is the JSON path (default `<arch-name>.sam4c.json`); with `--init-secdsl` it is the `.secdsl` path |
| `--html` | Also write a self-contained interactive HTML graph (`<arch-name>.sam4c.html`) next to the JSON |
| `--init-secdsl` | Generate a starter `.secdsl` from the architecture's attributes and exit. Needs only the arch file; refuses to overwrite an existing file |
| `--metamodel` | Print the full M2 metamodel definition (all three sub-packages: core, architecture, security) and exit. No input files required |
| `--inspect` | Print a full object-graph traversal: every rule with its fully resolved component objects, their types, attributes, ports, and children. Use this to verify the merge is correct before running a generator |
| `--validate` | Run the merge and print the resolution report, but do not write any output file. Exit code is 0 if all references resolved, 1 if any are unresolved |
| `--strict` | Treat semantic warnings (unmet or violated security rules, e.g. a single-replica `Availability=high`, a broken `Isolation`, an unauthenticated exposed service) as failures: exit non-zero and write no output. Use this to block insecure designs in CI |
| `--serve [--port N]` | Start the web Studio (browser editor + live graph) on port N (default 8080) |
| `--help` | Print usage |
| `--version` | Print version |

### Examples

Merge and write output:
```bash
java -jar sam4c-cli.jar clinic.arch.yaml clinic.secdsl
```

Merge with explicit output path:
```bash
java -jar sam4c-cli.jar clinic.arch.yaml clinic.secdsl -o output/clinic.sam4c.json
```

Validate without writing output (useful in CI):
```bash
java -jar sam4c-cli.jar clinic.arch.yaml clinic.secdsl --validate
echo $?   # 0 = all resolved, 1 = unresolved references
```

Inspect the full object graph traversal:
```bash
java -jar sam4c-cli.jar clinic.arch.yaml clinic.secdsl --inspect
```

Merge and also write the interactive HTML graph:
```bash
java -jar sam4c-cli.jar clinic.arch.yaml clinic.secdsl --html
# writes clinic.sam4c.json and clinic.sam4c.html
```

Generate a starter security model from the architecture (no rules file needed):
```bash
java -jar sam4c-cli.jar clinic.arch.yaml --init-secdsl
# writes clinic.secdsl with #attribute and #context declarations derived from the arch
```

Print the M2 metamodel (no input files needed):
```bash
java -jar sam4c-cli.jar --metamodel
```

### Terminal output

Every normal run first checks conformance against the M2 metamodel, then prints a summary:

```
Loading architecture: clinic.arch.yaml
Parsing security rules: clinic.secdsl
Checking conformance against M2 metamodel...
  Conformance OK
Merging and resolving cross-references...

-- Architecture --
  Name       : clinic-portal
  Components : 4
  Connectors : 4
  Links      : 7

-- Security --
  Attributes : 3
  Contexts   : 7
  Rules      : 7

-- Coverage (context -> matched components) --
  frontendCtx               -> [FrontendVM, Nginx]
  backendCtx                -> [BackendVM, SpringAPI]
  dbCtx                     -> [DatabaseVM, PatientDB]

-- Resolved rules --
  Confidentiality      sctx=[DatabaseVM, PatientDB]
                       tctx=[BackendVM, SpringAPI]
  Isolation            sctx=[DatabaseVM, PatientDB]
                       tctx=[FrontendVM, Nginx]

Wrote unified model to: clinic.sam4c.json
```

If any context name or reference in the security rules cannot be resolved, it appears under "Unresolved" and the tool exits with code 1.

---

## 3. Writing the architecture file

The architecture file is a YAML file. The convention is to name it `<name>.arch.yaml`. It describes the components of your system, the connectors between them, and the links that wire ports to connectors.

### Minimal example

```yaml
name: my-app

components:
  - name: ServerVM
    type: VM
    children:
      - name: WebServer
        type: App
        ports: [http_in, http_out]

connectors:
  - name: Internet
    external: true

links:
  - port: WebServer.http_in
    connector: Internet
```

### Top-level fields

| Field | Required | Description |
|---|---|---|
| `name` | Yes | Name of the architecture. Used as the base name for the output file |
| `components` | Yes | List of top-level components |
| `connectors` | Yes | List of connectors (network endpoints between components) |
| `links` | Yes | List of associations between ports and connectors |

---

### Components

Each component has the following fields:

| Field | Required | Description |
|---|---|---|
| `name` | Yes | Unique name for this component |
| `type` | Yes | Component type: `VM`, `App`, or `Data` (or any custom type you register) |
| `ports` | No | Port names, or rich port objects (see below) |
| `children` | No | Nested components (only meaningful for `VM`) |
| `external` | No | Boolean. Marks a component as hosted outside your infrastructure (default: false) |
| `attributes` | No | Key-value map used by the merger to evaluate security context predicates. A component with `Domain: frontend` will be matched by any context that contains `(Domain=frontend)` |
| `runtime` | No | How a workload is packaged: `container` \| `process` \| `function` |
| `exposure` | No | Reachability: `none` \| `internal` \| `external` |
| `deployedOn` | No | Name of a Host (VM/PM/Worker) this workload runs on (placement) |
| `image` | No | Artifact/image the component runs, e.g. `registry/api:1.4.2` (deployment property) |
| `scale` | No | Replication/autoscaling: `{ replicas, min, max, metric }` |
| `resources` | No | Resource requests: `{ cpu, memory }` |
| `lifecycle` | No | `continuous` \| `batch` \| `scheduled` (with `schedule` for scheduled) |
| `persistent` / `storage` | No | (Data) `persistent: true` + `storage: { size, class }` |
| `config` / `secrets` | No | `config: { KEY: value }` map; `secrets: [NAME]` references (never values) |
| `health` | No | Probe: `{ path, port }` |
| `trigger` | No | Invocation source: `{ kind: http\|event\|schedule, source }` |
| `placement` | No | `{ zone, affinity, scope }` |
| `spread` | No | `none` \| `host` \| `zone` - distribute the unit's replicas across failure domains (different machines / different data centers). Also valid on `Colocation`. |

**Component roles (M2).** Types fall into three roles:
- **Workloads** — `App` (stateless), `Data` (stateful). They run; carry `runtime`/`exposure`/`scale`/`image`/`deployedOn`.
- **Hosts** — `VM`, `PM`, `Worker`. Workloads run on them (`deployedOn`).
- **Groups** — `Zone` (boundary), `Colocation` (co-located, `shareNetwork`/`shareStorage`), `HostPool` (pool of hosts). They **contain** child components (via `children`).

`runtime`/`exposure` values and `deployedOn` (must name a real Host) are validated. Group
containment is validated too: a `Colocation` may hold only Workloads, a `HostPool` only
Hosts, a `Zone` not a Host directly. Placement (`deployedOn`, a reference) is kept separate from
grouping (`children`). Example of co-location:

```yaml
- name: api-pod
  type: Colocation
  shareNetwork: true
  children:
    - name: api
      type: App
      attributes: { Domain: backend }
      runtime: container
    - name: log-sidecar
      type: App
      attributes: { Domain: backend }
```

`image`, `scale`, and `resources` are **deployment properties** a generator consumes (replicas, image, sizing). They are carried in the component's `properties` map — optional, and not yet metamodel-validated (the "fast path"). The graph shows a replica badge (e.g. `Api x3`) for components with `scale.replicas`. Example:

```yaml
- name: api
  type: App
  attributes: { Domain: backend }
  image: registry/api:1.4.2
  scale: { replicas: 3, min: 2, max: 10, metric: cpu }
  resources: { cpu: "500m", memory: "512Mi" }
  ports: [ { name: api_in, number: 8080, protocol: http } ]
```

#### Type: VM

A virtual machine or grouping element. Contains other components as children. VMs do not have ports directly -- their child components do.

```yaml
- name: BackendVM
  type: VM
  children:
    - name: SpringAPI
      type: App
      ports: [api_in, db_out, admin_in]
    - name: Cache
      type: App
      ports: [cache_in]
```

#### Type: App

A running application, service, or microservice. Has ports.

```yaml
- name: Nginx
  type: App
  attributes:
    Domain: frontend
  ports: [http_in, api_out]
```

#### Port forms

Ports accept two forms. The **simple** form is just names:

```yaml
ports: [http_in, api_out]
```

The **rich** form adds a port number and protocol (needed for generation — emitting a
Service, firewall rule, or security-group entry):

```yaml
ports:
  - { name: http_in, number: 80,   protocol: http }
  - { name: api_out, number: 8080, protocol: http }
```

`number` (1–65535) and `protocol` (`tcp` | `udp` | `http` | `grpc`) are optional; invalid
values are conformance errors. The two forms can be mixed across components. The port `name`
is what a `Link`'s `port: Component.name` reference points at.

#### Type: Data

A data store (database, object storage, message queue). Has ports.

```yaml
- name: PostgreSQL
  type: Data
  attributes:
    Domain: database
    DataClass: clinical
  ports: [db_in]
```

#### Nesting

Components can be nested to any depth. A VM contains Apps and Data stores. You can also nest VMs inside VMs to model clusters or groups.

```yaml
- name: Cluster
  type: VM
  children:
    - name: Node1
      type: VM
      children:
        - name: ServiceA
          type: App
          ports: [in, out]
    - name: Node2
      type: VM
      children:
        - name: ServiceB
          type: App
          ports: [in, out]
```

---

### Connectors

A connector is a named communication channel. Components are never directly connected to each other -- they are always connected through a connector via their ports.

```yaml
connectors:
  - name: FE_to_BE
  - name: BE_to_DB
  - name: Internet
    external: true
```

| Field | Required | Description |
|---|---|---|
| `name` | Yes | Unique name for the connector |
| `external` | No | Boolean. Marks this connector as originating from outside the system (default: false) |
| `protocol` | No | Channel protocol: `tcp` \| `udp` \| `http` \| `grpc` |

`protocol` is a **structural** fact about the channel. Security requirements on a channel
(e.g. "must be encrypted") are **not** declared here — they come from the security model: a
`Confidentiality` rule resolves to the connector it crosses (via path resolution), so the
encryption requirement is *derived at merge*, not duplicated on the connector. Example:

```yaml
connectors:
  - name: BE_to_DB
    protocol: tcp
```

---

### Links

A link connects a port (written as `ComponentName.portName`) to a connector, with a flow direction. Both ends of a communication channel need a link: one for the sending port and one for the receiving port.

```yaml
links:
  - port: Nginx.api_out
    connector: FE_to_BE
    direction: out          # Nginx sends into FE_to_BE
  - port: SpringAPI.api_in
    connector: FE_to_BE
    direction: in           # SpringAPI receives from FE_to_BE
```

| Field | Required | Description |
|---|---|---|
| `port` | Yes | Reference to a port, written as `ComponentName.portName` |
| `connector` | Yes | Name of the connector this port is wired to |
| `direction` | No | Flow from the component's view: `out` (sends into connector), `in` (receives from connector), or `inout` (default, bidirectional) |

#### Direction

`direction` is component-centric and matches the usual port naming convention:

- `out` -- the component **sends** through this port into the connector (e.g. `api_out`)
- `in` -- the component **receives** from the connector through this port (e.g. `http_in`)
- `inout` -- bidirectional; this is the **default** when `direction` is omitted

So old files without `direction:` keep working (every link is treated as bidirectional). Validation rejects any value other than `in`, `out`, or `inout`. The HTML graph draws arrowheads to match (one arrow for `in`/`out`, both ends for `inout`), and each resolved rule's `paths` carry the direction so a generator can place directional controls.

Referential integrity is enforced: a link whose `port` names a non-existent component/port, or whose `connector` is not declared, is a conformance error (the run fails) -- this prevents broken topology reaching a generator.

#### Port reference format

The component name in a port reference is the direct parent component name, not the full qualified path:

```
Nginx.api_out        # correct: Nginx is the direct owner of api_out
FrontendVM.api_out   # wrong: api_out belongs to Nginx, not FrontendVM
```

---

### Full example

```yaml
name: clinic-portal

components:
  - name: FrontendVM
    type: VM
    attributes:
      Domain: frontend
    children:
      - name: Nginx
        type: App
        attributes:
          Domain: frontend
        ports: [http_in, api_out]

  - name: BackendVM
    type: VM
    attributes:
      Domain: backend
    children:
      - name: SpringAPI
        type: App
        attributes:
          Domain: backend
        ports: [api_in, db_out, admin_in]

  - name: DatabaseVM
    type: VM
    attributes:
      Domain: database
    children:
      - name: PatientDB
        type: Data
        attributes:
          Domain: database
          DataClass: clinical
        ports: [db_in]

  - name: AdminVM
    type: VM
    attributes:
      Domain: admin
    children:
      - name: AdminDashboard
        type: App
        attributes:
          Domain: admin
          Role: administrator
        ports: [mgmt_out]

connectors:
  - name: FE_to_BE
  - name: BE_to_DB
  - name: Admin_to_BE
  - name: Internet
    external: true

links:
  - port: Nginx.http_in
    connector: Internet
  - port: Nginx.api_out
    connector: FE_to_BE
  - port: SpringAPI.api_in
    connector: FE_to_BE
  - port: SpringAPI.db_out
    connector: BE_to_DB
  - port: PatientDB.db_in
    connector: BE_to_DB
  - port: AdminDashboard.mgmt_out
    connector: Admin_to_BE
  - port: SpringAPI.admin_in
    connector: Admin_to_BE
```

---

## 4. Writing the security rules file

The security rules file uses the `.secdsl` extension. It is the same format as the original SAM4C SecDSL. A file contains three kinds of declarations, in any order: attribute declarations, context declarations, and property declarations.

### 4.1 Attribute declarations

An attribute is a named set of possible values. Attributes are used to classify components.

```
#attribute Name = (value1, value2, value3);
```

Examples:

```
#attribute Domain = (frontend, backend, database, admin);
#attribute Role = (patient, clinician, administrator);
#attribute DataClass = (pii, clinical, audit);
#attribute Tier = (web, app, data);
```

Attributes can also be declared without values if you only need the name as a type:

```
#attribute Sensitivity;
```

---

### 4.2 Context declarations

A context is a named predicate that selects a set of elements. Contexts are the subjects and targets of security rules.

```
#context name = condition1 : condition2 : ... ;
```

A condition is an attribute-value pair written as `(AttributeName=value)`.

A context with one condition:

```
#context frontendCtx = (Domain=frontend);
```

A context with multiple conditions (all must match -- it is a conjunction):

```
#context adminCtx = (Domain=backend) : (Role=administrator);
```

Contexts can also reference other named contexts directly:

```
#context sensitiveBackend = backendCtx : (DataClass=pii);
```

---

### 4.3 Property declarations

A security property expresses a security requirement between contexts. The general syntax is:

```
#property PropertyName(arg1, arg2, ...) -> optional_target ;
```

There are five built-in properties.

---

#### Confidentiality

The communication between `sctx` and `tctx` must be encrypted. No third party can read the data in transit.

```
#property Confidentiality(sctx, tctx);
```

`tctx` is optional. When omitted it means the property applies to all communications involving `sctx`.

Examples:

```
#property Confidentiality(dbCtx, backendCtx);
#property Confidentiality(clinicalDataCtx, backendCtx);
#property Confidentiality(piiCtx);
```

Real-world mapping: TLS on the link between the two components, encrypted at-rest storage for data contexts.

---

#### Integrity

Data flowing from `sctx` to `tctx` must not be modifiable in transit.

```
#property Integrity(sctx, tctx);
```

`tctx` is optional.

Examples:

```
#property Integrity(backendCtx, dbCtx);
#property Integrity(clinicalDataCtx);
```

Real-world mapping: TLS with integrity checking, message signing, write-protected channels.

---

#### Isolation

`sctx` and `tctx` must have no shared execution or network path. They cannot communicate with each other under any circumstances.

```
#property Isolation(sctx, tctx);
```

`tctx` is optional.

Examples:

```
#property Isolation(dbCtx, frontendCtx);
#property Isolation(adminCtx, frontendCtx);
```

Real-world mapping: separate VPCs or subnets, security group rules with explicit deny, Kubernetes NetworkPolicy with no ingress from the other namespace.

---

#### Authentication

An entity described by `sctx` must authenticate via mechanism `actx` before it is allowed to reach `tctx`.

```
#property Authentication(sctx, actx) -> tctx;
```

All three arguments are required. The arrow `->` separates the authenticating party and mechanism from the target.

Read it as: "who=sctx, authenticated by=actx, can reach=tctx".

Examples:

```
#property Authentication(adminRoleCtx, adminCtx) -> backendCtx;
#property Authentication((Role=administrator), (Domain=admin)) -> dbCtx;
```

Real-world mapping: IAM role check, mTLS client certificate validation, API key gate, OAuth token verification on the target endpoint.

---

#### Authorization

A `subject` may perform one or more `actions` on a `resource`. Authentication answers "who are you"; Authorization answers "what may you do" once in.

```
#property Authorization(subject, resource, action [, action ...]);
```

`subject` and `resource` are contexts; the remaining arguments are action names (`read`, `write`, `admin`, ...). At least one action is required; list several to grant them together.

Examples:

```
#property Authorization(adminRoleCtx, dbCtx, read, write);
#property Authorization((Role=clinician), clinicalDataCtx, read);
```

Default semantics: **deny by default** - an action is permitted only if an `Authorization` rule grants it (an allowlist, like RBAC/IAM implicit deny).

Real-world mapping: RBAC (Role + RoleBinding), an IAM policy, or an access-control rule allowing `action` from the subject to the resource.

#### Availability

A `target` context must keep a minimum resilience `level`. This is the "A" of the CIA triad: it defends against denial-of-service, resource exhaustion, and single-point-of-failure.

```
#property Availability(target, level);
```

`target` is a context; `level` is one of `low`, `medium`, `high`. The levels are defined by what failure the service survives:

| Level | Survives | Requires (in the architecture) |
|-------|----------|--------------------------------|
| `low` | nothing | no requirement |
| `medium` | losing one instance | `scale.replicas >= 2` |
| `high` | losing a whole data center | `scale.replicas >= 2` and `spread: zone` |

Example:

```
#property Availability(backendCtx, high);
```

The tool **verifies** this against the architecture (a semantic check). If a `medium`/`high` target has fewer than 2 copies, or a `high` target is not spread across zones, you get a warning that the requirement is not actually satisfied. Set `scale` and `spread` on the workload (or its `Colocation`) to satisfy it.

Real-world mapping: minimum replicas + a PodDisruptionBudget + topology-spread constraints (Kubernetes), or an Auto Scaling group across multiple Availability Zones (AWS).

---

### 4.4 Inline references

Instead of declaring a named context and then referencing it, you can write the condition inline inside a rule.

Single inline condition:

```
#property Confidentiality((Domain=database), (Domain=backend));
```

Multiple inline conditions joined with `:` (conjunction):

```
#property Authentication((Role=administrator) : (Domain=admin), (Domain=admin)) -> (Domain=backend);
```

A group of condition sets in braces (disjunction -- any of the groups):

```
#property Isolation(dbCtx, {(Domain=frontend) : (Role=patient), (Domain=frontend) : (Role=guest)});
```

---

### 4.5 Complete example

```
#attribute Domain = (frontend, backend, database, admin);
#attribute Role = (patient, clinician, administrator);
#attribute DataClass = (pii, clinical, audit);

#context frontendCtx = (Domain=frontend);
#context backendCtx = (Domain=backend);
#context dbCtx = (Domain=database);
#context adminCtx = (Domain=admin);
#context adminRoleCtx = (Role=administrator);
#context clinicalDataCtx = (DataClass=clinical);
#context piiCtx = (DataClass=pii);

#property Confidentiality(dbCtx, backendCtx);
#property Integrity(backendCtx, dbCtx);
#property Authentication(adminRoleCtx, adminCtx) -> backendCtx;
#property Isolation(dbCtx, frontendCtx);
#property Isolation(adminCtx, frontendCtx);
#property Confidentiality(clinicalDataCtx, backendCtx);
#property Confidentiality(piiCtx, backendCtx);
```

---

### 4.6 Comments

Lines starting with `//` are ignored:

```
// This is a comment
#attribute Domain = (frontend, backend);  // inline comment
```

---

## 5. Understanding the output

The output is a JSON file named `<arch-name>.sam4c.json`. It has five top-level fields.

### name

The architecture name from the YAML file.

### architecture

The full architecture as loaded from the YAML file. Hierarchical structure is preserved -- VMs contain their child Apps and Data stores. Each port appears as `{ "name": "http_in" }`.

### security

The security model as parsed from the `.secdsl` file. Each rule has a `type` field:

```json
{
  "type": "Confidentiality",
  "sctx": { "name": "dbCtx" },
  "tctx": { "name": "backendCtx" }
}
```

This section shows the rules as written -- using context names, not resolved components. It is the "intent" layer.

### coverage

For each named context, the full component objects that matched its predicate. Components are embedded inline with all their data:

```json
"coverage": {
  "dbCtx": [
    { "name": "DatabaseVM", "type": "VM",   "attributes": { "Domain": "database" }, "children": ["PatientDB"] },
    { "name": "PatientDB",  "type": "Data", "attributes": { "Domain": "database", "DataClass": "clinical" }, "ports": ["db_in"] }
  ]
}
```

A context with no matching components appears as an empty array. This is useful feedback: it means you wrote a security rule that applies to nothing in your architecture.

### resolvedRules

Each security rule with the full component objects for every side embedded inline. This is the generator-ready section -- a generator reading only this section has everything it needs:

```json
{
  "type": "Isolation",
  "sctx": [
    { "name": "DatabaseVM", "type": "VM",   "attributes": { "Domain": "database" }, "children": ["PatientDB"] },
    { "name": "PatientDB",  "type": "Data", "attributes": { "Domain": "database", "DataClass": "clinical" }, "ports": ["db_in"] }
  ],
  "tctx": [
    { "name": "FrontendVM", "type": "VM",  "attributes": { "Domain": "frontend" }, "children": ["Nginx"] },
    { "name": "Nginx",      "type": "App", "attributes": { "Domain": "frontend" }, "ports": ["http_in", "api_out"] }
  ]
}
```

A generator iterates `resolvedRules`, reads the rule `type`, then accesses `sctx`, `tctx`, and `actx` directly. No lookup into the `architecture` section required.

#### paths

When the source and target sides share a connector, the rule also carries a `paths` array: the concrete channel(s) the rule governs. This is what a generator places a control on.

```json
{
  "type": "Confidentiality",
  "sctx": [ { "name": "PatientDB", "type": "Data", ... } ],
  "tctx": [ { "name": "SpringAPI", "type": "App", ... } ],
  "paths": [
    {
      "connector": "BE_to_DB",
      "sctxLinks": [ { "port": "PatientDB.db_in", "direction": "in" } ],
      "tctxLinks": [ { "port": "SpringAPI.db_out", "direction": "out" } ]
    }
  ]
}
```

How a generator uses it:

| Rule | With `paths` |
|---|---|
| Confidentiality | enable TLS on each `path.connector` |
| Integrity | integrity-checked channel on each `path.connector` (direction available on the links) |
| Isolation | **empty `paths` = already isolated**; a non-empty `paths` is a contradiction (sides share a connector) |
| Authentication | put an auth gate on the target-side ingress link of each path |

`paths` is omitted when empty (the two sides share no connector, or the rule has no target side). Because link references are validated for integrity, a path never points at a non-existent port or connector.

### unresolved (only if non-empty)

A list of names that appeared in the security rules but could not be matched to any named context or architecture element:

```json
"unresolved": ["typoCtx", "UnknownVM"]
```

When this field appears, the tool exits with code 1. Fix the names in the `.secdsl` file or add the missing components to the `.arch.yaml` file.

### HTML graph (`--html`)

With `--html`, a `<arch-name>.sam4c.html` file is written next to the JSON. It is a
single self-contained file (Cytoscape.js loaded from a CDN) that opens in any browser.

It is generated directly from the in-memory unified model -- the same resolved
`Component` references the JSON is built from -- so the graph is a faithful view of the
object graph, not a re-parse of the JSON.

What it shows:

- **Nodes**: components (VMs as compound containers around their children) and connectors
- **Architecture edges** (gray): the topology -- components wired to connectors via links
- **Security edges** (coloured): one per resolved rule pair, coloured by rule type
  (Confidentiality blue, Integrity green, Isolation red dashed, Authentication purple)

Interaction:

- **View toggle**: Architecture / Security / Both
- **Filter** by rule type
- **Click a node**: shows its type, attributes, and ports
- **Click a security edge**: shows the full resolved rule (all sctx / tctx / actx components)
- **Coverage panel**: for each context, the components it matched

---

## 5b. Generating a starter security model (`--init-secdsl`)

Run with only the architecture file and `--init-secdsl` to scaffold a `.secdsl`:

```bash
java -jar sam4c-cli.jar clinic.arch.yaml --init-secdsl
```

It scans every component (recursively) and emits:

- one `#attribute` declaration per attribute key, listing every value found in the arch
- one `#context` per `key=value` pair, named `<value>Ctx`
- commented-out templates of the four property types for you to fill in

It only emits what the architecture actually contains -- attribute values that no
component carries are not generated. It never invents security rules (those require
human intent), and it refuses to overwrite an existing file. Use `-o <file>` to write
elsewhere.

---

## 6. Extending the tool

### Adding a new security property

**Step 1.** Add the model record to `sam4c/light/model/rule/`:

```java
// NonRepudiation.java
package sam4c.light.model.rule;
import sam4c.light.model.ref.Ref;

public record NonRepudiation(Ref sctx) implements SecurityRule {}
```

**Step 2.** Add it to the `permits` clause of `SecurityRule.java`:

```java
public sealed interface SecurityRule
        permits Confidentiality, Integrity, Isolation, Authentication, NonRepudiation {}
```

**Step 3.** Register the factory in `Main.java` inside the `call()` method:

```java
propReg.register(new RuleFactory() {
    public String keyword() { return "NonRepudiation"; }
    public SecurityRule create(List<Ref> args, Ref ret) {
        return new NonRepudiation(args.get(0));
    }
});
```

**Step 4.** Add a case to `resolveRule()` in `ModelMerger.java` and to `checkRule()` in `ConformanceChecker.java`:

```java
// ModelMerger.java -- resolveRule()
case NonRepudiation r -> new ResolvedRule(rule,
    resolveRef(r.sctx(), coverage, all, unresolved),
    List.of(), List.of());

// ConformanceChecker.java -- checkRule()
case NonRepudiation r -> {
    if (r.sctx() == null)
        errors.add("NonRepudiation: sctx is required");
    else errors.addAll(checkRef(r.sctx(), "NonRepudiation.sctx"));
}
```

**Step 5.** Use it in any `.secdsl` file:

```
#property NonRepudiation(auditLogCtx);
```

---

### Adding a new component type

The loader is fully metamodel-driven -- there is no Java handler to write. Declare the
type in `ArchMetamodel.java` and it becomes loadable, writable, and editable in the
Studio automatically.

**Step 1.** Add the class in `ArchMetamodel.java`, e.g. a new kind of Deployable:

```java
MClass.builder("Function").superType("Deployable")
    .attr("trigger", STRING, 0, 1)
    .build(),
```

**Step 2.** Use it in any `.arch.yaml` file:

```yaml
- name: ThumbnailJob
  type: Function
  trigger: s3-upload
  ports: [http_in]
```

`trigger` (declared on the new type) and every field `Function` inherits from
`Deployable` (`exposure`, `deployedOn`, `implementation`, ...) are read into
`Component.properties` automatically -- `ComponentRegistry.load()` derives what to
read from `allAttributes`/`allReferences`, it never hardcodes a field list per type.
The Studio palette, editor form, and YAML writer pick up the new type the same way.

---

## 7. Error messages

### ParseException: Unknown keyword

```
Line 4: Unknown keyword: #propertyy
```

A typo in a `#attribute`, `#context`, or `#property` keyword. Check the spelling.

---

### ParseException: Expected identifier

```
Line 12: Expected identifier but got '('
```

The parser expected a name but found something else. Common cause: a missing context name, or a property keyword not followed by the correct syntax.

---

### Unknown property

```
Error: Unknown property 'Availabilty'. Known: [Confidentiality, Integrity, Isolation, Authentication]
```

A property keyword in the `.secdsl` file is not registered. Either it is a typo, or you need to register it as a new property (see section 6).

---

### Unknown component type

```
Error: Unknown component type 'Pod'. Known: [App, Data, VM]
```

A `type:` value in the `.arch.yaml` file is not registered. Either it is a typo, or you need to register it as a new component type (see section 6).

---

### Unresolved references (warning)

```
WARNING: 2 unresolved reference(s). Check names in your .secdsl file.

-- Resolution report --
  x typoCtx
  x UnknownVM
```

A name used in a security rule or context could not be matched to any named context or architecture element. Common causes:

- Typo in the context name
- Context declared in the `.secdsl` file but referenced with a different capitalisation
- Architecture element name that does not match what is in the YAML file
- A context referenced before it is declared (declarations can be in any order, so this should not happen -- if it does, it is a bug)

---

### Architecture file not found / Rules file not found

```
Architecture file not found: clinic.arch.yaml
```

The path passed as the first argument does not exist. Use an absolute path or make sure the working directory is correct.
