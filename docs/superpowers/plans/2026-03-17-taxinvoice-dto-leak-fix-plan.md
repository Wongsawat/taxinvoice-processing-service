# TaxInvoiceEventPublishingPort DTO Leak Fix Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix hexagonal architecture violation where TaxInvoiceEventPublishingPort leaks infrastructure DTO into application layer by introducing a pure domain event.

**Architecture:** Create TaxInvoiceProcessedDomainEvent in domain.event (pure Java record), move TaxInvoiceProcessedEvent (Kafka event) to application.dto.event, update port to accept domain event, update adapter to transform domain → Kafka.

**Tech Stack:** Java 21, Spring Boot 3.2.5, JPA, Kafka, Outbox Pattern

---

## File Structure

```
src/main/java/com/wpanther/taxinvoice/processing/
├── domain/
│   └── event/
│       └── TaxInvoiceProcessedDomainEvent.java    [NEW] Pure domain event
├── application/
│   ├── dto/event/
│   │   └── TaxInvoiceProcessedEvent.java         [MOVE] From infrastructure
│   ├── port/out/
│   │   └── TaxInvoiceEventPublishingPort.java   [MODIFY] Accept domain event
│   └── service/
│       └── TaxInvoiceProcessingService.java      [MODIFY] Create domain event
└── infrastructure/
    └── adapter/out/messaging/
        ├── TaxInvoiceEventPublisher.java         [MODIFY] Transform domain → Kafka
        └── dto/
            └── (TaxInvoiceProcessedEvent deleted after move)
```

---

## Tasks

### Task 1: Create Domain Event

**Files:**
- Create: `src/main/java/com/wpanther/taxinvoice/processing/domain/event/TaxInvoiceProcessedDomainEvent.java`

- [ ] **Step 1: Create directory and file**

```bash
mkdir -p src/main/java/com/wpanther/taxinvoice/processing/domain/event
```

Create file with content:
```java
package com.wpanther.taxinvoice.processing.domain.event;

import com.wpanther.taxinvoice.processing.domain.model.TaxInvoiceId;
import com.wpanther.taxinvoice.processing.domain.model.Money;

import java.time.Instant;

/**
 * Domain event raised when tax invoice processing completes.
 * Pure Java record — no framework or infrastructure dependencies.
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

- [ ] **Step 2: Compile to verify**

```bash
mvn compile -q
```

Expected: SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/wpanther/taxinvoice/processing/domain/event/
git commit -m "feat: add TaxInvoiceProcessedDomainEvent in domain.event"
```

---

### Task 2: Move Kafka Event to Application Layer

**Files:**
- Move: `src/main/java/com/wpanther/taxinvoice/processing/infrastructure/adapter/out/messaging/dto/TaxInvoiceProcessedEvent.java` → `src/main/java/com/wpanther/taxinvoice/processing/application/dto/event/TaxInvoiceProcessedEvent.java`

- [ ] **Step 1: Create target directory**

```bash
mkdir -p src/main/java/com/wpanther/taxinvoice/processing/application/dto/event
```

- [ ] **Step 2: Read source file**

Read: `src/main/java/com/wpanther/taxinvoice/processing/infrastructure/adapter/out/messaging/dto/TaxInvoiceProcessedEvent.java`

- [ ] **Step 3: Create new file with updated package**

Create: `src/main/java/com/wpanther/taxinvoice/processing/application/dto/event/TaxInvoiceProcessedEvent.java`

Change package from:
```java
package com.wpanther.taxinvoice.processing.infrastructure.adapter.out.messaging.dto;
```
To:
```java
package com.wpanther.taxinvoice.processing.application.dto.event;
```

Keep all other content identical.

- [ ] **Step 4: Compile to verify**

```bash
mvn compile -q
```

Expected: SUCCESS

- [ ] **Step 5: Delete old file**

```bash
rm src/main/java/com/wpanther/taxinvoice/processing/infrastructure/adapter/out/messaging/dto/TaxInvoiceProcessedEvent.java
```

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/wpanther/taxinvoice/processing/application/dto/event/
git add -u src/main/java/com/wpanther/taxinvoice/processing/infrastructure/adapter/out/messaging/dto/
git commit -m "refactor: move TaxInvoiceProcessedEvent to application.dto.event"
```

---

### Task 3: Update Port Interface

**Files:**
- Modify: `src/main/java/com/wpanther/taxinvoice/processing/application/port/out/TaxInvoiceEventPublishingPort.java`

- [ ] **Step 1: Read current port interface**

Read: `src/main/java/com/wpanther/taxinvoice/processing/application/port/out/TaxInvoiceEventPublishingPort.java`

- [ ] **Step 2: Modify to accept domain event**

Replace the import:
```java
import com.wpanther.taxinvoice.processing.infrastructure.adapter.out.messaging.dto.TaxInvoiceProcessedEvent;
```
With:
```java
import com.wpanther.taxinvoice.processing.domain.event.TaxInvoiceProcessedDomainEvent;
```

Replace method signature:
```java
void publish(TaxInvoiceProcessedEvent event);
```
With:
```java
void publish(TaxInvoiceProcessedDomainEvent event);
```

Update javadoc comment to reflect the change.

- [ ] **Step 3: Compile to verify**

```bash
mvn compile -q
```

Expected: SUCCESS (TaxInvoiceEventPublisher will fail - that's expected)

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/wpanther/taxinvoice/processing/application/port/out/TaxInvoiceEventPublishingPort.java
git commit -m "refactor: update TaxInvoiceEventPublishingPort to accept domain event"
```

---

### Task 4: Update Adapter (Transformer)

