# Hexagonal Architecture Migration Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Migrate `taxinvoice-processing-service` to textbook Hexagonal Architecture (Ports and Adapters) following a layer-by-layer incremental strategy that keeps tests green after every commit.

**Architecture:** Domain layer has only outbound ports (`domain/port/out/`). Application layer has both inbound ports (`application/port/in/` — use case interfaces) and outbound ports (`application/port/out/` — orchestration infrastructure). Infrastructure is split into `adapter/in/` (driving: Camel routes, command handler) and `adapter/out/` (driven: JPA, outbox publishers, XML parser). The aggregate raises domain events that the application layer drains and routes through the outbound event port.

**Tech Stack:** Java 21, Spring Boot 3.2.5, Apache Camel 4.14.4, PostgreSQL, Kafka, JPA/Hibernate, Flyway, JaCoCo, JUnit 5, Mockito

---

## Reference: Current → Target File Mapping

| Current path | Target path | Change |
|---|---|---|
| `domain/repository/ProcessedTaxInvoiceRepository` | `domain/port/out/ProcessedTaxInvoiceRepository` | Move |
| `domain/service/TaxInvoiceParserService` | `domain/port/out/TaxInvoiceParserPort` | Move + rename |
| `domain/port/SagaReplyPort` | `application/port/out/SagaReplyPort` | Move |
| `domain/event/ProcessTaxInvoiceCommand` | `infrastructure/adapter/in/messaging/dto/ProcessTaxInvoiceCommand` | Move |
| `domain/event/CompensateTaxInvoiceCommand` | `infrastructure/adapter/in/messaging/dto/CompensateTaxInvoiceCommand` | Move |
| `domain/event/TaxInvoiceReplyEvent` | `infrastructure/adapter/out/messaging/dto/TaxInvoiceReplyEvent` | Move |
| `domain/event/TaxInvoiceProcessedEvent` | `infrastructure/adapter/out/messaging/dto/TaxInvoiceProcessedEvent` | Move |
| `application/service/SagaCommandHandler` | `infrastructure/adapter/in/messaging/SagaCommandHandler` | Move + slim |
| `infrastructure/config/SagaRouteConfig` | `infrastructure/adapter/in/messaging/SagaRouteConfig` | Move |
| `infrastructure/messaging/EventPublisher` | `infrastructure/adapter/out/messaging/TaxInvoiceEventPublisher` | Move + rename + new interface |
| `infrastructure/messaging/SagaReplyPublisher` | `infrastructure/adapter/out/messaging/SagaReplyPublisher` | Move |
| `infrastructure/messaging/HeaderSerializer` | `infrastructure/adapter/out/messaging/HeaderSerializer` | Move |
| `infrastructure/persistence/ProcessedTaxInvoiceRepositoryImpl` | `infrastructure/adapter/out/persistence/ProcessedTaxInvoiceRepositoryAdapter` | Move + rename |
| `infrastructure/persistence/*.java` (entities, mapper, Spring Data repo) | `infrastructure/adapter/out/persistence/*.java` | Move |
| `infrastructure/persistence/outbox/*.java` | `infrastructure/adapter/out/outbox/*.java` | Move |
| `infrastructure/service/TaxInvoiceParserServiceImpl` | `infrastructure/adapter/out/parsing/TaxInvoiceParserAdapter` | Move + rename |

**New files created:**
- `domain/event/TaxInvoiceProcessedDomainEvent.java` (pure domain event record)
- `application/port/in/ProcessTaxInvoiceUseCase.java`
- `application/port/in/CompensateTaxInvoiceUseCase.java`
- `application/port/out/TaxInvoiceEventPublishingPort.java`

**Base package:** `com.wpanther.taxinvoice.processing`
**Base source path:** `src/main/java/com/wpanther/taxinvoice/processing/`
**Base test path:** `src/test/java/com/wpanther/taxinvoice/processing/`

---

## STEP 1: Domain Layer Restructure

> After this step: `domain/port/out/` contains all domain outbound ports. `domain/event/` contains the pure domain event. Old `domain/repository/`, `domain/service/`, `domain/port/` packages are deleted.

---

### Task 1: Create `domain/port/out/ProcessedTaxInvoiceRepository`

**Files:**
- Create: `src/main/java/com/wpanther/taxinvoice/processing/domain/port/out/ProcessedTaxInvoiceRepository.java`
- Delete later (Task 5): `src/main/java/com/wpanther/taxinvoice/processing/domain/repository/ProcessedTaxInvoiceRepository.java`

**Step 1: Create the new file with updated package declaration**

Copy the interface verbatim, changing only the package line:

```java
package com.wpanther.taxinvoice.processing.domain.port.out;

import com.wpanther.taxinvoice.processing.domain.model.TaxInvoiceId;
import com.wpanther.taxinvoice.processing.domain.model.ProcessedTaxInvoice;
import com.wpanther.taxinvoice.processing.domain.model.ProcessingStatus;

import java.util.List;
import java.util.Optional;

/**
 * Outbound port — persistence contract for ProcessedTaxInvoice aggregate.
 * Domain dictates the contract; infrastructure provides the implementation.
 */
public interface ProcessedTaxInvoiceRepository {

    ProcessedTaxInvoice save(ProcessedTaxInvoice invoice);

    Optional<ProcessedTaxInvoice> findById(TaxInvoiceId id);

    Optional<ProcessedTaxInvoice> findByInvoiceNumber(String invoiceNumber);

    List<ProcessedTaxInvoice> findByStatus(ProcessingStatus status);

    Optional<ProcessedTaxInvoice> findBySourceInvoiceId(String sourceInvoiceId);

    boolean existsByInvoiceNumber(String invoiceNumber);

    void deleteById(TaxInvoiceId id);
}
```

**Step 2: Verify the file compiles in isolation**

```bash
cd /home/wpanther/projects/etax/invoice-microservices/services/taxinvoice-processing-service
mvn compile -q 2>&1 | head -30
```

Expected: compile succeeds (old interface still exists, new one just added — no conflicts yet).

---

### Task 2: Create `domain/port/out/TaxInvoiceParserPort`

**Files:**
- Create: `src/main/java/com/wpanther/taxinvoice/processing/domain/port/out/TaxInvoiceParserPort.java`
- Delete later (Task 5): `src/main/java/com/wpanther/taxinvoice/processing/domain/service/TaxInvoiceParserService.java`

**Step 1: Create the new interface**

```java
package com.wpanther.taxinvoice.processing.domain.port.out;

import com.wpanther.taxinvoice.processing.domain.model.ProcessedTaxInvoice;

/**
 * Outbound port — XML parsing contract.
 * Domain defines what it needs; the teda-library adapter provides it.
 */
public interface TaxInvoiceParserPort {

    /**
     * Parse XML content into a ProcessedTaxInvoice domain object.
     *
     * @param xmlContent     The raw XML string from the saga command
     * @param sourceInvoiceId The document ID for idempotency tracking
     * @return Parsed domain object in PENDING status
     * @throws TaxInvoiceParsingException if parsing fails
     */
    ProcessedTaxInvoice parse(String xmlContent, String sourceInvoiceId)
            throws TaxInvoiceParsingException;

    /**
     * Checked exception for XML parsing failures.
     * Declared here so the domain port owns the exception contract.
     */
    class TaxInvoiceParsingException extends Exception {
        public TaxInvoiceParsingException(String message) {
            super(message);
        }

        public TaxInvoiceParsingException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
```

**Step 2: Verify compile**

```bash
mvn compile -q 2>&1 | head -30
```

Expected: success.

---

### Task 3: Create `domain/event/TaxInvoiceProcessedDomainEvent`

This is a pure domain event — a Java record with zero framework imports.

**Files:**
- Create: `src/main/java/com/wpanther/taxinvoice/processing/domain/event/TaxInvoiceProcessedDomainEvent.java`

**Step 1: Write the failing test first**

