# HTTP server stage 01: Bind to a port implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the HTTP server listen on TCP port 4221 and accept one connection for CodeCrafters stage #at4.

**Architecture:** Keep the existing single `Main` class and use a blocking `ServerSocket` as the smallest event loop for this stage.
The process blocks in `accept()` after binding, proving readiness without inventing HTTP behavior before later stages require it.

**Tech Stack:** Java 25, `java.net.ServerSocket`, PowerShell integration test, and the CodeCrafters HTTP Server challenge.

**Spec:** `docs/superpowers/specs/2026-09-18-http-stage-01-bind-to-port-design.md`

## Global Constraints

- Bind exactly TCP port `4221`.
- Enable `SO_REUSEADDR` before accepting a connection.
- Retain a blocking accept limited to the first connection for this stage.
- Do not parse or send HTTP data in this stage.
- Write the integration test first and observe its expected failure against the starter code.
- Compile with `javac -d codecrafters-http-server-java/target/classes codecrafters-http-server-java/src/main/java/Main.java`.
- Write `codecrafters-http-server-java/Tasks/1. Bind to a port.md` using the pedagogical sections required by `Tasks/Agent.md`.
- Commit the stage with `Solve Stage 1: Bind to a port`.

---

### Task 1: Bind and accept a TCP connection

**Files:**

- Create: `codecrafters-http-server-java/tests/bind-to-port.ps1`
- Create: `codecrafters-http-server-java/Tasks/1. Bind to a port.md`
- Modify: `codecrafters-http-server-java/src/main/java/Main.java`

**Interfaces:**

- Consumes: the Java process entry point `Main.main(String[] args)`.
- Produces: a process accepting a `TcpClient` connection to `127.0.0.1:4221` while it is running.

- [ ] **Step 1: Write the failing integration test.**

Create `codecrafters-http-server-java/tests/bind-to-port.ps1` that compiles `Main.java`, starts `java -cp target/classes Main`, retries a `TcpClient` connection to `127.0.0.1:4221` for five seconds, and kills the process in a `finally` block.

- [ ] **Step 2: Run the test and verify the expected failure.**

Run `powershell -ExecutionPolicy Bypass -File codecrafters-http-server-java/tests/bind-to-port.ps1`.
Expected: the test fails because the starter `Main` exits without binding port 4221.

- [ ] **Step 3: Implement the minimal listener.**

Replace the commented template in `Main.main` with a try-with-resources `ServerSocket` bound to 4221, call `setReuseAddress(true)`, call `accept()` once, and write any `IOException` to standard error.
Do not add HTTP parsing, threading, a second accept, or response writing.

- [ ] **Step 4: Run the integration test and direct compilation.**

Run `powershell -ExecutionPolicy Bypass -File codecrafters-http-server-java/tests/bind-to-port.ps1` and `javac -d codecrafters-http-server-java/target/classes codecrafters-http-server-java/src/main/java/Main.java`.
Expected: both commands exit with code 0.

- [ ] **Step 5: Write the study note.**

Create `codecrafters-http-server-java/Tasks/1. Bind to a port.md` with `Problem Solved`, `Concepts`, `Wire Format / Protocol`, `Failure Modes`, `Tradeoffs`, `Java Specifics`, and `Rebuild Checklist` sections.
Explain TCP byte streams, binding, `accept()`, and why no HTTP is parsed yet.

- [ ] **Step 6: Check the stage diff and commit.**

Run `git diff --check`, stage only the three stage files, and commit `Solve Stage 1: Bind to a port`.
