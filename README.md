# CodeCrafters Build Your Own X

Parent workspace for building and mastering systems software from scratch.

This monorepo tracks deep-dive implementations of real-world infrastructure systems. Each project is implemented with production-grade architecture, exhaustive failure-mode testing, and comprehensive pedagogical documentation.

## Projects

| Project | Local Path | Language | Status | CodeCrafters Challenge |
| :--- | :--- | :--- | :--- | :--- |
| **Build Your Own Redis** | [`codecrafters-redis-java`](codecrafters-redis-java/) | Java 21 | **100% Completed** (123/123 Stages) | [Redis Challenge](https://app.codecrafters.io/courses/redis/overview) |
| **Build Your Own HTTP Server** | [`codecrafters-http-server-java`](codecrafters-http-server-java/) | Java 21 | **100% Completed** (14/14 Stages) | [HTTP Server Challenge](https://app.codecrafters.io/courses/http-server/overview) |
| **Build Your Own DNS Server** | [`codecrafters-dns-server-java`](codecrafters-dns-server-java/) | Java 21 | **100% Completed** (8/8 Stages) | [DNS Server Challenge](https://app.codecrafters.io/courses/dns-server/overview) |
| **Build Your Own Grep** | [`codecrafters-grep-java`](codecrafters-grep-java/) | Java 21 | **100% Completed** (Base Track - 12/12 Stages) | [Grep Challenge](https://app.codecrafters.io/courses/grep/overview) |
| **Build Your Own SQLite** | [`codecrafters-sqlite-java`](codecrafters-sqlite-java/) | Java 21 | **100% Completed** (9/9 Stages) | [SQLite Challenge](https://app.codecrafters.io/courses/sqlite/overview) |
| **Build Your Own Shell** | [`codecrafters-shell-java`](codecrafters-shell-java/) | Java 21 | Available | [Shell Challenge](https://app.codecrafters.io/courses/shell/overview) |
| **Build Your Own Interpreter** | [`codecrafters-interpreter-java`](codecrafters-interpreter-java/) | Java 21 | **In Progress** | [Interpreter Challenge](https://app.codecrafters.io/courses/interpreter/overview) |
| **Build Your Own BitTorrent** | [`codecrafters-bittorrent-java`](codecrafters-bittorrent-java/) | Java 21 | Available | [BitTorrent Challenge](https://app.codecrafters.io/courses/bittorrent/overview) |

---

### 1. Build Your Own Redis (`codecrafters-redis-java`)
Full-featured, protocol-compliant in-memory key-value store and streaming engine:
- **Core RESP Wire Protocol & Storage Engine**: PING, ECHO, SET (with PX/EX TTL expiry), GET, INCR, TYPE, KEYS, CONFIG GET.
- **Lists**: RPUSH, LPUSH, LPOP, LRANGE (positive & negative indexing), LLEN, BLPOP (blocking pop with zero and fractional timeouts).
- **Streams**: XADD (fully/partially auto-generated & explicit IDs, validation), XRANGE (bounded, `-`, `+`), XREAD (single/multi-stream, blocking with timeouts and `$` latest-id tracking).
- **Transactions & Optimistic Locking**: MULTI, EXEC, DISCARD, WATCH (tracking modifications, missing keys, multi-key watches, UNWATCH, auto-unwatch on EXEC/DISCARD).
- **Replication Engine**: Full duplex master-replica handshakes (PING, REPLCONF listening-port, REPLCONF capa, PSYNC ? -1), empty RDB snapshot transfer, real-time command propagation, REPLCONF ACK offset synchronization, WAIT command barrier.
- **Persistence (RDB & AOF)**: Binary RDB parsing (string encoding, timestamps, length-prefixed bytes, expired key filtration), AOF directory & manifest management, write filtering, crash recovery replaying).
- **Pub/Sub Messaging**: SUBSCRIBE, UNSUBSCRIBE, PUBLISH, state isolation (connection subscribed mode enforcement).
- **Sorted Sets (ZSET)**: ZADD, ZRANK, ZRANGE, ZCOUNT, ZSCORE, ZREM (composite sorted order, float scores).
- **Geospatial Indexes (GEO)**: GEOADD, GEOPOS, GEODIST, GEOSEARCH (Haversine formula, 52-bit integer geohash encoding/decoding, search by radius).
- **Security & Access Control (ACL)**: ACL WHOAMI, ACL GETUSER, `nopass` flag, SHA-256 hashed password authentication, AUTH command, connection authentication enforcement).
- **Bitmaps**: SETBIT, GETBIT, BITCOUNT, BITOP (AND, OR, XOR, NOT) with zero-padding and bitwise alignment.
- **Pedagogical Archive**: All 123 stages accompanied by deep architectural documentation in [`codecrafters-redis-java/Tasks/`](codecrafters-redis-java/Tasks/).

---

### 2. Build Your Own HTTP Server (`codecrafters-http-server-java`)
Production-grade, RFC-compliant HTTP/1.1 web server built from raw TCP sockets:
- **Base HTTP Protocol & Networking**:
  - TCP server socket binding with `SO_REUSEADDR` on port 4221.
  - Multi-threaded asynchronous request dispatching via `ExecutorService` cached thread pools.
  - Case-insensitive request header parsing, CRLF message boundaries, and body streaming using `Content-Length`.
  - Dynamic routing and status code handling (`200 OK`, `201 Created`, `404 Not Found`).
- **File Server & Static Assets**:
  - Serving binary files via `/files/{filename}` with `application/octet-stream`.
  - Storing uploaded binary payloads via `POST /files/{filename}` with directory creation.
- **HTTP Compression Extension (RFC 1952)**:
  - `Accept-Encoding` header negotiation supporting comma-separated tokenization with whitespace trimming.
  - GZIP body compression via `java.util.zip.GZIPOutputStream` and raw binary octet streaming.
  - Accurate `Content-Length` calculation on compressed byte streams.
- **Persistent Connections Extension (HTTP/1.1 Keep-Alive)**:
  - Keep-alive connection pooling over single TCP connections (`curl --http1.1 --next`).
  - Concurrent persistent connections across independent client sockets.
  - Graceful connection teardown via `Connection: close` header synchronization.
- **Pedagogical Archive**: All 14 stages fully documented in [`codecrafters-http-server-java/Tasks/`](codecrafters-http-server-java/Tasks/) with 11-section pedagogical guides.

---

### 3. Build Your Own DNS Server (`codecrafters-dns-server-java`)
Full-featured, RFC 1035 compliant UDP DNS forwarding and resolution server built from raw datagram sockets:
- **UDP Socket Networking**:
  - Binding to UDP port 2053 and listening for DNS queries using Java NIO `ByteBuffer` and raw `DatagramPacket`.
- **DNS Wire Format Header Serialization & Parsing**:
  - 12-byte binary header manipulation (ID, QR, OPCODE, AA, TC, RD, RA, Z, RCODE, QDCOUNT, ANCOUNT, NSCOUNT, ARCOUNT).
  - Bitfield packing and masking across 16-bit flags.
  - Dynamic query reflection: echoing client query ID, opcode, recursion desired bit, and setting proper error response codes (`RCODE 4` for unsupported opcodes).
- **Question & Answer Framing**:
  - Variable-length length-prefixed label sequence encoding and decoding (`<len><label>...<0x00>`).
  - Resource Record synthesis for `A` records (`TYPE 1`, `CLASS 1`, 32-bit big-endian TTL, 16-bit RDLENGTH, 4-byte IPv4 RDATA).
- **DNS Compression Pointer Decompression**:
  - Resolution of 14-bit compression pointers (`0xC0` mask) referencing prior offsets in the message.
  - Composite pointer chaining, cursor preservation, and circular loop protection.
- **DNS Forwarding Proxy & Multiplexing**:
  - Upstream resolver integration via `--resolver <ip:port>`.
  - Query demultiplexing: splitting multi-question incoming queries into isolated single-question datagrams for strict upstream resolvers.
  - Upstream response deserialization, record extraction, and response multiplexing into a single downstream reply datagram.
- **Pedagogical Archive**: All 8 stages fully documented in [`codecrafters-dns-server-java/Tasks/`](codecrafters-dns-server-java/Tasks/).

---

### 4. Build Your Own Grep (`codecrafters-grep-java`)
Recursive-descent AST parser and backtracking regular expression engine built from first principles (without `java.util.regex`):
- **Lexing & Pattern AST Architecture**:
  - Custom recursive descent token parser emitting discrete `PatternNode` AST nodes.
  - Polymorphic token hierarchy: `LiteralToken`, `DigitToken` (`\d`), `WordToken` (`\w`), `WildcardToken` (`.`).
  - Positive character groups `[...]` and negative character groups `[^...]` with exact set inclusion.
- **Compound Pattern Evaluation**:
  - Sliding-window tape reader iterating prospective substring match origins.
  - Recursive continuous predicate matching over token sequences.
- **Anchors & Positional Invariants**:
  - Start-of-string anchor `^` pinning matching exclusively to index 0.
  - End-of-string anchor `$` enforcing complete input tape exhaustion.
- **Greedy Quantifiers with Recursive Backtracking**:
  - One-or-more quantifier `+` matching maximally with greedy step-down backtracking.
  - Zero-or-one quantifier `?` attempting 1-character match before taking zero-width $\epsilon$-transition fallback.
- **Alternation & Capture Groups**:
  - Parenthesized alternation `(a|b|c)` exploring branching alternative sub-expressions.
  - Capture group registration and backtracking state preservation.
- **Pedagogical Archive**: All 12 base stages documented in [`codecrafters-grep-java/Tasks/`](codecrafters-grep-java/Tasks/) adhering to the structured revision card standard.

---

### 5. Build Your Own SQLite (`codecrafters-sqlite-java`)
Production-grade SQLite database storage engine and query executor implemented from raw binary byte buffers:
- **Database Header & Page Architecture**:
  - 100-byte database file header parsing (`page size`, `reserved space`, `text encoding`).
  - Big-endian byte order manipulation and 64-bit page offset addressing.
- **B-tree Page Layouts & Parsing**:
  - Table B-tree pages: Leaf pages (`0x0D`) and Interior pages (`0x05`).
  - Index B-tree pages: Leaf pages (`0x0A`) and Interior pages (`0x02`).
  - Cell pointer array traversal, cell content decoding, and rightmost child routing.
- **Varint Decoding & Record Serialization Format**:
  - SQLite variable-length integer (varint) decoding (up to 9 bytes with high-bit continuation).
  - Record header parsing: serial type codes (integers 1-6, 8/9 constants, floats 7, UTF-8 strings $\ge 13$, blobs $\ge 12$).
- **Schema Discovery & SQL Parser**:
  - Reading `sqlite_schema` (tables, indexes, root pages, column definitions).
  - Robust `CREATE TABLE` and `CREATE INDEX` SQL parsing with identifier quote stripping (`"`, `'`, `` ` ``, `[]`).
  - Support for `INTEGER PRIMARY KEY` rowid aliasing.
- **Relational Query Execution**:
  - Multi-column projection and delimiter formatting (`col1|col2|...`).
  - Filter evaluation with `WHERE` equality predicates against literal values.
  - Full-table multi-page B-tree scans across interior and leaf pages.
  - Index-driven query optimization: $O(\log N)$ range search over secondary index B-trees combined with point lookups on table B-trees, servicing queries on gigabyte databases in milliseconds.
- **Pedagogical Archive**: All 9 stages completely documented in [`codecrafters-sqlite-java/Tasks/`](codecrafters-sqlite-java/Tasks/).

---

### 6. Build Your Own Shell (`codecrafters-shell-java`)
POSIX-compliant command-line interpreter:
- Built-in commands (`echo`, `type`, `exit`, `pwd`, `cd`).
- `PATH` resolution and external program execution.
- Single and double quoting rules, escaping, argument tokenization.
- Standard input/output/error redirection (`>`, `1>`, `2>`, `>>`, `1>>`, `2>>`).
- Pipelines (`|`) with multi-process coordination.

---

### 7. Build Your Own Interpreter (`codecrafters-interpreter-java`)
Full-featured tree-walk interpreter for the Lox programming language (Crafting Interpreters):
- **Scanning & Lexical Analysis**: Regular expressions, token streams, lexemes, literal preservation, line tracking, error reporting.
- **Syntactic Analysis (Parsing)**: Context-free grammars, recursive descent parsing, operator precedence and associativity, AST nodes.
- **Evaluation & Runtime**: Tree-walk expression evaluator, truthiness, unary and binary operations, type checking and runtime error handling.
- **Statements & State**: Expression statements, print statements, global and local variables, nested lexical environments and block scopes.
- **Control Flow**: Conditional branching (`if`/`else`), logical operators (`and`/`or` short-circuiting), loops (`while`, `for`).
- **Functions & Closures**: Function declarations, call expressions, arity checking, return statements, lexical closures.
- **Classes & OOP**: Class declarations, instantiation, properties, method invocation, `this` binding, constructors.

---

## Repository Architecture & Remotes

- **Parent Monorepo**: Pushed to GitHub:
  `https://github.com/Bahubuli/Codecrafters-Build-from-scratch.git`
- **CodeCrafters Remotes**:
  - `redis-codecrafters`: `https://git.codecrafters.io/9300847fe0a03ad5`
  - `http-codecrafters`: `https://git.codecrafters.io/a660f48206a74329`
  - `dns-codecrafters`: `https://git.codecrafters.io/5bb057cfc4420466`
  - `shell-codecrafters`: `https://git.codecrafters.io/d44f4c8a35fb46bd`
  - `interpreter-codecrafters`: `https://git.codecrafters.io/45148298d18ce52a`
  - `grep-codecrafters`: `https://git.codecrafters.io/6bc6999955a9e704`
  - `sqlite-codecrafters`: `https://git.codecrafters.io/3834811af6483d8a`
  - `bittorrent-codecrafters`: `https://git.codecrafters.io/9565605bfc6d1920`

CodeCrafters isolates tests per challenge repo. Submissions are synced seamlessly from the monorepo to the respective challenge remotes via dedicated scripts:

```sh
# Submit Redis changes
powershell -File scripts/push-redis.ps1 "Stage NN: <Title>"

# Submit HTTP Server changes
powershell -File scripts/push-http.ps1 "Stage NN: <Title>"

# Submit DNS Server changes
powershell -File scripts/push-dns.ps1 "Stage NN: <Title>"

# Submit Grep changes
powershell -File scripts/push-grep.ps1 "Stage NN: <Title>"

# Submit SQLite changes
powershell -File scripts/push-sqlite.ps1 "Stage NN: <Title>"
```