Create: `src/test/java/com/wpanther/taxinvoice/processing/domain/event/TaxInvoiceProcessedDomainEventTest.java`

```java
package com.wpanther.taxinvoice.processing.domain.event;

import com.wpanther.taxinvoice.processing.domain.model.Money;
import com.wpanther.taxinvoice.processing.domain.model.TaxInvoiceId;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class TaxInvoiceProcessedDomainEventTest {

    @Test
    void shouldCreateEventWithAllFields() {
        TaxInvoiceId id = TaxInvoiceId.generate();
        Money total = new Money(new BigDecimal("1000.00"), "THB");
        Instant now = Instant.now();

        TaxInvoiceProcessedDomainEvent event = new TaxInvoiceProcessedDomainEvent(
            id, "INV-001", total, "corr-123", now
        );

        assertThat(event.invoiceId()).isEqualTo(id);
        assertThat(event.invoiceNumber()).isEqualTo("INV-001");
        assertThat(event.total()).isEqualTo(total);
        assertThat(event.correlationId()).isEqualTo("corr-123");
        assertThat(event.occurredAt()).isEqualTo(now);
    }

    @Test
    void shouldBeEqualWhenAllFieldsMatch() {
        TaxInvoiceId id = TaxInvoiceId.generate();
        Money total = new Money(new BigDecimal("500.00"), "THB");
        Instant now = Instant.now();

        TaxInvoiceProcessedDomainEvent e1 = new TaxInvoiceProcessedDomainEvent(id, "INV-002", total, "c-1", now);
        TaxInvoiceProcessedDomainEvent e2 = new TaxInvoiceProcessedDomainEvent(id, "INV-002", total, "c-1", now);

        assertThat(e1).isEqualTo(e2);
    }
}
```

**Step 2: Run to confirm failure**

```bash
mvn test -Dtest=TaxInvoiceProcessedDomainEventTest -q 2>&1 | tail -20
```

Expected: FAIL — `TaxInvoiceProcessedDomainEvent` does not exist.

**Step 3: Create the domain event**

```java
package com.wpanther.taxinvoice.processing.domain.event;

import com.wpanther.taxinvoice.processing.domain.model.Money;
import com.wpanther.taxinvoice.processing.domain.model.TaxInvoiceId;

import java.time.Instant;

/**
 * Domain event raised by ProcessedTaxInvoice when processing completes.
 * Pure Java record — no framework or Kafka dependencies.
 * The application layer translates this into a Kafka DTO via TaxInvoiceEventPublishingPort.
 */
public record TaxInvoiceProcessedDomainEvent(
    TaxInvoiceId invoiceId,
    String invoiceNumber,
    Money total,
    String correlationId,
    Instant occurredAt
) {}
```

**Step 4: Run test to confirm pass**

```bash
mvn test -Dtest=TaxInvoiceProcessedDomainEventTest -q 2>&1 | tail -10
```

Expected: BUILD SUCCESS.

---

### Task 4: Add domain event support to `ProcessedTaxInvoice`

The aggregate must raise `TaxInvoiceProcessedDomainEvent` when `markCompleted()` is called. We extend the signature to accept `correlationId`.

**Files:**
- Modify: `src/main/java/com/wpanther/taxinvoice/processing/domain/model/ProcessedTaxInvoice.java`
- Modify test: `src/test/java/com/wpanther/taxinvoice/processing/domain/model/ProcessedTaxInvoiceTest.java`

**Step 1: Write failing tests for domain event behaviour**

Add these test methods to the existing `ProcessedTaxInvoiceTest` class:

```java
// Add imports at top of test file:
import com.wpanther.taxinvoice.processing.domain.event.TaxInvoiceProcessedDomainEvent;
import java.time.Instant;

@Test
void markCompleted_shouldRaiseTaxInvoiceProcessedDomainEvent() {
    ProcessedTaxInvoice invoice = buildValidInvoice();
    invoice.startProcessing();

    invoice.markCompleted("corr-abc");

    assertThat(invoice.domainEvents()).hasSize(1);
    TaxInvoiceProcessedDomainEvent event =
        (TaxInvoiceProcessedDomainEvent) invoice.domainEvents().get(0);
    assertThat(event.invoiceId()).isEqualTo(invoice.getId());
    assertThat(event.correlationId()).isEqualTo("corr-abc");
    assertThat(event.invoiceNumber()).isEqualTo(invoice.getInvoiceNumber());
    assertThat(event.occurredAt()).isNotNull();
}

@Test
void clearDomainEvents_shouldEmptyTheList() {
    ProcessedTaxInvoice invoice = buildValidInvoice();
    invoice.startProcessing();
    invoice.markCompleted("corr-xyz");
    assertThat(invoice.domainEvents()).hasSize(1);

    invoice.clearDomainEvents();

    assertThat(invoice.domainEvents()).isEmpty();
}

@Test
void domainEvents_shouldBeUnmodifiable() {
    ProcessedTaxInvoice invoice = buildValidInvoice();

    assertThatThrownBy(() -> invoice.domainEvents().add(new Object()))
        .isInstanceOf(UnsupportedOperationException.class);
}
```

**Step 2: Run to confirm failure**

```bash
mvn test -Dtest=ProcessedTaxInvoiceTest -q 2>&1 | tail -20
```

Expected: FAIL — `domainEvents()`, `clearDomainEvents()`, `markCompleted(String)` don't exist.

**Step 3: Modify `ProcessedTaxInvoice`**

Add the domain events list field after the `errorMessage` field declaration (line 44):

```java
// Add this import at the top of the file:
import com.wpanther.taxinvoice.processing.domain.event.TaxInvoiceProcessedDomainEvent;
import java.time.Instant;

// Add this field after errorMessage field:
// Domain events raised during aggregate lifecycle
private final List<Object> domainEvents = new ArrayList<>();
```

Replace the existing `markCompleted()` method (lines 145–151):

```java
/**
 * Mark invoice processing as completed.
 * Raises TaxInvoiceProcessedDomainEvent.
 *
 * @param correlationId The saga correlation ID for event tracing
 */
public void markCompleted(String correlationId) {
    if (status != ProcessingStatus.PROCESSING) {
        throw new IllegalStateException("Can only complete from PROCESSING status");
    }
    this.status = ProcessingStatus.COMPLETED;
    this.completedAt = LocalDateTime.now();
    domainEvents.add(new TaxInvoiceProcessedDomainEvent(
        this.id,
        this.invoiceNumber,
        this.getTotal(),
        correlationId,
        Instant.now()
    ));
}
```

Add these two methods after `markFailed()`:

```java
/**
 * Returns an unmodifiable view of domain events raised since last clear.
 */
public List<Object> domainEvents() {
    return Collections.unmodifiableList(domainEvents);
}

/**
 * Clears all domain events. Call after the application layer has processed them.
 */
public void clearDomainEvents() {
    domainEvents.clear();
}
```

**Step 4: Run tests to confirm pass**

```bash
mvn test -Dtest=ProcessedTaxInvoiceTest -q 2>&1 | tail -10
```

Expected: BUILD SUCCESS.

**Step 5: Run full test suite to catch any call sites that used `markCompleted()` with no args**

```bash
mvn test -q 2>&1 | tail -30
```

Fix any compilation errors where `markCompleted()` was called without a `correlationId` argument. Common locations to check:
- `ProcessedTaxInvoiceMapper.java` (entity → domain mapping may reconstruct completed status)
- Any test helpers that call `markCompleted()`

For mapper reconstruction (entity → domain), if the mapper sets status directly via the Builder (not calling `markCompleted()`), no change is needed. If tests call `markCompleted()` directly, pass any non-null string like `"test-correlation"`.

**Step 6: Commit**

```bash
git add src/main/java/com/wpanther/taxinvoice/processing/domain/ \
        src/test/java/com/wpanther/taxinvoice/processing/domain/
git commit -m "domain: add port/out packages, domain event, and domain event raising on markCompleted"
```

---

### Task 5: Update implementations to compile against new domain ports; delete old packages

