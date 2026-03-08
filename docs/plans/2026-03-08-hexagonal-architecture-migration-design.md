# Hexagonal Architecture Migration Design
**taxinvoice-processing-service**
Date: 2026-03-08

## Context

The `taxinvoice-processing-service` currently follows a DDD layered structure (domain → application → infrastructure) and is approximately 60% hexagonal. This document defines the target "textbook" Hexagonal Architecture (Ports and Adapters) and the layer-by-layer migration plan to achieve it.

## Goals

- Full restructure into strict Hexagonal Architecture with explicit `port/in/` and `port/out/` directories
- Domain layer: **only outbound ports** (business truth — repository, parser)
- Application layer: **both inbound ports** (use case interfaces) **and outbound ports** (orchestration infrastructure — event publishing, saga replies)
- Infrastructure: explicit `adapter/in/` (driving) and `adapter/out/` (driven) separation
- Introduce proper domain events raised by the aggregate
- Each migration step leaves the service compilable with tests green

## Decisions Made

| Question | Decision |
|---|---|
| Migration scope | Full restructure (Option A) |
| Port placement | Application layer owns in/out; Domain layer owns out only |
| SagaCommandHandler | Moves to `infrastructure/adapter/in/messaging/` — it is a driving adapter |
| Kafka DTOs | Split by direction: commands → `adapter/in/dto/`, replies → `adapter/out/dto/` |
| Domain events | Introduced — `ProcessedTaxInvoice` raises `TaxInvoiceProcessedDomainEvent` |
| Migration strategy | Layer-by-layer incremental (Approach B) |

---

## Target Package Structure

```
com.wpanther.taxinvoice.processing/
│
├── domain/
│   ├── model/
│   │   ├── ProcessedTaxInvoice.java        ← adds domainEvents + markCompleted(correlationId)
│   │   ├── ProcessingStatus.java
│   │   ├── TaxInvoiceId.java
│   │   ├── Money.java
│   │   ├── LineItem.java
│   │   ├── Party.java
│   │   ├── Address.java
│   │   └── TaxIdentifier.java
│   ├── event/                              ← NEW: pure domain events, zero framework deps
│   │   └── TaxInvoiceProcessedDomainEvent.java
│   └── port/
│       └── out/                            ← REPLACES domain/repository/ + domain/service/ + domain/port/
│           ├── ProcessedTaxInvoiceRepository.java
│           └── TaxInvoiceParserPort.java   ← renamed from TaxInvoiceParserService
│
├── application/
│   ├── port/
│   │   ├── in/                             ← NEW: driving/inbound use case interfaces
│   │   │   ├── ProcessTaxInvoiceUseCase.java
│   │   │   └── CompensateTaxInvoiceUseCase.java
│   │   └── out/                            ← REPLACES domain/port/SagaReplyPort + no-interface EventPublisher
│   │       ├── SagaReplyPort.java
│   │       └── TaxInvoiceEventPublishingPort.java
│   └── service/
│       └── TaxInvoiceProcessingService.java  ← implements both use case interfaces
│
└── infrastructure/
    ├── adapter/
    │   ├── in/
    │   │   └── messaging/                  ← PRIMARY ADAPTERS (driving)
    │   │       ├── SagaRouteConfig.java
    │   │       ├── SagaCommandHandler.java
    │   │       └── dto/
    │   │           ├── ProcessTaxInvoiceCommand.java
    │   │           └── CompensateTaxInvoiceCommand.java
    │   └── out/
    │       ├── messaging/                  ← SECONDARY ADAPTERS (driven - event publishing)
    │       │   ├── SagaReplyPublisher.java
    │       │   ├── TaxInvoiceEventPublisher.java
    │       │   ├── HeaderSerializer.java
    │       │   └── dto/
    │       │       ├── TaxInvoiceReplyEvent.java
    │       │       └── TaxInvoiceProcessedEvent.java
    │       ├── persistence/                ← SECONDARY ADAPTERS (driven - storage)
    │       │   ├── ProcessedTaxInvoiceRepositoryAdapter.java
    │       │   ├── JpaProcessedTaxInvoiceRepository.java
    │       │   ├── ProcessedTaxInvoiceEntity.java
    │       │   ├── TaxInvoicePartyEntity.java
    │       │   ├── TaxInvoiceLineItemEntity.java
    │       │   └── ProcessedTaxInvoiceMapper.java
    │       ├── outbox/                     ← SECONDARY ADAPTERS (driven - CDC outbox)
    │       │   ├── SpringDataOutboxRepository.java
    │       │   ├── JpaOutboxEventRepository.java
    │       │   └── OutboxEventEntity.java
    │       └── parsing/                    ← SECONDARY ADAPTERS (driven - XML parsing)
    │           └── TaxInvoiceParserAdapter.java
    └── config/
        └── ApplicationConfig.java          ← Spring @Configuration for explicit wiring if needed
```

