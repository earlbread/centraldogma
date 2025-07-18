# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Central Dogma is a distributed service configuration repository based on Git and ZooKeeper. It provides a highly available and version-controlled configuration store with multi-protocol support (HTTP/REST, gRPC, Thrift).

## Essential Commands

### Building and Testing

```bash
# Build the entire project
./gradlew build

# Run all tests
./gradlew test

# Run a specific test class
./gradlew :server:test --tests com.linecorp.centraldogma.server.internal.storage.repository.git.GitRepositoryTest

# Run tests matching a pattern
./gradlew test --tests "*GitRepository*"

# Build without tests
./gradlew build -x test

# Clean build
./gradlew clean build
```

### Code Quality

```bash
# Run checkstyle
./gradlew checkstyle

# Run all checks (including checkstyle)
./gradlew check
```

### Web Application Development

```bash
# Navigate to webapp directory first
cd webapp

# Install dependencies
npm install

# Run development server
npm run dev

# Run tests in watch mode
npm test

# Run tests for CI
npm run test:ci

# Lint and fix
npm run lint:fix

# Format code
npm run format:fix

# Build for production
npm run build

# Run E2E tests
npm run test:e2e
```

### Documentation

```bash
# Generate site documentation
./gradlew site

# The generated documentation will be in site/build/site/
```

## Architecture Overview

### Core Components

1. **Server (`server/`)**: Main server implementation using Armeria framework
   - Entry point: `CentralDogma` class
   - Core services in `com.linecorp.centraldogma.server.internal`
   - Storage layer: Git repositories + RocksDB for metadata
   - Coordination: ZooKeeper/Curator for distributed consensus

2. **Common (`common/`)**: Shared data models and APIs
   - Core types: `Project`, `Repository`, `Commit`, `Change`
   - API interfaces that both server and clients use

3. **Client Libraries**:
   - `client/java/`: Pure Java client
   - `client/java-armeria/`: Armeria-based async client
   - `client/java-spring-boot*/`: Spring Boot integrations

4. **Web UI (`webapp/`)**: React/Next.js application
   - Redux Toolkit for state management
   - Chakra UI for components
   - TypeScript throughout

### Key Design Patterns

1. **Command Pattern**: All repository operations (push, watch, diff) are commands
   - See `Command` classes in `server/src/main/java/com/linecorp/centraldogma/server/command/`

2. **Repository Abstraction**: 
   - Git-based storage with metadata in RocksDB
   - Watch mechanism for real-time updates
   - Mirror support for external Git repositories

3. **Multi-Protocol Support**:
   - HTTP/REST API (main)
   - Thrift RPC (legacy)
   - gRPC (experimental)

4. **Authentication/Authorization**:
   - Pluggable auth via Shiro
   - SAML support
   - Per-repository permissions

### Important Conventions

1. **Package Structure**:
   - `internal` packages: Implementation details, not part of public API
   - `common` packages: Shared between server and client
   - Test classes follow same package structure in `src/test`

2. **Async Programming**:
   - Uses CompletableFuture extensively
   - Armeria's EventLoop for HTTP handling
   - Be careful with blocking operations

3. **Error Handling**:
   - Custom exceptions in `common.CentralDogmaException`
   - HTTP status codes mapped appropriately
   - Detailed error messages for debugging

4. **Testing**:
   - Unit tests next to implementation
   - Integration tests in `it/` directory
   - Use `@ParameterizedTest` for multiple scenarios
   - Mock external dependencies (ZooKeeper, Git)

## Development Tips

1. **Working with Git Storage**:
   - Repository data stored in `gitRepositories/` under data directory
   - Metadata in RocksDB for performance
   - Direct Git operations possible but use APIs

2. **Debugging Server**:
   - Enable debug logging: `-Dcom.linecorp.centraldogma.logLevel=DEBUG`
   - Metrics available at `/internal/metrics`
   - Health check at `/internal/health`

3. **Frontend Development**:
   - API calls go through `/api/v1/` prefix
   - Redux DevTools enabled in development
   - Hot reload works with `npm run dev`

4. **Adding New Features**:
   - Update both server and common modules
   - Add client support if needed
   - Update webapp for UI changes
   - Document in Sphinx docs

5. **Performance Considerations**:
   - Watch operations are long-polling
   - Batch operations when possible
   - Repository mirroring is async
   - Use pagination for large result sets