Currently `TaxInvoiceParserServiceImpl` implements the old `TaxInvoiceParserService`. `ProcessedTaxInvoiceRepositoryImpl` imports from `domain.repository`. Update both, then delete old packages.

**Files:**
- Modify: `src/main/java/com/wpanther/taxinvoice/processing/infrastructure/service/TaxInvoiceParserServiceImpl.java`
- Modify: `src/main/java/com/wpanther/taxinvoice/processing/infrastructure/persistence/ProcessedTaxInvoiceRepositoryImpl.java`
- Modify: `src/main/java/com/wpanther/taxinvoice/processing/application/service/TaxInvoiceProcessingService.java` (import update only)
- Modify: `src/main/java/com/wpanther/taxinvoice/processing/application/service/SagaCommandHandler.java` (import update only)
- Delete: `src/main/java/com/wpanther/taxinvoice/processing/domain/repository/ProcessedTaxInvoiceRepository.java`
- Delete: `src/main/java/com/wpanther/taxinvoice/processing/domain/service/TaxInvoiceParserService.java`
- Delete: `src/main/java/com/wpanther/taxinvoice/processing/domain/port/SagaReplyPort.java`

**Step 1: Update `TaxInvoiceParserServiceImpl` — change implements clause and import**

In `TaxInvoiceParserServiceImpl.java`, change:
- Package stays in `infrastructure.service` for now (moves in Task 19)
- Change `implements TaxInvoiceParserService` → `implements TaxInvoiceParserPort`
- Change import: `domain.service.TaxInvoiceParserService` → `domain.port.out.TaxInvoiceParserPort`
- Change method signature: `parseInvoice(...)` → `parse(...)` (rename to match new port)
- Change exception: `TaxInvoiceParserService.TaxInvoiceParsingException` → `TaxInvoiceParserPort.TaxInvoiceParsingException`

The method rename from `parseInvoice` to `parse` means all callers (`TaxInvoiceProcessingService`) must also update. We'll handle that in Step 2.

**Step 2: Update `ProcessedTaxInvoiceRepositoryImpl` import**

Change:
```java
// Old:
import com.wpanther.taxinvoice.processing.domain.repository.ProcessedTaxInvoiceRepository;
// New:
import com.wpanther.taxinvoice.processing.domain.port.out.ProcessedTaxInvoiceRepository;
```

The class already `implements ProcessedTaxInvoiceRepository` — just the import path changes.

**Step 3: Update `TaxInvoiceProcessingService` imports**

Change:
```java
// Old:
import com.wpanther.taxinvoice.processing.domain.repository.ProcessedTaxInvoiceRepository;
import com.wpanther.taxinvoice.processing.domain.service.TaxInvoiceParserService;
// New:
import com.wpanther.taxinvoice.processing.domain.port.out.ProcessedTaxInvoiceRepository;
import com.wpanther.taxinvoice.processing.domain.port.out.TaxInvoiceParserPort;
```

Also update the field declaration and method call:
```java
// Old field:
private final TaxInvoiceParserService parserService;
// New field:
private final TaxInvoiceParserPort parserPort;

// Old method call:
ProcessedTaxInvoice invoice = parserService.parseInvoice(xmlContent, documentId);
// New method call:
ProcessedTaxInvoice invoice = parserPort.parse(xmlContent, documentId);

// Old exception reference:
throws TaxInvoiceParserService.TaxInvoiceParsingException
// New exception reference:
throws TaxInvoiceParserPort.TaxInvoiceParsingException
```

**Step 4: Update `SagaCommandHandler` import for repository**

Change:
```java
// Old:
import com.wpanther.taxinvoice.processing.domain.repository.ProcessedTaxInvoiceRepository;
// New:
import com.wpanther.taxinvoice.processing.domain.port.out.ProcessedTaxInvoiceRepository;
```

**Step 5: Delete old domain packages**

```bash
rm src/main/java/com/wpanther/taxinvoice/processing/domain/repository/ProcessedTaxInvoiceRepository.java
rm src/main/java/com/wpanther/taxinvoice/processing/domain/service/TaxInvoiceParserService.java
rm src/main/java/com/wpanther/taxinvoice/processing/domain/port/SagaReplyPort.java
rmdir src/main/java/com/wpanther/taxinvoice/processing/domain/repository
rmdir src/main/java/com/wpanther/taxinvoice/processing/domain/service
rmdir src/main/java/com/wpanther/taxinvoice/processing/domain/port
```

**Step 6: Compile and fix any remaining issues**

```bash
mvn compile -q 2>&1 | head -50
```

Fix any import errors that appear. Common issue: test files that import from the deleted packages — leave those for Step 5 (test updates).

**Step 7: Run tests**

```bash
mvn test -q 2>&1 | tail -20
```

Expected: BUILD SUCCESS. If test files import deleted packages, add `-Dmaven.test.failure.ignore=true` temporarily and note which tests need fixing.

**Step 8: Commit**

```bash
git add -A
git commit -m "domain: migrate implementations to domain/port/out contracts, remove old packages"
```

---

## STEP 2: Application Layer Restructure

> After this step: `application/port/in/` holds use case interfaces. `application/port/out/` holds `SagaReplyPort` and `TaxInvoiceEventPublishingPort`. `TaxInvoiceProcessingService` implements both use cases and drains domain events.

---

### Task 6: Create inbound port `ProcessTaxInvoiceUseCase`

**Files:**
- Create: `src/main/java/com/wpanther/taxinvoice/processing/application/port/in/ProcessTaxInvoiceUseCase.java`

**Step 1: Create the interface**

```java
package com.wpanther.taxinvoice.processing.application.port.in;

import com.wpanther.saga.domain.enums.SagaStep;

/**
 * Inbound port — driving adapter (Camel/Kafka) calls this to process a tax invoice.
 * The implementation lives in application/service/TaxInvoiceProcessingService.
 */
public interface ProcessTaxInvoiceUseCase {

    /**
     * Process a tax invoice from a saga command.
     * Handles idempotency, parses XML, persists, raises domain events, and publishes saga reply.
     *
     * @param documentId  Source document ID (used for idempotency)
     * @param xmlContent  Raw XML string to parse
     * @param sagaId      Saga instance identifier
     * @param sagaStep    Current step in the saga
     * @param correlationId Correlation ID for tracing
     */
    void process(String documentId, String xmlContent,
                 String sagaId, SagaStep sagaStep, String correlationId);
}
```

**Step 2: Compile**

```bash
mvn compile -q 2>&1 | head -20
```

Expected: success.

---

### Task 7: Create inbound port `CompensateTaxInvoiceUseCase`

**Files:**
- Create: `src/main/java/com/wpanther/taxinvoice/processing/application/port/in/CompensateTaxInvoiceUseCase.java`

**Step 1: Create the interface**

```java
package com.wpanther.taxinvoice.processing.application.port.in;

import com.wpanther.saga.domain.enums.SagaStep;

/**
 * Inbound port — driving adapter calls this to compensate (rollback) a tax invoice processing.
 * The implementation lives in application/service/TaxInvoiceProcessingService.
 */
public interface CompensateTaxInvoiceUseCase {

    /**
     * Compensate (hard delete) a previously processed tax invoice.
     * Safe to call if the invoice was never processed (no-op).
     *
     * @param documentId   Source document ID identifying the invoice to delete
     * @param sagaId       Saga instance identifier
     * @param sagaStep     Current step in the saga
     * @param correlationId Correlation ID for tracing
     */
    void compensate(String documentId, String sagaId,
                    SagaStep sagaStep, String correlationId);
}
```

**Step 2: Compile**

```bash
mvn compile -q 2>&1 | head -20
```

Expected: success.

---

### Task 8: Create outbound port `SagaReplyPort` in `application/port/out/`

**Files:**
- Create: `src/main/java/com/wpanther/taxinvoice/processing/application/port/out/SagaReplyPort.java`