---

## Section 1: Domain Layer

### 1.1 Consolidate outbound ports into `domain/port/out/`

- `ProcessedTaxInvoiceRepository` moves from `domain/repository/` → `domain/port/out/` (unchanged contract)
- `TaxInvoiceParserService` renamed to `TaxInvoiceParserPort`, moves to `domain/port/out/`
- Inner `TaxInvoiceParsingException` moves with `TaxInvoiceParserPort`
- Packages `domain/repository/`, `domain/service/`, `domain/port/` are deleted

```java
// domain/port/out/TaxInvoiceParserPort.java
public interface TaxInvoiceParserPort {
    ProcessedTaxInvoice parse(String xmlContent, String sourceInvoiceId)
        throws TaxInvoiceParsingException;

    class TaxInvoiceParsingException extends Exception {
        public TaxInvoiceParsingException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
```

### 1.2 New domain event

Pure Java record — no framework dependencies, no Kafka imports:

```java
// domain/event/TaxInvoiceProcessedDomainEvent.java
public record TaxInvoiceProcessedDomainEvent(
    TaxInvoiceId invoiceId,
    String invoiceNumber,
    Money total,
    String correlationId,
    Instant occurredAt
) {}
```

### 1.3 Aggregate raises domain event

`ProcessedTaxInvoice` gains a private `domainEvents` list. `markCompleted()` is updated to accept `correlationId` and appends the domain event:

```java
// Additions to ProcessedTaxInvoice
private final List<TaxInvoiceProcessedDomainEvent> domainEvents = new ArrayList<>();

public List<TaxInvoiceProcessedDomainEvent> domainEvents() {
    return Collections.unmodifiableList(domainEvents);
}

public void clearDomainEvents() {
    domainEvents.clear();
}

public void markCompleted(String correlationId) {
    if (this.status != ProcessingStatus.PROCESSING) {
        throw new IllegalStateException("Can only complete from PROCESSING state");
    }
    this.status = ProcessingStatus.COMPLETED;
    this.completedAt = Instant.now();
    domainEvents.add(new TaxInvoiceProcessedDomainEvent(
        this.id, this.invoiceNumber, this.getTotal(), correlationId, Instant.now()
    ));
}
```

All value objects, the Builder, and business invariants remain unchanged.

---

## Section 2: Application Layer

### 2.1 Inbound ports — `application/port/in/`

```java
// application/port/in/ProcessTaxInvoiceUseCase.java
public interface ProcessTaxInvoiceUseCase {
    void process(String documentId, String xmlContent,
                 String sagaId, String sagaStep, String correlationId);
}

// application/port/in/CompensateTaxInvoiceUseCase.java
public interface CompensateTaxInvoiceUseCase {
    void compensate(String documentId, String sagaId,
                    String sagaStep, String correlationId);
}
```

Driving adapters depend only on these interfaces — never on `TaxInvoiceProcessingService` directly.

### 2.2 Outbound ports — `application/port/out/`

