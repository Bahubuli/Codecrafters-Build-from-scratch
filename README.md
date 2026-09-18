# CodeCrafters Build Your Own X

Parent workspace for building and mastering systems software from scratch.

This monorepo tracks deep-dive implementations of real-world infrastructure systems. Each project is implemented with production-grade architecture, exhaustive failure-mode testing, and comprehensive pedagogical documentation.

## Projects

| Project | Local Path | Language | Status | CodeCrafters Challenge |
| :--- | :--- | :--- | :--- | :--- |
| **Build Your Own Redis** | [`codecrafters-redis-java`](codecrafters-redis-java/) | Java 21 | **100% Completed** (123/123 Stages) | [Redis Challenge](https://app.codecrafters.io/courses/redis/overview) |
| **Build Your Own HTTP Server** | [`codecrafters-http-server-java`](codecrafters-http-server-java/) | Java 21 | **In Progress** | [HTTP Server Challenge](https://app.codecrafters.io/courses/http-server/overview) |
| **Build Your Own Shell** | [`codecrafters-shell-java`](codecrafters-shell-java/) | Java 21 | Available | [Shell Challenge](https://app.codecrafters.io/courses/shell/overview) |
| **Build Your Own Interpreter** | [`codecrafters-interpreter-java`](codecrafters-interpreter-java/) | Java 21 | **In Progress** | [Interpreter Challenge](https://app.codecrafters.io/courses/interpreter/overview) |
| **Build Your Own Grep** | [`codecrafters-grep-java`](codecrafters-grep-java/) | Java 21 | **In Progress** | [Grep Challenge](https://app.codecrafters.io/courses/grep/overview) |
| **Build Your Own BitTorrent** | [`codecrafters-bittorrent-java`](codecrafters-bittorrent-java/) | Java 25 | **In Progress** | [BitTorrent Challenge](https://app.codecrafters.io/courses/bittorrent/overview) |

---

### 1. Build Your Own Redis (`codecrafters-redis-java`)
Full-featured, protocol-compliant in-memory key-value store and streaming engine:
- **Core RESP Wire Protocol & Storage Engine**: PING, ECHO, SET (with PX/EX TTL expiry), GET, INCR, TYPE, KEYS, CONFIG GET.
- **Lists**: RPUSH, LPUSH, LPOP, LRANGE (positive & negative indexing), LLEN, BLPOP (blocking pop with zero and fractional timeouts).
- **Streams**: XADD (fully/partially auto-generated & explicit IDs, validation), XRANGE (bounded, `-`, `+`), XREAD (single/multi-stream, blocking with timeouts and `$` latest-id tracking).
- **Transactions & Optimistic Locking**: MULTI, EXEC, DISCARD, WATCH (tracking modifications, missing keys, multi-key watches, UNWATCH, auto-unwatch on EXEC/DISCARD).
- **Replication Engine**: Full duplex master-replica handshakes (PING, REPLCONF listening-port, REPLCONF capa, PSYNC ? -1), empty RDB snapshot transfer, real-time command propagation, REPLCONF ACK offset synchronization, WAIT command barrier.
- **Persistence (RDB & AOF)**: Binary RDB parsing (string encoding, timestamps, length-prefixed bytes, expired key filtration), AOF directory & manifest management, write filtering, crash recovery replaying.
- **Pub/Sub Messaging**: SUBSCRIBE, UNSUBSCRIBE, PUBLISH, state isolation (connection subscribed mode enforcement).
- **Sorted Sets (ZSET)**: ZADD, ZRANK, ZRANGE, ZCOUNT, ZSCORE, ZREM (composite sorted order, float scores).
- **Geospatial Indexes (GEO)**: GEOADD, GEOPOS, GEODIST, GEOSEARCH (Haversine formula, 52-bit integer geohash encoding/decoding, search by radius).
- **Security & Access Control (ACL)**: ACL WHOAMI, ACL GETUSER, `nopass` flag, SHA-256 hashed password authentication, AUTH command, connection authentication enforcement.
- **Bitmaps**: SETBIT, GETBIT, BITCOUNT, BITOP (AND, OR, XOR, NOT) with zero-padding and bitwise alignment.
- **Pedagogical Archive**: All 123 stages accompanied by deep architectural documentation in [`codecrafters-redis-java/Tasks/`](codecrafters-redis-java/Tasks/).

---

### 2. Build Your Own HTTP Server (`codecrafters-http-server-java`)
Lightweight, RFC-compliant HTTP/1.1 web server built from TCP primitives:
- TCP server socket binding and concurrent connection handling via thread pools.
- HTTP request line, header, and body parsing.
- URL routing, dynamic path parameter extraction, and status code generation (`200 OK`, `404 Not Found`, `201 Created`).
- File serving and uploading (`/files/{filename}`).
- Content negotiation and HTTP compression (`gzip` via `java.util.zip.GZIPOutputStream`).
- Persistent connections (`Connection: keep-alive`).

---

### 3. Build Your Own Shell (`codecrafters-shell-java`)
POSIX-compliant command-line interpreter:
- Built-in commands (`echo`, `type`, `exit`, `pwd`, `cd`).
- `PATH` resolution and external program execution.
- Single and double quoting rules, escaping, argument tokenization.
- Standard input/output/error redirection (`>`, `1>`, `2>`, `>>`, `1>>`, `2>>`).
- Pipelines (`|`) with multi-process coordination.

---

### 4. Build Your Own Interpreter (`codecrafters-interpreter-java`)
Full-featured tree-walk interpreter for the Lox programming language (Crafting Interpreters):
- **Scanning & Lexical Analysis**: Regular expressions, token streams, lexemes, literal preservation, line tracking, error reporting.
- **Syntactic Analysis (Parsing)**: Context-free grammars, recursive descent parsing, operator precedence and associativity, AST nodes.
- **Evaluation & Runtime**: Tree-walk expression evaluator, truthiness, unary and binary operations, type checking and runtime error handling.
- **Statements & State**: Expression statements, print statements, global and local variables, nested lexical environments and block scopes.
- **Control Flow**: Conditional branching (`if`/`else`), logical operators (`and`/`or` short-circuiting), loops (`while`, `for`).
- **Functions & Closures**: Function declarations, call expressions, arity checking, return statements, lexical closures.
- **Classes & OOP**: Class declarations, instantiation, properties, method invocation, `this` binding, constructors.

---

### 5. Build Your Own Grep (`codecrafters-grep-java`)
Recursive-descent / backtracking regular expression matcher and grep CLI from first principles:
- **Literals & Character Classes**: Single characters, `\\d` (digits), `\\w` (word characters).
- **Character Groups**: Positive groups (`[abc]`), negative groups (`[^abc]`).
- **Compound Patterns**: Combinations of literal characters and character classes.
- **Anchors**: Line beginning (`^`) and line ending (`$`).
- **Quantifiers**: One or more (`+`), zero or one (`?`), zero or more (`*`).
- **Wildcard**: Any character (`.`).
- **Alternation**: Disjunction of subpatterns (`(cat|dog)`).
- **Backreferences**: Single and multiple capture groups with backreference matching (`\\1`, `\\2`).

---

### 6. Build Your Own BitTorrent (`codecrafters-bittorrent-java`)
Full-featured BitTorrent client conforming to BEP 0003, BEP 0009, and BEP 0010:
- **Bencoding Engine**: Serializer and deserializer for strings, integers, lists, and ordered dictionaries.
- **Metainfo & SHA-1 Verification**: `.torrent` metainfo extraction, binary piece hashing, info hash computation.
- **Tracker Communication**: Compact binary peer discovery over HTTP/GET with URL-encoded 20-byte binary hashes.
- **Peer Wire Protocol**: Handshakes, stateful message parsing (`choke`, `unchoke`, `interested`, `bitfield`, `request`, `piece`).
- **Pipelined Block Downloader**: Asynchronous 16 KiB block scheduling, piece validation against SHA-1 hashes, disk persistence.
- **Magnet Links & Extension Protocol**: BEP 9/BEP 10 metadata exchange over peer wire.

---

## Repository Architecture & Remotes

- **Parent Monorepo**: Pushed to GitHub:
  `https://github.com/Bahubuli/Codecrafters-Build-from-scratch.git`
- **CodeCrafters Remotes**:
  - `redis-codecrafters`: `https://git.codecrafters.io/9300847fe0a03ad5`
  - `http-codecrafters`: `https://git.codecrafters.io/a660f48206a74329`
  - `shell-codecrafters`: `https://git.codecrafters.io/d44f4c8a35fb46bd`
  - `interpreter-codecrafters`: `https://git.codecrafters.io/45148298d18ce52a`
  - `grep-codecrafters`: `https://git.codecrafters.io/6bc6999955a9e704`
  - `bittorrent-codecrafters`: `https://git.codecrafters.io/9565605bfc6d1920`

CodeCrafters isolates tests per challenge repo. Submissions are synced seamlessly from the monorepo to the respective challenge remotes via dedicated scripts:

```sh
# Submit Redis changes
powershell -File scripts/push-redis.ps1 "Stage NN: <Title>"

# Submit HTTP Server changes
powershell -File scripts/push-http.ps1 "Stage NN: <Title>"

# Submit Shell changes
powershell -File scripts/push-shell.ps1 "Stage NN: <Title>"

# Submit Interpreter changes
powershell -File scripts/push-interpreter.ps1 "Stage NN: <Title>"

# Submit Grep changes
powershell -File scripts/push-grep.ps1 "Stage NN: <Title>"

# Submit BitTorrent changes
powershell -File scripts/push-bittorrent.ps1 "Stage NN: <Title>"
```