Note: The old `domain/port/SagaReplyPort` was already deleted in Task 5. This is the new home.

**Step 1: Create the interface**

```java
package com.wpanther.taxinvoice.processing.application.port.out;

import com.wpanther.saga.domain.enums.SagaStep;

/**
 * Outbound port — application layer uses this to send saga reply events to the orchestrator.
 * Implemented by infrastructure/adapter/out/messaging/SagaReplyPublisher.
 */
public interface SagaReplyPort {

    void publishSuccess(String sagaId, SagaStep sagaStep, String correlationId);

    void publishFailure(String sagaId, SagaStep sagaStep, String correlationId, String errorMessage);

    void publishCompensated(String sagaId, SagaStep sagaStep, String correlationId);
}
```

**Step 2: Update `SagaReplyPublisher` to implement the new port**

In `infrastructure/messaging/SagaReplyPublisher.java`:
- Change import: `domain.port.SagaReplyPort` → `application.port.out.SagaReplyPort`
- The `implements SagaReplyPort` clause stays the same

**Step 3: Compile**

```bash
mvn compile -q 2>&1 | head -20
```

Expected: success.

---

### Task 9: Create outbound port `TaxInvoiceEventPublishingPort`

**Files:**
- Create: `src/main/java/com/wpanther/taxinvoice/processing/application/port/out/TaxInvoiceEventPublishingPort.java`

**Step 1: Write failing test**

Create: `src/test/java/com/wpanther/taxinvoice/processing/application/port/out/TaxInvoiceEventPublishingPortTest.java`

```java
package com.wpanther.taxinvoice.processing.application.port.out;

import com.wpanther.taxinvoice.processing.domain.event.TaxInvoiceProcessedDomainEvent;
import com.wpanther.taxinvoice.processing.domain.model.Money;
import com.wpanther.taxinvoice.processing.domain.model.TaxInvoiceId;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.mockito.Mockito.*;

class TaxInvoiceEventPublishingPortTest {

    @Test
    void shouldAcceptDomainEventPublishCall() {
        TaxInvoiceEventPublishingPort port = mock(TaxInvoiceEventPublishingPort.class);
        TaxInvoiceProcessedDomainEvent event = new TaxInvoiceProcessedDomainEvent(
            TaxInvoiceId.generate(), "INV-001",
            new Money(new BigDecimal("100.00"), "THB"),
            "corr-1", Instant.now()
        );

        port.publish(event);

        verify(port).publish(event);
    }
}
```

**Step 2: Run to confirm failure**

```bash
mvn test -Dtest=TaxInvoiceEventPublishingPortTest -q 2>&1 | tail -10
```

Expected: FAIL — interface doesn't exist.

**Step 3: Create the interface**

```java
package com.wpanther.taxinvoice.processing.application.port.out;

import com.wpanther.taxinvoice.processing.domain.event.TaxInvoiceProcessedDomainEvent;

/**
 * Outbound port — application layer uses this to publish the TaxInvoiceProcessedDomainEvent.
 * Implemented by infrastructure/adapter/out/messaging/TaxInvoiceEventPublisher.
 * The adapter translates the pure domain event into a Kafka DTO before writing to the outbox.
 */
public interface TaxInvoiceEventPublishingPort {

    /**
     * Publish a domain event indicating a tax invoice was successfully processed.
     * Must be called within an active transaction (MANDATORY propagation on the adapter).
     */
    void publish(TaxInvoiceProcessedDomainEvent event);
}
```

**Step 4: Run test to confirm pass**

```bash
mvn test -Dtest=TaxInvoiceEventPublishingPortTest -q 2>&1 | tail -10
```

Expected: BUILD SUCCESS.

**Step 5: Update `EventPublisher` to implement the new port**

In `infrastructure/messaging/EventPublisher.java`:
- Add `implements TaxInvoiceEventPublishingPort` to class declaration
- Add import: `application.port.out.TaxInvoiceEventPublishingPort`
- Rename method `publishTaxInvoiceProcessed(TaxInvoiceProcessedEvent event)` → `publish(TaxInvoiceProcessedDomainEvent domainEvent)`
- The method still builds `TaxInvoiceProcessedEvent` internally from the domain event fields

The updated method signature:
```java
@Override
@Transactional(propagation = Propagation.MANDATORY)
public void publish(TaxInvoiceProcessedDomainEvent domainEvent) {
    TaxInvoiceProcessedEvent kafkaEvent = new TaxInvoiceProcessedEvent(
        domainEvent.invoiceId().value().toString(),
        domainEvent.invoiceNumber(),
        domainEvent.total().amount(),
        domainEvent.total().currency(),
        domainEvent.correlationId()
    );

    Map<String, String> headers = Map.of(
        "correlationId", domainEvent.correlationId(),
        "invoiceNumber", domainEvent.invoiceNumber()
    );

    outboxService.saveWithRouting(
        kafkaEvent,
        "ProcessedTaxInvoice",
        domainEvent.invoiceId().value().toString(),
        "taxinvoice.processed",
        domainEvent.invoiceId().value().toString(),
        headerSerializer.toJson(headers)
    );

    log.info("Published TaxInvoiceProcessedEvent to outbox: {}", domainEvent.invoiceNumber());
}
```

Add required imports to `EventPublisher.java`:
```java
import com.wpanther.taxinvoice.processing.application.port.out.TaxInvoiceEventPublishingPort;
import com.wpanther.taxinvoice.processing.domain.event.TaxInvoiceProcessedDomainEvent;
```

**Step 6: Compile**

```bash
mvn compile -q 2>&1 | head -30
```

Fix any issues with `TaxInvoiceId.value()` — check actual method name on `TaxInvoiceId` (may be `.getValue()` or it may extend UUID — use the correct accessor).

**Step 7: Commit**

```bash
git add src/main/java/com/wpanther/taxinvoice/processing/application/port/ \
        src/main/java/com/wpanther/taxinvoice/processing/infrastructure/messaging/ \
        src/test/java/com/wpanther/taxinvoice/processing/application/port/
git commit -m "application: add port/in use case interfaces and port/out event/reply ports"
```

---

### Task 10: Refactor `TaxInvoiceProcessingService` to implement use cases

This is the most significant change. The service stops taking `EventPublisher` (infrastructure) as a dependency and instead takes `TaxInvoiceEventPublishingPort` (application port). It implements both use case interfaces. Saga reply logic moves here from `SagaCommandHandler`.

**Files:**
- Modify: `src/main/java/com/wpanther/taxinvoice/processing/application/service/TaxInvoiceProcessingService.java`
- Modify: `src/test/java/com/wpanther/taxinvoice/processing/application/service/TaxInvoiceProcessingServiceTest.java`

**Step 1: Write failing tests for the new use case interface behaviour**

Add these test methods to `TaxInvoiceProcessingServiceTest.java`. First update the mock setup in the test class:

```java
// Replace existing @Mock declarations with:
@Mock ProcessedTaxInvoiceRepository invoiceRepository;     // domain/port/out
@Mock TaxInvoiceParserPort parserPort;                     // domain/port/out
@Mock SagaReplyPort sagaReplyPort;                         // application/port/out
@Mock TaxInvoiceEventPublishingPort eventPublishingPort;   // application/port/out

@InjectMocks TaxInvoiceProcessingService service;

// Add these imports:
import com.wpanther.taxinvoice.processing.application.port.in.ProcessTaxInvoiceUseCase;
import com.wpanther.taxinvoice.processing.application.port.in.CompensateTaxInvoiceUseCase;
import com.wpanther.taxinvoice.processing.application.port.out.SagaReplyPort;
import com.wpanther.taxinvoice.processing.application.port.out.TaxInvoiceEventPublishingPort;
import com.wpanther.taxinvoice.processing.domain.port.out.ProcessedTaxInvoiceRepository;
import com.wpanther.taxinvoice.processing.domain.port.out.TaxInvoiceParserPort;
import com.wpanther.saga.domain.enums.SagaStep;
```

Add new test methods:

