# HTTP server stage 01 design

## Goal

Implement the first HTTP server challenge stage: listen on TCP port 4221 and accept a client connection.

## Design

`Main.main` will create a `ServerSocket` bound to port 4221, enable address reuse, and block in `accept()`.
The process remains running until a client connects, providing the smallest correct foundation for later HTTP parsing stages.
`IOException` is reported to standard error without fabricating an HTTP response.

## Verification

An integration test starts the compiled `Main` process, waits until port 4221 accepts a TCP connection, and fails if the process exits or the port remains unavailable.
The worker must run the test while the starter program still fails before implementing the listener.

## Documentation

The stage note explains socket binding, TCP's connection model, port reuse, blocking accept, failure modes, tradeoffs, Java APIs, and a rebuild checklist.
