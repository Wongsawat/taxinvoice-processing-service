# Race Condition Fix: Remove saveWithIdempotencyHandling Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the broken `saveWithIdempotencyHandling()` method that silently loses saga replies by writing to a `ROLLBACK_ONLY` transaction, and replace it with a direct save plus a distinct WARN log in the catch block.

**Architecture:** TDD — write the failing test first, then make the minimal code changes to pass it. Three files change: the service implementation, the use case interface Javadoc, and the test class. No schema, Camel route, or infrastructure changes required.

**Tech Stack:** Java 21, Spring Boot 3.2.5, JUnit 5, Mockito, Maven (`mvn test` / `mvn verify`)

---

## File Map

| File | Role | Action |
|------|------|--------|
| `src/test/java/com/wpanther/taxinvoice/processing/application/service/TaxInvoiceProcessingServiceTest.java` | Unit tests for the application service | Add one new test method |
| `src/main/java/com/wpanther/taxinvoice/processing/application/service/TaxInvoiceProcessingService.java` | Core service — contains the broken method | Remove `saveWithIdempotencyHandling()`, simplify call site, add `DataIntegrityViolationException` branch to catch block |
| `src/main/java/com/wpanther/taxinvoice/processing/application/port/in/ProcessTaxInvoiceUseCase.java` | Use case interface — contains stale Javadoc | Remove one stale sentence from Javadoc |

---

### Task 1: Write the failing test

**Files:**
- Modify: `src/test/java/com/wpanther/taxinvoice/processing/application/service/TaxInvoiceProcessingServiceTest.java`

- [ ] **Step 1.1: Open the test file and locate the insertion point**

  Open `TaxInvoiceProcessingServiceTest.java`. Scroll to the end of the class (just before the closing `}`). The new test goes here, after `testCompensateHandlesException`.

  Confirm these imports already exist at the top of the file — you will need them:
  ```java
  import org.springframework.dao.DataIntegrityViolationException;
  import static org.junit.jupiter.api.Assertions.assertInstanceOf;
  ```
  If either import is missing, add it.