```java
@Test
void process_shouldPublishSuccessReply_onSuccess() throws Exception {
    String documentId = "doc-001";
    String xmlContent = "<xml/>";
    String sagaId = "saga-001";
    String correlationId = "corr-001";

    when(invoiceRepository.findBySourceInvoiceId(documentId)).thenReturn(Optional.empty());
    ProcessedTaxInvoice invoice = buildCompletableInvoice(documentId);
    when(parserPort.parse(xmlContent, documentId)).thenReturn(invoice);
    when(invoiceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    service.process(documentId, xmlContent, sagaId, SagaStep.PROCESS_TAX_INVOICE, correlationId);

    verify(sagaReplyPort).publishSuccess(sagaId, SagaStep.PROCESS_TAX_INVOICE, correlationId);
    verify(eventPublishingPort).publish(any(TaxInvoiceProcessedDomainEvent.class));
}

@Test
void process_shouldPublishFailureReply_onParsingException() throws Exception {
    String documentId = "doc-002";
    when(invoiceRepository.findBySourceInvoiceId(documentId)).thenReturn(Optional.empty());
    when(parserPort.parse(any(), any()))
        .thenThrow(new TaxInvoiceParserPort.TaxInvoiceParsingException("bad xml"));

    service.process(documentId, "<bad/>", "saga-002", SagaStep.PROCESS_TAX_INVOICE, "corr-002");

    verify(sagaReplyPort).publishFailure(eq("saga-002"), eq(SagaStep.PROCESS_TAX_INVOICE),
        eq("corr-002"), contains("bad xml"));
    verify(eventPublishingPort, never()).publish(any());
}

@Test
void process_shouldBeIdempotent_whenAlreadyProcessed() throws Exception {
    String documentId = "doc-003";
    ProcessedTaxInvoice existing = buildCompletedInvoice(documentId);
    when(invoiceRepository.findBySourceInvoiceId(documentId)).thenReturn(Optional.of(existing));

    service.process(documentId, "<xml/>", "saga-003", SagaStep.PROCESS_TAX_INVOICE, "corr-003");

    verify(parserPort, never()).parse(any(), any());
    verify(sagaReplyPort).publishSuccess("saga-003", SagaStep.PROCESS_TAX_INVOICE, "corr-003");
}

@Test
void compensate_shouldDeleteInvoiceAndPublishCompensated() {
    String documentId = "doc-004";
    ProcessedTaxInvoice existing = buildCompletedInvoice(documentId);
    when(invoiceRepository.findBySourceInvoiceId(documentId)).thenReturn(Optional.of(existing));

    service.compensate(documentId, "saga-004", SagaStep.PROCESS_TAX_INVOICE, "corr-004");

    verify(invoiceRepository).deleteById(existing.getId());
    verify(sagaReplyPort).publishCompensated("saga-004", SagaStep.PROCESS_TAX_INVOICE, "corr-004");
}

@Test
void compensate_shouldBeIdempotent_whenInvoiceNotFound() {
    String documentId = "doc-005";
    when(invoiceRepository.findBySourceInvoiceId(documentId)).thenReturn(Optional.empty());

    service.compensate(documentId, "saga-005", SagaStep.PROCESS_TAX_INVOICE, "corr-005");

    verify(invoiceRepository, never()).deleteById(any());
    verify(sagaReplyPort).publishCompensated("saga-005", SagaStep.PROCESS_TAX_INVOICE, "corr-005");
}
```

**Step 2: Run to confirm failure**

```bash
mvn test -Dtest=TaxInvoiceProcessingServiceTest -q 2>&1 | tail -20
```

Expected: FAIL — service doesn't implement the interfaces yet and doesn't have the right dependencies.

**Step 3: Replace `TaxInvoiceProcessingService` with new implementation**

```java
package com.wpanther.taxinvoice.processing.application.service;

import com.wpanther.saga.domain.enums.SagaStep;
import com.wpanther.taxinvoice.processing.application.port.in.CompensateTaxInvoiceUseCase;
import com.wpanther.taxinvoice.processing.application.port.in.ProcessTaxInvoiceUseCase;
import com.wpanther.taxinvoice.processing.application.port.out.SagaReplyPort;
import com.wpanther.taxinvoice.processing.application.port.out.TaxInvoiceEventPublishingPort;
import com.wpanther.taxinvoice.processing.domain.event.TaxInvoiceProcessedDomainEvent;
import com.wpanther.taxinvoice.processing.domain.model.ProcessedTaxInvoice;
import com.wpanther.taxinvoice.processing.domain.model.ProcessingStatus;
import com.wpanther.taxinvoice.processing.domain.model.TaxInvoiceId;
import com.wpanther.taxinvoice.processing.domain.port.out.ProcessedTaxInvoiceRepository;
import com.wpanther.taxinvoice.processing.domain.port.out.TaxInvoiceParserPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Application service implementing both use case inbound ports.
 * Coordinates domain logic, outbound ports (repository, parser, event publishing, saga replies).
 * Zero imports from infrastructure — dependency rule enforced by package structure.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TaxInvoiceProcessingService
        implements ProcessTaxInvoiceUseCase, CompensateTaxInvoiceUseCase {

    private final ProcessedTaxInvoiceRepository invoiceRepository;
    private final TaxInvoiceParserPort parserPort;
    private final SagaReplyPort sagaReplyPort;
    private final TaxInvoiceEventPublishingPort eventPublishingPort;

    @Override
    @Transactional
    public void process(String documentId, String xmlContent,
                        String sagaId, SagaStep sagaStep, String correlationId) {
        log.info("Processing tax invoice for saga={} document={}", sagaId, documentId);
        try {
            // Idempotency: skip if already processed
            if (invoiceRepository.findBySourceInvoiceId(documentId).isPresent()) {
                log.warn("Tax invoice already processed for document={}, replying SUCCESS", documentId);
                sagaReplyPort.publishSuccess(sagaId, sagaStep, correlationId);
                return;
            }

            // Parse XML → domain object (PENDING status)
            ProcessedTaxInvoice invoice = parserPort.parse(xmlContent, documentId);

            // PENDING → PROCESSING
            invoice.startProcessing();
            invoiceRepository.save(invoice);

            // PROCESSING → COMPLETED (raises TaxInvoiceProcessedDomainEvent)
            invoice.markCompleted(correlationId);
            invoiceRepository.save(invoice);

            // Drain domain events → outbound event port
            invoice.domainEvents().forEach(e -> {
                if (e instanceof TaxInvoiceProcessedDomainEvent domainEvent) {
                    eventPublishingPort.publish(domainEvent);
                }
            });
            invoice.clearDomainEvents();

            sagaReplyPort.publishSuccess(sagaId, sagaStep, correlationId);
            log.info("Successfully processed tax invoice={} for saga={}", invoice.getInvoiceNumber(), sagaId);

        } catch (Exception e) {
            log.error("Failed to process tax invoice for saga={} document={}: {}",
                sagaId, documentId, e.getMessage(), e);
            sagaReplyPort.publishFailure(sagaId, sagaStep, correlationId, e.getMessage());
        }
    }

    @Override
    @Transactional
    public void compensate(String documentId, String sagaId,
                           SagaStep sagaStep, String correlationId) {
        log.info("Compensating tax invoice for saga={} document={}", sagaId, documentId);
        invoiceRepository.findBySourceInvoiceId(documentId)
            .ifPresentOrElse(
                invoice -> {
                    invoiceRepository.deleteById(invoice.getId());
                    log.info("Deleted ProcessedTaxInvoice id={} for compensation", invoice.getId());
                },
                () -> log.info("No invoice found for document={} — already compensated or never processed", documentId)
            );
        sagaReplyPort.publishCompensated(sagaId, sagaStep, correlationId);
    }

    @Transactional(readOnly = true)
    public Optional<ProcessedTaxInvoice> findById(String id) {
        try {
            return invoiceRepository.findById(TaxInvoiceId.from(id));
        } catch (IllegalArgumentException e) {
            log.warn("Invalid tax invoice ID format: {}", id);
            return Optional.empty();
        }
    }

    @Transactional(readOnly = true)
    public List<ProcessedTaxInvoice> findByStatus(ProcessingStatus status) {
        return invoiceRepository.findByStatus(status);
    }
}
```