```java
// application/port/out/SagaReplyPort.java  (moved from domain/port/)
public interface SagaReplyPort {
    void publishSuccess(String sagaId, String sagaStep, String correlationId);
    void publishFailure(String sagaId, String sagaStep, String correlationId, String errorMessage);
    void publishCompensated(String sagaId, String sagaStep, String correlationId);
}

// application/port/out/TaxInvoiceEventPublishingPort.java  (NEW)
public interface TaxInvoiceEventPublishingPort {
    void publish(TaxInvoiceProcessedDomainEvent event);
}
```

### 2.3 Use case implementation

`TaxInvoiceProcessingService` implements both use case interfaces. It drains domain events after each state transition:

```java
@Service
@RequiredArgsConstructor
public class TaxInvoiceProcessingService
        implements ProcessTaxInvoiceUseCase, CompensateTaxInvoiceUseCase {

    private final ProcessedTaxInvoiceRepository invoiceRepository;   // domain/port/out
    private final TaxInvoiceParserPort parserPort;                   // domain/port/out
    private final SagaReplyPort sagaReplyPort;                       // application/port/out
    private final TaxInvoiceEventPublishingPort eventPublishingPort; // application/port/out

    @Override
    @Transactional
    public void process(String documentId, String xmlContent,
                        String sagaId, String sagaStep, String correlationId) {
        try {
            if (invoiceRepository.findBySourceInvoiceId(documentId).isPresent()) {
                sagaReplyPort.publishSuccess(sagaId, sagaStep, correlationId);
                return;
            }
            ProcessedTaxInvoice invoice = parserPort.parse(xmlContent, documentId);
            invoice.startProcessing();
            invoiceRepository.save(invoice);
            invoice.markCompleted(correlationId);
            invoiceRepository.save(invoice);
            invoice.domainEvents().forEach(eventPublishingPort::publish);
            invoice.clearDomainEvents();
            sagaReplyPort.publishSuccess(sagaId, sagaStep, correlationId);
        } catch (Exception e) {
            log.error("Failed to process tax invoice documentId={}", documentId, e);
            sagaReplyPort.publishFailure(sagaId, sagaStep, correlationId, e.getMessage());
        }
    }

    @Override
    @Transactional
    public void compensate(String documentId, String sagaId,
                           String sagaStep, String correlationId) {
        invoiceRepository.findBySourceInvoiceId(documentId)
            .ifPresent(invoice -> invoiceRepository.deleteById(invoice.getId()));
        sagaReplyPort.publishCompensated(sagaId, sagaStep, correlationId);
    }
}
```

**Dependency rule:** `TaxInvoiceProcessingService` imports only from `domain/` and `application/port/`. Zero imports from `infrastructure/`.

---

## Section 3: Infrastructure Layer

### 3.1 Primary adapter — `infrastructure/adapter/in/messaging/`

`SagaCommandHandler` slimmed to pure DTO translation + use case delegation:

```java
@Component
@RequiredArgsConstructor
public class SagaCommandHandler {
    private final ProcessTaxInvoiceUseCase processTaxInvoiceUseCase;
    private final CompensateTaxInvoiceUseCase compensateTaxInvoiceUseCase;

    public void handleProcessCommand(ProcessTaxInvoiceCommand cmd) {
        processTaxInvoiceUseCase.process(
            cmd.getDocumentId(), cmd.getXmlContent(),
            cmd.getSagaId(), cmd.getSagaStep(), cmd.getCorrelationId()
        );
    }

    public void handleCompensation(CompensateTaxInvoiceCommand cmd) {
        compensateTaxInvoiceUseCase.compensate(
            cmd.getDocumentId(), cmd.getSagaId(),
            cmd.getSagaStep(), cmd.getCorrelationId()
        );
    }
}
```

`SagaRouteConfig` moves from `infrastructure/config/` → same package. DTOs move to `dto/` sub-package.

### 3.2 Secondary adapters — messaging

