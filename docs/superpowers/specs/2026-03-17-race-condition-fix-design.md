# Design: Fix Race Condition Handler in TaxInvoiceProcessingService

**Date**: 2026-03-17
**Service**: `taxinvoice-processing-service`
**Approach**: Option B — Remove broken catch, add distinct WARN log, document known limitation

---

## Problem

`TaxInvoiceProcessingService.saveWithIdempotencyHandling()` catches `DataIntegrityViolationException` and attempts to call `sagaReplyPort.publishSuccess()` afterward. This is broken because:

1. `DataIntegrityViolationException` is an unchecked exception that propagates through `ProcessedTaxInvoiceRepositoryImpl.save()`'s `@Transactional` proxy boundary.
2. Spring marks the **shared outer transaction** as `ROLLBACK_ONLY` when this exception exits the proxy.
3. The subsequent `publishSuccess()` call (which uses `MANDATORY` propagation) writes to the outbox inside an already-poisoned transaction.
4. The transaction rolls back — taking the outbox entry with it. **No reply reaches the orchestrator.**

This race condition window exists because the Camel consumer runs 3 parallel threads (`consumersCount=3`). In practice, it is near-impossible because the orchestrator uses `correlationId` as the Kafka message key, routing all commands for the same saga to the same partition and consumer thread.

---

## Full Failure Chain Analysis

When `DataIntegrityViolationException` occurs (race condition):

```
invoiceRepository.save() throws DataIntegrityViolationException
  → exits @Transactional proxy of ProcessedTaxInvoiceRepositoryImpl
  → Spring marks shared outer transaction ROLLBACK_ONLY
  → wrapped in CompletionException, caught in process() catch block
  → publishFailure() called — BUT transaction is already ROLLBACK_ONLY
  → publishFailure() outbox write is also lost (same problem as publishSuccess())
  → TaxInvoiceProcessingException thrown
  → SagaCommandHandler.handleProcessCommand() catches it and swallows it
  → Camel sees a clean return, commits Kafka offset
  → No reply (success or failure) ever reaches the orchestrator
  → Saga times out via the orchestrator's timeout mechanism
```

**Note**: `SagaCommandHandler` swallows all `TaxInvoiceProcessingException`s by design (the handler assumes the use case has already published a reply). This means Camel's Dead Letter Channel is never triggered and does not retry in this case.

---

## Solution: Scope and Intent

The fix removes the broken, misleading `saveWithIdempotencyHandling()` method. The end behavior in the race condition case remains the same (saga times out), but the code:

- No longer silently attempts to write to a poisoned transaction (removing the `UnexpectedRollbackException` risk)
- No longer contains dead code that implies correctness it cannot deliver
- Adds a distinct `WARN` log so ops can detect race condition occurrences in log aggregators, even though no outbox entry is written

**Known Limitation**: In the extremely rare race condition case, both the success and failure outbox writes are lost (both execute in the `ROLLBACK_ONLY` transaction), and the saga times out. A complete fix would additionally require either:
- Making `SagaCommandHandler` re-throw retriable exceptions so Camel DLC can retry (on retry, the `findBySourceInvoiceId` idempotency check finds the committed record cleanly), or
- Publishing the failure reply in a separate `REQUIRES_NEW` transaction outside the rollback boundary.

These are out of scope for this fix because: (a) the race condition is near-impossible in production due to Kafka partition affinity, and (b) any saga timeout is recoverable — the orchestrator can be re-triggered. The primary goal here is correctness of the code path, not adding new retry machinery.

---

## Files Changed

| File | Change |
|------|--------|
| `application/service/TaxInvoiceProcessingService.java` | Remove `saveWithIdempotencyHandling()` method. Replace call site with direct `invoiceRepository.save(invoice)`. In the `CompletionException` catch block, add `instanceof DataIntegrityViolationException` branch with WARN log and best-effort `publishFailure()`. Retain `import org.springframework.dao.DataIntegrityViolationException` (required by the new branch). |
| `application/port/in/ProcessTaxInvoiceUseCase.java` | Remove stale sentence *"Race conditions (DataIntegrityViolationException) are treated as idempotent success."* from Javadoc. |
| `application/service/TaxInvoiceProcessingServiceTest.java` | Add `testProcessInvoiceForSagaDataIntegrityViolationPropagates` test. |

---

## Code Change Detail

### `TaxInvoiceProcessingService.java`

**Remove** `saveWithIdempotencyHandling()` entirely.

**Replace** the call site in `processInvoiceForSagaInternal`:

```java
// Before
ProcessedTaxInvoice saved = saveWithIdempotencyHandling(invoice, documentId, sagaId, sagaStep, correlationId);
if (saved == null) {
    return invoiceRepository.findBySourceInvoiceId(documentId).orElseThrow();
}

// After
ProcessedTaxInvoice saved = invoiceRepository.save(invoice);
```

**Add** a distinct WARN branch in the `CompletionException` catch block in `process()`:

```java
} catch (CompletionException e) {
    Throwable cause = e.getCause();
    if (cause instanceof DataIntegrityViolationException) {
        // Race condition: another thread already inserted this document.
        // The outer transaction is ROLLBACK_ONLY at this point, so the
        // publishFailure() outbox write below will also be rolled back and lost.
        // SagaCommandHandler swallows TaxInvoiceProcessingException, so Camel
        // will NOT retry. The saga will time out. This is acceptable given
        // partition affinity makes this case near-impossible in production.
        log.warn("Race condition (duplicate key) detected for document {}, saga {}. " +
                 "publishFailure() is attempted but will be lost (ROLLBACK_ONLY transaction); " +
                 "saga will time out.", documentId, sagaId);
        sagaReplyPort.publishFailure(sagaId, sagaStep, correlationId,
            "Duplicate document: " + cause.getMessage()); // best-effort; write will be rolled back
        throw new TaxInvoiceProcessingException(
            "Duplicate key for document: " + documentId, (DataIntegrityViolationException) cause);
    } else if (cause instanceof TaxInvoiceParserPort.TaxInvoiceParsingException parseException) {
        // ... existing handling unchanged
    } else if (cause instanceof RuntimeException runtimeException) {
        // ... existing handling unchanged
    }
    // ... rest unchanged
}
```

---

## New Test

**File**: `TaxInvoiceProcessingServiceTest.java`
**Method**: `testProcessInvoiceForSagaDataIntegrityViolationPropagates`

**Purpose**: Verify that `DataIntegrityViolationException` from `invoiceRepository.save()` propagates cleanly as `TaxInvoiceProcessingException`, that `publishFailure()` is attempted, and that `eventPublisher.publish()` is never called.

```java
@Test
void testProcessInvoiceForSagaDataIntegrityViolationPropagates() throws Exception {
    // Given - simulate race condition: idempotency check passes but insert conflicts
    when(invoiceRepository.findBySourceInvoiceId(anyString())).thenReturn(Optional.empty());
    when(parserService.parse(anyString(), anyString())).thenReturn(validInvoice);
    when(invoiceRepository.save(any(ProcessedTaxInvoice.class)))
        .thenThrow(new DataIntegrityViolationException("duplicate key: source_invoice_id"));

    // When / Then - exception propagates (no silent swallowing), with original cause preserved
    ProcessTaxInvoiceUseCase.TaxInvoiceProcessingException ex =
        assertThrows(ProcessTaxInvoiceUseCase.TaxInvoiceProcessingException.class,
            () -> service.process("intake-123", "<xml>test</xml>",
                                  "saga-1", SagaStep.PROCESS_TAX_INVOICE, "correlation-123"));
    assertInstanceOf(DataIntegrityViolationException.class, ex.getCause());

    // publishFailure is attempted (note: in real Spring context with ROLLBACK_ONLY
    // transaction, this write would be lost; the unit test verifies the call is made)
    verify(sagaReplyPort).publishFailure(eq("saga-1"), eq(SagaStep.PROCESS_TAX_INVOICE),
        eq("correlation-123"), anyString());

    // Domain event never published (invoice not committed)
    verify(eventPublisher, never()).publish(any());
}
```

**Why this is sufficient**: The retry success path (finding an existing record on retry) is already covered by `testProcessInvoiceForSagaAlreadyProcessed`. The unit test mocks the repository so it cannot verify transaction rollback behavior — that behavior is documented above.

---

## Success Criteria

- [ ] `saveWithIdempotencyHandling()` method no longer exists in `TaxInvoiceProcessingService`
- [ ] `DataIntegrityViolationException` import retained in `TaxInvoiceProcessingService` (required by the new `instanceof` check in the catch block)
- [ ] Stale Javadoc sentence removed from `ProcessTaxInvoiceUseCase`
- [ ] New test passes
- [ ] All existing 288 tests continue to pass (`mvn test`)
- [ ] `mvn verify` passes (JaCoCo **80%** coverage threshold per package met)
- [ ] Known limitation documented in code comments at the call site