**Step 4: Run tests**

```bash
mvn test -Dtest=TaxInvoiceProcessingServiceTest -q 2>&1 | tail -20
```

Expected: BUILD SUCCESS.

**Step 5: Run full suite**

```bash
mvn test -q 2>&1 | tail -20
```

Fix any remaining compile errors from old references to `EventPublisher` or `parserService`.

**Step 6: Commit**

```bash
git add src/main/java/com/wpanther/taxinvoice/processing/application/ \
        src/test/java/com/wpanther/taxinvoice/processing/application/
git commit -m "application: refactor TaxInvoiceProcessingService to implement use case ports, drain domain events"
```

---

## STEP 3: Infrastructure Inbound Adapter

> After this step: `SagaCommandHandler` and `SagaRouteConfig` live in `infrastructure/adapter/in/messaging/`. Command DTOs are in `infrastructure/adapter/in/messaging/dto/`. `application/service/SagaCommandHandler` is deleted.

---

### Task 11: Create command DTOs in `infrastructure/adapter/in/messaging/dto/`

**Files:**
- Create: `src/main/java/com/wpanther/taxinvoice/processing/infrastructure/adapter/in/messaging/dto/ProcessTaxInvoiceCommand.java`
- Create: `src/main/java/com/wpanther/taxinvoice/processing/infrastructure/adapter/in/messaging/dto/CompensateTaxInvoiceCommand.java`
- Delete later: `src/main/java/com/wpanther/taxinvoice/processing/domain/event/ProcessTaxInvoiceCommand.java`
- Delete later: `src/main/java/com/wpanther/taxinvoice/processing/domain/event/CompensateTaxInvoiceCommand.java`

**Step 1: Create `ProcessTaxInvoiceCommand` with new package**

Copy the existing class verbatim, changing only the package line:

```java
package com.wpanther.taxinvoice.processing.infrastructure.adapter.in.messaging.dto;

// All existing imports and class body unchanged
```

**Step 2: Create `CompensateTaxInvoiceCommand` with new package**

Copy the existing class verbatim, changing only the package line:

```java
package com.wpanther.taxinvoice.processing.infrastructure.adapter.in.messaging.dto;

// All existing imports and class body unchanged
```

**Step 3: Compile**

```bash
mvn compile -q 2>&1 | head -20
```

Expected: success (old classes still exist, new ones just added).

---

### Task 12: Create new `SagaCommandHandler` in `infrastructure/adapter/in/messaging/`

This is a new, slim driving adapter. It injects the two use case interfaces only.

**Files:**
- Create: `src/main/java/com/wpanther/taxinvoice/processing/infrastructure/adapter/in/messaging/SagaCommandHandler.java`
- Delete later: `src/main/java/com/wpanther/taxinvoice/processing/application/service/SagaCommandHandler.java`

**Step 1: Write failing test**

Create: `src/test/java/com/wpanther/taxinvoice/processing/infrastructure/adapter/in/messaging/SagaCommandHandlerTest.java`

```java
package com.wpanther.taxinvoice.processing.infrastructure.adapter.in.messaging;

import com.wpanther.saga.domain.enums.SagaStep;
import com.wpanther.taxinvoice.processing.application.port.in.CompensateTaxInvoiceUseCase;
import com.wpanther.taxinvoice.processing.application.port.in.ProcessTaxInvoiceUseCase;
import com.wpanther.taxinvoice.processing.infrastructure.adapter.in.messaging.dto.CompensateTaxInvoiceCommand;
import com.wpanther.taxinvoice.processing.infrastructure.adapter.in.messaging.dto.ProcessTaxInvoiceCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SagaCommandHandlerTest {

    @Mock ProcessTaxInvoiceUseCase processTaxInvoiceUseCase;
    @Mock CompensateTaxInvoiceUseCase compensateTaxInvoiceUseCase;

    @InjectMocks SagaCommandHandler handler;

    @Test
    void handleProcessCommand_shouldDelegateToUseCase() {
        ProcessTaxInvoiceCommand cmd = new ProcessTaxInvoiceCommand(
            "saga-1", SagaStep.PROCESS_TAX_INVOICE, "corr-1", "doc-1", "<xml/>", "INV-001"
        );

        handler.handleProcessCommand(cmd);

        verify(processTaxInvoiceUseCase).process(
            "doc-1", "<xml/>", "saga-1", SagaStep.PROCESS_TAX_INVOICE, "corr-1"
        );
    }

    @Test
    void handleCompensation_shouldDelegateToUseCase() {
        CompensateTaxInvoiceCommand cmd = new CompensateTaxInvoiceCommand(
            "saga-2", SagaStep.PROCESS_TAX_INVOICE, "corr-2", SagaStep.PROCESS_TAX_INVOICE, "doc-2", "TAX_INVOICE"
        );

        handler.handleCompensation(cmd);

        verify(compensateTaxInvoiceUseCase).compensate(
            "doc-2", "saga-2", SagaStep.PROCESS_TAX_INVOICE, "corr-2"
        );
    }
}
```

**Step 2: Run to confirm failure**

```bash
mvn test -Dtest="com.wpanther.taxinvoice.processing.infrastructure.adapter.in.messaging.SagaCommandHandlerTest" -q 2>&1 | tail -15
```

Expected: FAIL — class doesn't exist.

**Step 3: Create the new `SagaCommandHandler`**

```java
package com.wpanther.taxinvoice.processing.infrastructure.adapter.in.messaging;

import com.wpanther.taxinvoice.processing.application.port.in.CompensateTaxInvoiceUseCase;
import com.wpanther.taxinvoice.processing.application.port.in.ProcessTaxInvoiceUseCase;
import com.wpanther.taxinvoice.processing.infrastructure.adapter.in.messaging.dto.CompensateTaxInvoiceCommand;
import com.wpanther.taxinvoice.processing.infrastructure.adapter.in.messaging.dto.ProcessTaxInvoiceCommand;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Primary (driving) adapter — translates Kafka command DTOs into use case calls.
 * No business logic here. Pure translation and delegation.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SagaCommandHandler {

    private final ProcessTaxInvoiceUseCase processTaxInvoiceUseCase;
    private final CompensateTaxInvoiceUseCase compensateTaxInvoiceUseCase;

    public void handleProcessCommand(ProcessTaxInvoiceCommand cmd) {
        log.info("Received ProcessTaxInvoiceCommand saga={} document={}",
            cmd.getSagaId(), cmd.getDocumentId());
        processTaxInvoiceUseCase.process(
            cmd.getDocumentId(),
            cmd.getXmlContent(),
            cmd.getSagaId(),
            cmd.getSagaStep(),
            cmd.getCorrelationId()
        );
    }

    public void handleCompensation(CompensateTaxInvoiceCommand cmd) {
        log.info("Received CompensateTaxInvoiceCommand saga={} document={}",
            cmd.getSagaId(), cmd.getDocumentId());
        compensateTaxInvoiceUseCase.compensate(
            cmd.getDocumentId(),
            cmd.getSagaId(),
            cmd.getSagaStep(),
            cmd.getCorrelationId()
        );
    }
}
```

**Step 4: Run test**

```bash
mvn test -Dtest="com.wpanther.taxinvoice.processing.infrastructure.adapter.in.messaging.SagaCommandHandlerTest" -q 2>&1 | tail -10
```

Expected: BUILD SUCCESS.

---

### Task 13: Move `SagaRouteConfig` to `infrastructure/adapter/in/messaging/`

**Files:**
- Create: `src/main/java/com/wpanther/taxinvoice/processing/infrastructure/adapter/in/messaging/SagaRouteConfig.java`
- Delete: `src/main/java/com/wpanther/taxinvoice/processing/infrastructure/config/SagaRouteConfig.java`