`TaxInvoiceEventPublisher` (renamed from `EventPublisher`) translates `TaxInvoiceProcessedDomainEvent` → Kafka DTO before writing to outbox:

```java
@Component
@RequiredArgsConstructor
public class TaxInvoiceEventPublisher implements TaxInvoiceEventPublishingPort {
    private final OutboxService outboxService;
    private final ObjectMapper objectMapper;

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
        outboxService.saveWithRouting(kafkaEvent, "ProcessedTaxInvoice",
            domainEvent.invoiceId().value().toString(),
            "taxinvoice.processed",
            domainEvent.invoiceId().value().toString(),
            HeaderSerializer.serialize(Map.of("correlationId", domainEvent.correlationId()))
        );
    }
}
```

`SagaReplyPublisher` implements `application/port/out/SagaReplyPort` (unchanged logic, updated implements clause).

### 3.3 Secondary adapters — persistence, outbox, parsing

- `ProcessedTaxInvoiceRepositoryImpl` → renamed `ProcessedTaxInvoiceRepositoryAdapter`, moved to `adapter/out/persistence/`
- `TaxInvoiceParserServiceImpl` → renamed `TaxInvoiceParserAdapter`, implements `TaxInvoiceParserPort`, moved to `adapter/out/parsing/`
- All outbox classes moved to `adapter/out/outbox/`

### 3.4 Infrastructure dependency rules

| Adapter | May import from | Must NOT import from |
|---|---|---|
| `adapter/in/messaging/` | `application/port/in/`, adapter `dto/` | `application/service/`, `domain/model/` directly |
| `adapter/out/messaging/` | `application/port/out/`, `domain/event/`, adapter `dto/` | `application/service/` |
| `adapter/out/persistence/` | `domain/port/out/`, `domain/model/` | `application/` |
| `adapter/out/parsing/` | `domain/port/out/`, `domain/model/` | `application/` |

---

## Section 4: Migration Sequence (Layer-by-Layer)

### Step 1 — Domain layer restructure
1. Create `domain/port/out/`
2. Move `ProcessedTaxInvoiceRepository` → `domain/port/out/`
3. Rename `TaxInvoiceParserService` → `TaxInvoiceParserPort`, move to `domain/port/out/`
4. Update `TaxInvoiceParserServiceImpl` import (temporary, renamed in Step 4)
5. Update `ProcessedTaxInvoiceRepositoryImpl` import
6. Delete `domain/repository/`, `domain/service/`, `domain/port/` packages
7. Create `domain/event/TaxInvoiceProcessedDomainEvent`
8. Add `domainEvents` list, `markCompleted(correlationId)`, `clearDomainEvents()` to `ProcessedTaxInvoice`
9. ✅ `mvn test` — all tests green

### Step 2 — Application layer restructure
1. Create `application/port/in/ProcessTaxInvoiceUseCase` and `CompensateTaxInvoiceUseCase`
2. Create `application/port/out/SagaReplyPort` (moved from `domain/port/`)
3. Create `application/port/out/TaxInvoiceEventPublishingPort`
4. Refactor `TaxInvoiceProcessingService` — implements both use cases, injects all four ports, drains domain events
5. Update `SagaReplyPublisher` implements clause → `application/port/out/SagaReplyPort`
6. Update `EventPublisher` implements clause → `TaxInvoiceEventPublishingPort` (rename in Step 4)
7. ✅ `mvn test` — all tests green

### Step 3 — Infrastructure inbound adapter
1. Create `infrastructure/adapter/in/messaging/`
2. Move `SagaRouteConfig` from `infrastructure/config/` → `infrastructure/adapter/in/messaging/`
3. Move `SagaCommandHandler` from `application/service/` → `infrastructure/adapter/in/messaging/`
4. Slim `SagaCommandHandler` — inject use case interfaces only, remove all business logic
5. Create `infrastructure/adapter/in/messaging/dto/` — move `ProcessTaxInvoiceCommand` + `CompensateTaxInvoiceCommand` from `domain/event/`
6. Update `SagaRouteConfig` imports
7. ✅ `mvn test` — all tests green