**Files:**
- Modify: `src/main/java/com/wpanther/taxinvoice/processing/infrastructure/adapter/out/messaging/TaxInvoiceEventPublisher.java`

- [ ] **Step 1: Read current adapter**

Read: `src/main/java/com/wpanther/taxinvoice/processing/infrastructure/adapter/out/messaging/TaxInvoiceEventPublisher.java`

- [ ] **Step 2: Update imports**

Replace:
```java
import com.wpanther.taxinvoice.processing.infrastructure.adapter.out.messaging.dto.TaxInvoiceProcessedEvent;
```
With:
```java
import com.wpanther.taxinvoice.processing.application.dto.event.TaxInvoiceProcessedEvent;
import com.wpanther.taxinvoice.processing.domain.event.TaxInvoiceProcessedDomainEvent;
```

- [ ] **Step 3: Modify publish method**

Replace the publish method to accept domain event and transform to Kafka event:

```java
@Override
@Transactional(propagation = Propagation.MANDATORY)
public void publish(TaxInvoiceProcessedDomainEvent domainEvent) {
    // Transform: domain event → Kafka event
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

- [ ] **Step 4: Compile to verify**

```bash
mvn compile -q
```

Expected: SUCCESS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/wpanther/taxinvoice/processing/infrastructure/adapter/out/messaging/TaxInvoiceEventPublisher.java
git commit -m "refactor: update TaxInvoiceEventPublisher to transform domain → Kafka event"
```

---

### Task 5: Update Application Service

**Files:**
- Modify: `src/main/java/com/wpanther/taxinvoice/processing/application/service/TaxInvoiceProcessingService.java`

- [ ] **Step 1: Read current service**

Read: `src/main/java/com/wpanther/taxinvoice/processing/application/service/TaxInvoiceProcessingService.java`

Focus on lines around 150-160 where TaxInvoiceProcessedEvent is created.

- [ ] **Step 2: Update imports**

Replace:
```java
import com.wpanther.taxinvoice.processing.infrastructure.adapter.out.messaging.dto.TaxInvoiceProcessedEvent;
```
With:
```java
import com.wpanther.taxinvoice.processing.domain.event.TaxInvoiceProcessedDomainEvent;
```

- [ ] **Step 3: Modify event creation**

Replace:
```java
TaxInvoiceProcessedEvent processedEvent = new TaxInvoiceProcessedEvent(
    saved.getId().toString(),
    saved.getInvoiceNumber(),
    saved.getTotal().amount(),
    saved.getCurrency(),
    correlationId
);
eventPublisher.publish(processedEvent);
```

With:
```java
TaxInvoiceProcessedDomainEvent domainEvent = new TaxInvoiceProcessedDomainEvent(
    saved.getId(),
    saved.getInvoiceNumber(),
    saved.getTotal(),
    correlationId,
    Instant.now()
);
eventPublisher.publish(domainEvent);
```

- [ ] **Step 4: Compile to verify**

```bash
mvn compile -q
```

Expected: SUCCESS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/wpanther/taxinvoice/processing/application/service/TaxInvoiceProcessingService.java
git commit -m "refactor: update TaxInvoiceProcessingService to use domain event"
```

---

### Task 6: Update Tests

**Files:**
- Modify: `src/test/java/com/wpanther/taxinvoice/processing/infrastructure/adapter/out/messaging/TaxInvoiceEventPublisherTest.java`
- Modify: `src/test/java/com/wpanther/taxinvoice/processing/application/service/TaxInvoiceProcessingServiceTest.java`

> **Note:** Also check for any other tests that import the old `infrastructure.adapter.out.messaging.dto.TaxInvoiceProcessedEvent` path and update them.

- [ ] **Step 1: Read current test**

Read: `src/test/java/com/wpanther/taxinvoice/processing/infrastructure/adapter/out/messaging/TaxInvoiceEventPublisherTest.java`

- [ ] **Step 2: Update imports and test to use domain event**

Update imports to use domain event:
```java
import com.wpanther.taxinvoice.processing.domain.event.TaxInvoiceProcessedDomainEvent;
```

Update test to create domain event instead of Kafka event:
```java
// Before:
TaxInvoiceProcessedEvent event = new TaxInvoiceProcessedEvent(...);

// After:
TaxInvoiceProcessedDomainEvent event = new TaxInvoiceProcessedDomainEvent(
    new TaxInvoiceId(UUID.fromString("...")),
    "INV-001",
    Money.of(1000.00, "THB"),
    "correlation-1",
    Instant.now()
);
```

- [ ] **Step 3: Run tests**

```bash
mvn test -Dtest=TaxInvoiceEventPublisherTest -q
```

Expected: PASS

- [ ] **Step 4: Run all tests**

```bash
mvn test -q
```

Expected: All tests pass

- [ ] **Step 5: Commit**

```bash
git add src/test/java/com/wpanther/taxinvoice/processing/infrastructure/adapter/out/messaging/TaxInvoiceEventPublisherTest.java
git commit -m "test: update TaxInvoiceEventPublisherTest to use domain event"
```

---

### Task 7: Verify and Finalize

- [ ] **Step 1: Run full test suite**

```bash
mvn verify -q
```

Expected: All tests pass, coverage maintained

- [ ] **Step 2: Check git status**

```bash
git status
```

- [ ] **Step 3: Final commit**

```bash
git commit --allow-empty -m "fix: resolve TaxInvoiceEventPublishingPort DTO leak"
git log --oneline -5
```

---

## Notes

- The Kafka topic (`taxinvoice.processed`) remains unchanged
- No changes needed to consumers of the Kafka event
- Domain layer now has zero external dependencies
- Transactional behavior unchanged (MANDATORY propagation)