**Step 1: Create the new `SagaRouteConfig` in `adapter/in/messaging/`**

Copy the existing `SagaRouteConfig` verbatim, updating:
- Package: `infrastructure.adapter.in.messaging`
- Import for `SagaCommandHandler`: `infrastructure.adapter.in.messaging.SagaCommandHandler`
- Import for command DTOs: `infrastructure.adapter.in.messaging.dto.*`

```java
package com.wpanther.taxinvoice.processing.infrastructure.adapter.in.messaging;

import com.wpanther.taxinvoice.processing.infrastructure.adapter.in.messaging.dto.CompensateTaxInvoiceCommand;
import com.wpanther.taxinvoice.processing.infrastructure.adapter.in.messaging.dto.ProcessTaxInvoiceCommand;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.dataformat.JsonLibrary;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// ... rest of class body identical to current SagaRouteConfig
```

**Step 2: Delete old `SagaRouteConfig` and old `SagaCommandHandler` from application layer**

```bash
rm src/main/java/com/wpanther/taxinvoice/processing/infrastructure/config/SagaRouteConfig.java
rm src/main/java/com/wpanther/taxinvoice/processing/application/service/SagaCommandHandler.java
rmdir src/main/java/com/wpanther/taxinvoice/processing/infrastructure/config
```

**Step 3: Delete old command DTOs from domain/event**

```bash
rm src/main/java/com/wpanther/taxinvoice/processing/domain/event/ProcessTaxInvoiceCommand.java
rm src/main/java/com/wpanther/taxinvoice/processing/domain/event/CompensateTaxInvoiceCommand.java
```

**Step 4: Compile and run tests**

```bash
mvn test -q 2>&1 | tail -20
```

Fix any remaining import errors. Common issue: `SagaRouteConfigTest` imports from the old package.

**Step 5: Commit**

```bash
git add -A
git commit -m "infrastructure: move inbound adapters to adapter/in/messaging/, slim SagaCommandHandler to use case delegation"
```

---

## STEP 4: Infrastructure Outbound Adapters

> After this step: all driven adapters live under `infrastructure/adapter/out/`. Old `infrastructure/messaging/`, `infrastructure/persistence/`, `infrastructure/service/` packages are deleted.

---

### Task 14: Move messaging adapters to `infrastructure/adapter/out/messaging/`

**Files to create (new packages):**
- `infrastructure/adapter/out/messaging/SagaReplyPublisher.java`
- `infrastructure/adapter/out/messaging/TaxInvoiceEventPublisher.java` (renamed from `EventPublisher`)
- `infrastructure/adapter/out/messaging/HeaderSerializer.java`
- `infrastructure/adapter/out/messaging/dto/TaxInvoiceReplyEvent.java`
- `infrastructure/adapter/out/messaging/dto/TaxInvoiceProcessedEvent.java`

**Step 1: Move and rename `EventPublisher` → `TaxInvoiceEventPublisher`**

Create `infrastructure/adapter/out/messaging/TaxInvoiceEventPublisher.java` with:
- Package: `infrastructure.adapter.out.messaging`
- Class renamed: `TaxInvoiceEventPublisher`
- Implements: `TaxInvoiceEventPublishingPort`
- Import for DTO: `infrastructure.adapter.out.messaging.dto.TaxInvoiceProcessedEvent`
- All other logic unchanged

**Step 2: Move `SagaReplyPublisher`**

Create `infrastructure/adapter/out/messaging/SagaReplyPublisher.java` with:
- Package: `infrastructure.adapter.out.messaging`
- Implements: `application.port.out.SagaReplyPort`
- Import for DTO: `infrastructure.adapter.out.messaging.dto.TaxInvoiceReplyEvent`
- All logic unchanged

**Step 3: Move `HeaderSerializer`**

Create `infrastructure/adapter/out/messaging/HeaderSerializer.java` with updated package only.

**Step 4: Move outbound DTOs**

Create `infrastructure/adapter/out/messaging/dto/TaxInvoiceReplyEvent.java` — copy verbatim, change package.
Create `infrastructure/adapter/out/messaging/dto/TaxInvoiceProcessedEvent.java` — copy verbatim, change package.

**Step 5: Delete old messaging package**

```bash
rm src/main/java/com/wpanther/taxinvoice/processing/infrastructure/messaging/EventPublisher.java
rm src/main/java/com/wpanther/taxinvoice/processing/infrastructure/messaging/SagaReplyPublisher.java
rm src/main/java/com/wpanther/taxinvoice/processing/infrastructure/messaging/HeaderSerializer.java
rm src/main/java/com/wpanther/taxinvoice/processing/domain/event/TaxInvoiceReplyEvent.java
rm src/main/java/com/wpanther/taxinvoice/processing/domain/event/TaxInvoiceProcessedEvent.java
rmdir src/main/java/com/wpanther/taxinvoice/processing/infrastructure/messaging
# domain/event/ may now be empty (all 4 event classes moved)
rmdir src/main/java/com/wpanther/taxinvoice/processing/domain/event 2>/dev/null || true
```

**Step 6: Compile**

```bash
mvn compile -q 2>&1 | head -30
```

Fix any remaining import errors (tests referencing old packages — leave for Task 16).

---

### Task 15: Move persistence adapters to `infrastructure/adapter/out/persistence/`

**Step 1: Move and rename `ProcessedTaxInvoiceRepositoryImpl` → `ProcessedTaxInvoiceRepositoryAdapter`**

Create `infrastructure/adapter/out/persistence/ProcessedTaxInvoiceRepositoryAdapter.java`:
- Package: `infrastructure.adapter.out.persistence`
- Class renamed to `ProcessedTaxInvoiceRepositoryAdapter`
- Import for `ProcessedTaxInvoiceRepository`: `domain.port.out.ProcessedTaxInvoiceRepository`
- All logic unchanged

**Step 2: Move remaining persistence classes**

Move these to `infrastructure/adapter/out/persistence/` (package change only):
- `JpaProcessedTaxInvoiceRepository.java`
- `ProcessedTaxInvoiceEntity.java`
- `TaxInvoicePartyEntity.java`
- `TaxInvoiceLineItemEntity.java`
- `ProcessedTaxInvoiceMapper.java`

**Step 3: Delete old persistence package**

```bash
rm src/main/java/com/wpanther/taxinvoice/processing/infrastructure/persistence/ProcessedTaxInvoiceRepositoryImpl.java
rm src/main/java/com/wpanther/taxinvoice/processing/infrastructure/persistence/JpaProcessedTaxInvoiceRepository.java
rm src/main/java/com/wpanther/taxinvoice/processing/infrastructure/persistence/ProcessedTaxInvoiceEntity.java
rm src/main/java/com/wpanther/taxinvoice/processing/infrastructure/persistence/TaxInvoicePartyEntity.java
rm src/main/java/com/wpanther/taxinvoice/processing/infrastructure/persistence/TaxInvoiceLineItemEntity.java
rm src/main/java/com/wpanther/taxinvoice/processing/infrastructure/persistence/ProcessedTaxInvoiceMapper.java
rmdir src/main/java/com/wpanther/taxinvoice/processing/infrastructure/persistence
```

---

### Task 16: Move parser adapter to `infrastructure/adapter/out/parsing/`

**Files:**
- Create: `src/main/java/com/wpanther/taxinvoice/processing/infrastructure/adapter/out/parsing/TaxInvoiceParserAdapter.java`
- Delete: `src/main/java/com/wpanther/taxinvoice/processing/infrastructure/service/TaxInvoiceParserServiceImpl.java`

**Step 1: Create `TaxInvoiceParserAdapter`**

Copy `TaxInvoiceParserServiceImpl` verbatim with:
- Package: `infrastructure.adapter.out.parsing`
- Class renamed: `TaxInvoiceParserAdapter`
- Implements: `TaxInvoiceParserPort` (already updated in Task 5)
- Method renamed: `parseInvoice` → `parse` (already updated in Task 5)
- All JAXB parsing logic unchanged

