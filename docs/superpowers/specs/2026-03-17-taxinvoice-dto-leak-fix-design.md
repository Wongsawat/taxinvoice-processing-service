# Design: Fix TaxInvoiceEventPublishingPort DTO Leak

**Date:** 2026-03-17
**Service:** taxinvoice-processing-service
**Issue:** #4 - TaxInvoiceEventPublishingPort leaks infrastructure DTO into application layer

## Problem Statement

The current `TaxInvoiceEventPublishingPort` interface in the application layer references `TaxInvoiceProcessedEvent`, which is located in `infrastructure.adapter.out.messaging.dto`. This violates hexagonal architecture because:

1. The application layer (port) depends on an infrastructure class
2. Dependencies point outward instead of inward
3. Changing the infrastructure (e.g., Kafka serialization) would require changes to the application layer

## Current Architecture

```
application/
  port/out/
    TaxInvoiceEventPublishingPort     ← Uses infrastructure DTO
                                        ↑
infrastructure/
  adapter/out/messaging/
    dto/
      TaxInvoiceProcessedEvent       ← Kafka event (extends TraceEvent)
```

## Target Architecture

Following the cleaner separation pattern (as suggested):

```
domain/
  event/
    TaxInvoiceProcessedDomainEvent   ← Pure domain event (no deps)
                                        ↑
application/
  port/out/
    TaxInvoiceEventPublishingPort     ← Accepts pure domain event
                                        ↑
  dto/event/
    TaxInvoiceProcessedEvent         ← Kafka event (infrastructure)
                                        ↑
infrastructure/
  adapter/out/messaging/
    TaxInvoiceEventPublisher         ← Transforms domain → Kafka
```

## Design

### 1. Create Domain Event (`domain.event.TaxInvoiceProcessedDomainEvent`)

**Location:** `src/main/java/com/wpanther/taxinvoice/processing/domain/event/TaxInvoiceProcessedDomainEvent.java`

A pure Java record with domain types:

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

### 2. Move Kafka Event to Application Layer

**Current Location:** `infrastructure.adapter.out.messaging.dto.TaxInvoiceProcessedEvent`
**New Location:** `application.dto.event.TaxInvoiceProcessedEvent`

Move the existing `TaxInvoiceProcessedEvent` (extends `TraceEvent`) to:
`src/main/java/com/wpanther/taxinvoice/processing/application/dto/event/TaxInvoiceProcessedEvent.java`

> **Note:** This deviates from invoice-processing-service which keeps the Kafka event in `domain.event`. The cleaner `application.dto.event` separation was specifically requested by the user for better architectural cleanliness (domain has zero dependencies).

### 3. Update Port Interface

**File:** `application.port.out.TaxInvoiceEventPublishingPort`

```java
package com.wpanther.taxinvoice.processing.application.port.out;

import com.wpanther.taxinvoice.processing.domain.event.TaxInvoiceProcessedDomainEvent;

/**
 * Outbound port for publishing tax invoice processed events.
 * Application layer publishes the TaxInvoiceProcessedDomainEvent (pure domain event).
 * Implementation: infrastructure/adapter/out/messaging/TaxInvoiceEventPublisher.
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

### 4. Update Adapter (Transformer)

**File:** `infrastructure.adapter.out.messaging.TaxInvoiceEventPublisher`

The adapter transforms the domain event to the Kafka event before writing to outbox:

```java
@Override
@Transactional(propagation = Propagation.MANDATORY)
public void publish(TaxInvoiceProcessedDomainEvent domainEvent) {
    // Transform: domain event → Kafka event
    TaxInvoiceProcessedEvent kafkaEvent = new TaxInvoiceProcessedEvent(
        domainEvent.invoiceId().value().toString(),  // UUID → String
        domainEvent.invoiceNumber(),
        domainEvent.total().amount(),
        domainEvent.total().currency(),
        domainEvent.correlationId()
    );

    // Write to outbox (existing logic)
    outboxService.saveWithRouting(
        kafkaEvent,
        "ProcessedTaxInvoice",
        domainEvent.invoiceId().value().toString(),
        "taxinvoice.processed",
        domainEvent.invoiceId().value().toString(),
        headerSerializer.toJson(headers)
    );
}
```

### 5. Update Application Service

**File:** `application.service.TaxInvoiceProcessingService`

Change from:
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

To:
```java
TaxInvoiceProcessedDomainEvent domainEvent = new TaxInvoiceProcessedDomainEvent(
    saved.getId(),                    // TaxInvoiceId (domain type)
    saved.getInvoiceNumber(),         // String
    saved.getTotal(),                 // Money (domain type)
    correlationId,                    // String
    Instant.now()                     // Instant
);
eventPublisher.publish(domainEvent);
```

> **Note:** This uses manual event creation in the service. An alternative (matching invoice-processing-service) would be to add a `domainEvents` list to the `ProcessedTaxInvoice` aggregate and raise events via `markCompleted(correlationId)`. That pattern requires more extensive domain model changes, so the simpler manual approach is used here.

## Files to Modify

| Action | File |
|--------|------|
| Create | `domain/event/TaxInvoiceProcessedDomainEvent.java` |
| Move | `infrastructure/.../dto/TaxInvoiceProcessedEvent.java` → `application/dto/event/TaxInvoiceProcessedEvent.java` |
| Modify | `application/port/out/TaxInvoiceEventPublishingPort.java` |
| Modify | `infrastructure/adapter/out/messaging/TaxInvoiceEventPublisher.java` |
| Modify | `application/service/TaxInvoiceProcessingService.java` |
| Delete | `infrastructure/adapter/out/messaging/dto/TaxInvoiceProcessedEvent.java` (after move) |

## Testing Strategy

1. **Unit tests for domain event**: Verify pure domain event creation
2. **Unit tests for port/adapter**: Verify transformation logic
3. **Integration tests**: Verify Kafka event is published correctly
4. **Existing tests**: Update to use new domain event

## Alternative Considered

**Option A (chosen):** Domain event in `domain.event`, Kafka event in `application.dto.event`
- Pros: Clean separation, domain layer has zero dependencies
- Cons: More files to manage

**Option B:** Keep Kafka event in `infrastructure`, create domain event only
- Pros: Less movement of files
- Cons: Doesn't fully fix the architectural violation

## Dependencies

- No new dependencies required
- Uses existing `Money` and `TaxInvoiceId` domain types
- Uses existing `OutboxService` and `HeaderSerializer`

## Backward Compatibility

- The Kafka topic (`taxinvoice.processed`) remains unchanged
- Consumers of the Kafka event are not affected
- Only internal architecture changes