### Step 4 — Infrastructure outbound adapters
1. Create `infrastructure/adapter/out/messaging/` — move + rename `EventPublisher` → `TaxInvoiceEventPublisher`, update to translate domain event → Kafka DTO
2. Move `SagaReplyPublisher` + `HeaderSerializer` to `infrastructure/adapter/out/messaging/`
3. Create `infrastructure/adapter/out/messaging/dto/` — move `TaxInvoiceReplyEvent` + `TaxInvoiceProcessedEvent`
4. Create `infrastructure/adapter/out/persistence/` — move all persistence classes, rename `ProcessedTaxInvoiceRepositoryImpl` → `ProcessedTaxInvoiceRepositoryAdapter`
5. Create `infrastructure/adapter/out/parsing/` — move + rename `TaxInvoiceParserServiceImpl` → `TaxInvoiceParserAdapter`
6. Move outbox classes to `infrastructure/adapter/out/outbox/`
7. Delete empty packages: `infrastructure/config/`, `infrastructure/messaging/`, `infrastructure/persistence/`, `infrastructure/service/`, `domain/event/` (old DTOs)
8. ✅ `mvn verify` — full coverage check green

### Step 5 — Test updates (parallel with Steps 3–4)

| Test class | Change |
|---|---|
| `SagaCommandHandlerTest` | Move to `adapter/in/messaging/`, inject use case mocks |
| `TaxInvoiceProcessingServiceTest` | Update port mocks, verify domain event draining |
| `TaxInvoiceParserAdapterTest` | Rename, update package |
| `SagaRouteConfigTest` | Update package import only |
| `TaxInvoiceEventPublisherTest` | Rename, test domain event → Kafka DTO translation |
| All domain model tests | No changes |
| Integration tests | Update DTO imports only |

---

## Section 5: Testing Strategy

### Domain layer — pure unit tests, zero mocks

```java
@Test
void markCompleted_raisesTaxInvoiceProcessedDomainEvent() {
    ProcessedTaxInvoice invoice = buildValidInvoice();
    invoice.startProcessing();
    invoice.markCompleted("corr-123");

    assertThat(invoice.domainEvents()).hasSize(1);
    assertThat(invoice.domainEvents().get(0))
        .isInstanceOf(TaxInvoiceProcessedDomainEvent.class)
        .extracting(TaxInvoiceProcessedDomainEvent::correlationId)
        .isEqualTo("corr-123");
}
```

### Application layer — mocked ports, no Spring context

```java
@ExtendWith(MockitoExtension.class)
class TaxInvoiceProcessingServiceTest {
    @Mock ProcessedTaxInvoiceRepository invoiceRepository;
    @Mock TaxInvoiceParserPort parserPort;
    @Mock SagaReplyPort sagaReplyPort;
    @Mock TaxInvoiceEventPublishingPort eventPublishingPort;
    @InjectMocks TaxInvoiceProcessingService service;

    // Tests: process success, idempotency, parsing failure, compensation
}
```

### Infrastructure adapters — each isolated

| Adapter | Test approach |
|---|---|
| `TaxInvoiceParserAdapter` | Unit test with real JAXB + XML fixture |
| `ProcessedTaxInvoiceRepositoryAdapter` | `@DataJpaTest` with H2 |
| `TaxInvoiceEventPublisher` | Unit — mock `OutboxService`, verify domain event → DTO translation |
| `SagaReplyPublisher` | Unit — mock `OutboxService` |
| `SagaCommandHandler` | Unit — mock both use case interfaces |
| `SagaRouteConfig` | `@CamelSpringBootTest` |

### Integration tests
`KafkaConsumerIntegrationTest` and `TaxInvoiceCdcIntegrationTest` unchanged in logic — only import paths updated.

**Coverage:** JaCoCo 100% line coverage per package maintained throughout migration.