**Step 2: Delete old service**

```bash
rm src/main/java/com/wpanther/taxinvoice/processing/infrastructure/service/TaxInvoiceParserServiceImpl.java
rmdir src/main/java/com/wpanther/taxinvoice/processing/infrastructure/service
```

---

### Task 17: Move outbox classes to `infrastructure/adapter/out/outbox/`

Move these to `infrastructure/adapter/out/outbox/` (package change only):
- `SpringDataOutboxRepository.java`
- `JpaOutboxEventRepository.java`
- `OutboxEventEntity.java`

Then delete:

```bash
rm src/main/java/com/wpanther/taxinvoice/processing/infrastructure/persistence/outbox/SpringDataOutboxRepository.java
rm src/main/java/com/wpanther/taxinvoice/processing/infrastructure/persistence/outbox/JpaOutboxEventRepository.java
rm src/main/java/com/wpanther/taxinvoice/processing/infrastructure/persistence/outbox/OutboxEventEntity.java
rmdir src/main/java/com/wpanther/taxinvoice/processing/infrastructure/persistence/outbox 2>/dev/null || true
```

**Run full compile and test after Tasks 14–17:**

```bash
mvn test -q 2>&1 | tail -30
```

Expected: BUILD SUCCESS (test import errors handled in Task 18).

**Commit:**

```bash
git add -A
git commit -m "infrastructure: move all outbound adapters to adapter/out/, rename RepositoryImpl→RepositoryAdapter, EventPublisher→TaxInvoiceEventPublisher, ParserServiceImpl→TaxInvoiceParserAdapter"
```

---

## STEP 5: Test Updates

> After this step: all tests compile against new package paths. `mvn verify` passes with 100% JaCoCo coverage.

---

### Task 18: Update all test file imports

**Files to update** (scan for broken imports using compile):

```bash
mvn test-compile 2>&1 | grep "cannot find symbol\|package.*does not exist" | sort -u
```

For each error, update the import in the corresponding test file. Common patterns:

| Old import | New import |
|---|---|
| `domain.repository.ProcessedTaxInvoiceRepository` | `domain.port.out.ProcessedTaxInvoiceRepository` |
| `domain.service.TaxInvoiceParserService` | `domain.port.out.TaxInvoiceParserPort` |
| `domain.port.SagaReplyPort` | `application.port.out.SagaReplyPort` |
| `domain.event.ProcessTaxInvoiceCommand` | `infrastructure.adapter.in.messaging.dto.ProcessTaxInvoiceCommand` |
| `domain.event.CompensateTaxInvoiceCommand` | `infrastructure.adapter.in.messaging.dto.CompensateTaxInvoiceCommand` |
| `domain.event.TaxInvoiceReplyEvent` | `infrastructure.adapter.out.messaging.dto.TaxInvoiceReplyEvent` |
| `domain.event.TaxInvoiceProcessedEvent` | `infrastructure.adapter.out.messaging.dto.TaxInvoiceProcessedEvent` |
| `application.service.SagaCommandHandler` | `infrastructure.adapter.in.messaging.SagaCommandHandler` |
| `infrastructure.config.SagaRouteConfig` | `infrastructure.adapter.in.messaging.SagaRouteConfig` |
| `infrastructure.messaging.EventPublisher` | `infrastructure.adapter.out.messaging.TaxInvoiceEventPublisher` |
| `infrastructure.messaging.SagaReplyPublisher` | `infrastructure.adapter.out.messaging.SagaReplyPublisher` |
| `infrastructure.messaging.HeaderSerializer` | `infrastructure.adapter.out.messaging.HeaderSerializer` |
| `infrastructure.persistence.*` | `infrastructure.adapter.out.persistence.*` |
| `infrastructure.persistence.outbox.*` | `infrastructure.adapter.out.outbox.*` |
| `infrastructure.service.TaxInvoiceParserServiceImpl` | `infrastructure.adapter.out.parsing.TaxInvoiceParserAdapter` |

**Test class renames:**

| Old test class | New test class | Move file? |
|---|---|---|
| `SagaCommandHandlerTest` (in `application/service/`) | Move to `infrastructure/adapter/in/messaging/` | Yes |
| `TaxInvoiceParserServiceImplTest` | Rename to `TaxInvoiceParserAdapterTest`, move to `adapter/out/parsing/` | Yes |
| `EventPublisherTest` | Rename to `TaxInvoiceEventPublisherTest`, move to `adapter/out/messaging/` | Yes |
| `SagaRouteConfigTest` | Move to `infrastructure/adapter/in/messaging/` | Yes |

**For `SagaRouteConfigTest`:** Update `@Autowired SagaRouteConfig` — the new route config is in `adapter/in/messaging/`. The Camel route IDs (`saga-command-consumer`, `saga-compensation-consumer`) are unchanged, so route existence tests pass without change.

**Step: Run compile and test loop until clean**

```bash
mvn test-compile 2>&1 | grep "error" | head -20
# Fix errors, repeat until:
mvn test -q 2>&1 | tail -10
# Expected: BUILD SUCCESS
```

---

### Task 19: Final verification — `mvn verify` with JaCoCo

**Step 1: Run full verify with coverage**

```bash
cd /home/wpanther/projects/etax/invoice-microservices/services/taxinvoice-processing-service
mvn verify -q 2>&1 | tail -30
```

Expected: BUILD SUCCESS with all JaCoCo coverage checks passing (100% per package).

**Step 2: If coverage fails, identify uncovered lines**

```bash
# Open coverage report
ls target/site/jacoco/
# Check for packages below threshold
mvn verify 2>&1 | grep "FAILED\|Coverage"
```

New packages that need coverage:
- `application/port/in/` — interfaces; covered by `TaxInvoiceProcessingServiceTest` which tests implementations
- `application/port/out/TaxInvoiceEventPublishingPort` — covered by `TaxInvoiceEventPublishingPortTest`
- `domain/event/TaxInvoiceProcessedDomainEvent` — covered by `TaxInvoiceProcessedDomainEventTest`
- `infrastructure/adapter/in/messaging/` — covered by `SagaCommandHandlerTest` and `SagaRouteConfigTest`
- `infrastructure/adapter/out/parsing/` — covered by `TaxInvoiceParserAdapterTest`
- `infrastructure/adapter/out/messaging/` — covered by `SagaReplyPublisherTest` and `TaxInvoiceEventPublisherTest`
- `infrastructure/adapter/out/persistence/` — covered by `ProcessedTaxInvoiceRepositoryAdapterTest` and entity tests
- `infrastructure/adapter/out/outbox/` — covered by `JpaOutboxEventRepositoryTest`

Add targeted tests for any uncovered method.

**Step 3: Final commit**

```bash
git add -A
git commit -m "test: update all test imports and class locations for hexagonal architecture migration"
```

---

## Final Checklist

Run this after all tasks complete:

```bash
# 1. No old packages remain
find src/main -path "*/domain/repository*" -o -path "*/domain/service*" -o \
     -path "*/infrastructure/config*" -o -path "*/infrastructure/messaging*" -o \
     -path "*/infrastructure/service*" 2>/dev/null
# Expected: no output

# 2. No infrastructure imports in domain or application service
grep -r "infrastructure" src/main/java/com/wpanther/taxinvoice/processing/domain/ 2>/dev/null
grep -r "infrastructure" src/main/java/com/wpanther/taxinvoice/processing/application/service/ 2>/dev/null
# Expected: no output

# 3. No domain/event DTOs with Kafka deps remain in domain
grep -r "kafka\|Jackson\|JsonProperty" src/main/java/com/wpanther/taxinvoice/processing/domain/ 2>/dev/null
# Expected: no output

# 4. Full build and coverage pass
mvn verify -q 2>&1 | tail -5
# Expected: BUILD SUCCESS
```
