// cancelledWriteError/apiErrorBody are pure: they decide whether a cancelled
// DynamoDB write is a genuine 409 or a retryable TRANSACTION_CONFLICT, and what the
// client sees. No DynamoDB Local needed.
import { describe, expect, it } from "vitest";
import { ApiError, apiErrorBody, cancelledWriteError } from "../src/core/errors";

function cancelled(...codes: string[]): Error {
  return Object.assign(new Error("Transaction cancelled"), {
    name: "TransactionCanceledException",
    CancellationReasons: codes.map((Code) => ({ Code })),
  });
}

describe("cancelledWriteError", () => {
  it("only TransactionConflict reasons (besides None): retryable TRANSACTION_CONFLICT", () => {
    const err = cancelledWriteError(cancelled("None", "TransactionConflict", "None"), "stem taken");
    expect(err).toBeInstanceOf(ApiError);
    expect(err?.code).toBe("CONFLICT");
    expect(err?.reason).toBe("TRANSACTION_CONFLICT");
  });

  it("a failed condition anywhere is a genuine conflict, even next to a TransactionConflict", () => {
    const err = cancelledWriteError(cancelled("ConditionalCheckFailed", "TransactionConflict"), "stem taken");
    expect(err?.code).toBe("CONFLICT");
    expect(err?.reason).toBeUndefined();
    expect(err?.message).toBe("stem taken");
  });

  it("a cancellation with no reasons stays a genuine conflict", () => {
    const err = cancelledWriteError(cancelled(), "stem taken");
    expect(err?.reason).toBeUndefined();
    expect(err?.message).toBe("stem taken");
  });

  it("ConditionalCheckFailedException is a genuine conflict", () => {
    const e = Object.assign(new Error("x"), { name: "ConditionalCheckFailedException" });
    expect(cancelledWriteError(e, "gone")?.reason).toBeUndefined();
  });

  it("anything else isn't a cancellation and propagates", () => {
    expect(cancelledWriteError(new Error("boom"), "m")).toBeUndefined();
    expect(cancelledWriteError("not an error", "m")).toBeUndefined();
  });
});

describe("apiErrorBody", () => {
  it("adds code only when the error has a reason", () => {
    expect(apiErrorBody(ApiError.conflict("asset is already trashed"))).toEqual({ error: "asset is already trashed" });
    expect(apiErrorBody(ApiError.transactionConflict())).toEqual({
      error: "another write to the same items was in progress; retry",
      code: "TRANSACTION_CONFLICT",
    });
  });
});