- [ ] **Step 1.2: Add the new test method**

  Insert the following method at the end of the class body:

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

      // publishFailure is attempted (in real Spring context with ROLLBACK_ONLY
      // transaction this write would be lost, but the call must still be made)
      verify(sagaReplyPort).publishFailure(eq("saga-1"), eq(SagaStep.PROCESS_TAX_INVOICE),
          eq("correlation-123"), anyString());

      // Domain event never published (invoice not committed)
      verify(eventPublisher, never()).publish(any());
  }
  ```

- [ ] **Step 1.3: Run the new test to confirm it FAILS**

  ```bash
  cd /home/wpanther/projects/etax/invoice-microservices/services/taxinvoice-processing-service
  mvn test -Dtest=TaxInvoiceProcessingServiceTest#testProcessInvoiceForSagaDataIntegrityViolationPropagates -q
  ```

  **Expected**: The test FAILS. The current `saveWithIdempotencyHandling()` catches `DataIntegrityViolationException` silently, so:
  - No `TaxInvoiceProcessingException` is thrown (assertion fails), OR
  - `ex.getCause()` is not a `DataIntegrityViolationException`

  If the test somehow passes, stop and re-read `TaxInvoiceProcessingService.java` lines 170–187 before continuing.

---

### Task 2: Implement the fix in TaxInvoiceProcessingService

**Files:**
- Modify: `src/main/java/com/wpanther/taxinvoice/processing/application/service/TaxInvoiceProcessingService.java`

> **Context:** The current file has these sections you'll touch:
> - Line 26: `import org.springframework.dao.DataIntegrityViolationException;` — **keep this import**
> - Lines 86–118: `process()` method with the `CompletionException` catch block — **add new branch here**
> - Lines 120–168: `processInvoiceForSagaInternal()` — **replace the call site on lines 141–145**
> - Lines 170–187: `saveWithIdempotencyHandling()` — **delete this entire method**

- [ ] **Step 2.1: Replace the call site in `processInvoiceForSagaInternal`**

  Find this block (around line 141):
  ```java
  // Save with race condition handling for idempotency
  ProcessedTaxInvoice saved = saveWithIdempotencyHandling(invoice, documentId, sagaId, sagaStep, correlationId);
  if (saved == null) {
      // Race condition was handled - another thread already saved this invoice
      return invoiceRepository.findBySourceInvoiceId(documentId).orElseThrow();
  }
  ```

  Replace it with:
  ```java
  ProcessedTaxInvoice saved = invoiceRepository.save(invoice);
  ```

- [ ] **Step 2.2: Delete the `saveWithIdempotencyHandling` method**

  Find and delete the entire method (around lines 170–187):
  ```java
  /**
   * Save invoice with race condition handling for idempotency.
   * If another thread already saved this invoice (DataIntegrityViolationException),
   * returns null and the caller will fetch the existing invoice.
   */
  private ProcessedTaxInvoice saveWithIdempotencyHandling(ProcessedTaxInvoice invoice,
          String documentId, String sagaId, SagaStep sagaStep, String correlationId) {
      try {
          return invoiceRepository.save(invoice);
      } catch (DataIntegrityViolationException e) {
          // Race condition: another thread already saved this invoice (same sourceInvoiceId)
          // This is an idempotent success - fetch and return the existing invoice
          log.warn("Race condition detected for document {}, fetching existing invoice", documentId);
          // Still publish success for idempotent case
          sagaReplyPort.publishSuccess(sagaId, sagaStep, correlationId);
          return null;
      }
  }
  ```

  Delete every line of that method including the Javadoc.

- [ ] **Step 2.3: Add the `DataIntegrityViolationException` branch to the catch block in `process()`**

  In the `process()` method, find the `catch (CompletionException e)` block (around line 101). It currently starts like this:
  ```java
  } catch (CompletionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof TaxInvoiceParserPort.TaxInvoiceParsingException parseException) {
  ```

  Insert a new `if` branch **before** the existing `TaxInvoiceParsingException` check, so it becomes:
  ```java
  } catch (CompletionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof DataIntegrityViolationException divException) {
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
              "Duplicate document: " + divException.getMessage()); // best-effort; write will be rolled back
          throw new TaxInvoiceProcessingException(
              "Duplicate key for document: " + documentId, divException);
      } else if (cause instanceof TaxInvoiceParserPort.TaxInvoiceParsingException parseException) {
  ```

  Leave the rest of the catch block (`TaxInvoiceParsingException`, `RuntimeException`, and unknown cause branches) exactly as they are.

- [ ] **Step 2.4: Run the new test to confirm it now PASSES**

  ```bash
  cd /home/wpanther/projects/etax/invoice-microservices/services/taxinvoice-processing-service
  mvn test -Dtest=TaxInvoiceProcessingServiceTest#testProcessInvoiceForSagaDataIntegrityViolationPropagates -q
  ```

  **Expected**: `BUILD SUCCESS` — test passes.

- [ ] **Step 2.5: Run the full service test suite to confirm no regressions**

  ```bash
  mvn test -Dtest=TaxInvoiceProcessingServiceTest -q
  ```

  **Expected**: All tests in `TaxInvoiceProcessingServiceTest` pass (was 14 tests, now 15).

---

### Task 3: Fix stale Javadoc in ProcessTaxInvoiceUseCase

**Files:**
- Modify: `src/main/java/com/wpanther/taxinvoice/processing/application/port/in/ProcessTaxInvoiceUseCase.java`

- [ ] **Step 3.1: Remove the stale sentence from the Javadoc**

  Open `ProcessTaxInvoiceUseCase.java`. Find this line in the Javadoc (around line 16):
  ```
   * Race conditions (DataIntegrityViolationException) are treated as idempotent success.
  ```

  Delete that line entirely. The updated Javadoc comment block should read:
  ```java
  /**
   * Process a tax invoice as part of a saga command.
   * Parses XML, validates, calculates totals, saves to DB, publishes notification event.
   * Handles idempotency, parses XML, persists, raises domain events, publishes saga reply.
   *
   * @param documentId    Source document ID (used for idempotency)
   * @param xmlContent    Raw XML string to parse
   * @param sagaId        Saga instance identifier
   * @param sagaStep      Current step in the saga
   * @param correlationId Correlation ID for tracing
   */
  ```

---

### Task 4: Run full test suite and commit

**Files:** None changed — verification and commit only.

- [ ] **Step 4.1: Run the complete test suite**

  ```bash
  cd /home/wpanther/projects/etax/invoice-microservices/services/taxinvoice-processing-service
  mvn test -q
  ```

  **Expected**: `BUILD SUCCESS`. All 289 tests pass (288 existing + 1 new).

  If any test fails, read the failure output carefully. Do not proceed to the next step until all tests pass.

- [ ] **Step 4.2: Run verify to check JaCoCo coverage**

  ```bash
  mvn verify -q
  ```

  **Expected**: `BUILD SUCCESS`. JaCoCo 80% line coverage threshold met per package.

  If coverage drops below threshold, check which package is affected in the `target/site/jacoco/` report and add coverage if needed before committing.

- [ ] **Step 4.3: Commit**

  ```bash
  git add \
    src/main/java/com/wpanther/taxinvoice/processing/application/service/TaxInvoiceProcessingService.java \
    src/main/java/com/wpanther/taxinvoice/processing/application/port/in/ProcessTaxInvoiceUseCase.java \
    src/test/java/com/wpanther/taxinvoice/processing/application/service/TaxInvoiceProcessingServiceTest.java
  git commit -m "fix: remove broken DataIntegrityViolationException handler in TaxInvoiceProcessingService

  saveWithIdempotencyHandling() was calling publishSuccess() after the outer
  transaction was marked ROLLBACK_ONLY, silently losing the outbox write.
  Remove the method and add a DataIntegrityViolationException branch in the
  CompletionException catch block with a WARN log and best-effort publishFailure().
  The saga will time out in this near-impossible race condition case, which is
  recoverable. Partition affinity (correlationId as Kafka key) prevents the
  race from occurring in practice."
  ```

---

## Success Checklist

- [ ] `saveWithIdempotencyHandling()` method no longer exists in `TaxInvoiceProcessingService`
- [ ] `DataIntegrityViolationException` import retained (used by new catch branch)
- [ ] New `DataIntegrityViolationException` branch is the FIRST `if` in the `CompletionException` catch block (before `RuntimeException`, which it extends)
- [ ] Stale Javadoc sentence removed from `ProcessTaxInvoiceUseCase`
- [ ] 289 tests pass (`mvn test`)
- [ ] `mvn verify` passes (JaCoCo 80% threshold met)
- [ ] WARN log message mentions both `documentId` and `sagaId`
- [ ] Known limitation comment present in the new catch branch
