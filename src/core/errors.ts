// Small error hierarchy shared by the repo layer and every Lambda handler. Handlers
// map these to status codes; SDK exception messages must never reach a client.

export type ApiErrorCode =
  | "NOT_FOUND"
  | "CONFLICT"
  | "VALIDATION"
  | "UNAUTHORIZED"
  | "FORBIDDEN"
  | "INTERNAL";

/** A finer-grained reason a client can act on, sent as `code` in the JSON error body
 * next to `error`. Only set where the status alone is ambiguous: a 409 can be a genuine
 * conflict (a claimed path, an asset already trashed) that will fail again on retry,
 * or `TRANSACTION_CONFLICT` — DynamoDB cancelled the transaction because another one
 * was writing the same item at that moment (typically a shared counter row: `HIST`,
 * `#PLACES`). Nothing was written, and the identical request can be retried. See
 * api.md "Errors". */
export type ApiErrorReason = "TRANSACTION_CONFLICT";

export class ApiError extends Error {
  readonly code: ApiErrorCode;
  readonly reason: ApiErrorReason | undefined;

  constructor(code: ApiErrorCode, message: string, reason?: ApiErrorReason) {
    super(message);
    this.name = "ApiError";
    this.code = code;
    this.reason = reason;
  }

  static notFound(message = "not found"): ApiError {
    return new ApiError("NOT_FOUND", message);
  }

  static conflict(message: string): ApiError {
    return new ApiError("CONFLICT", message);
  }

  /** 409 with `code: "TRANSACTION_CONFLICT"`: retryable as-is, nothing was written. */
  static transactionConflict(): ApiError {
    return new ApiError(
      "CONFLICT",
      "another write to the same items was in progress; retry",
      "TRANSACTION_CONFLICT",
    );
  }

  static validation(message: string): ApiError {
    return new ApiError("VALIDATION", message);
  }

  static unauthorized(message = "unauthorized"): ApiError {
    return new ApiError("UNAUTHORIZED", message);
  }

  static forbidden(message = "forbidden"): ApiError {
    return new ApiError("FORBIDDEN", message);
  }
}

export const HTTP_STATUS_BY_CODE: Record<ApiErrorCode, number> = {
  NOT_FOUND: 404,
  CONFLICT: 409,
  VALIDATION: 400,
  UNAUTHORIZED: 401,
  FORBIDDEN: 403,
  INTERNAL: 500,
};

/** The JSON body an `ApiError` is returned as: `{error}`, plus `code` when the error
 * carries a reason. */
export function apiErrorBody(err: ApiError): { error: string; code?: ApiErrorReason } {
  return err.reason ? { error: err.message, code: err.reason } : { error: err.message };
}

/**
 * Maps a failed `TransactWriteItems` (or single conditional write) to the 409 it
 * should surface as, or `undefined` when `err` isn't a cancellation at all and should
 * propagate unchanged.
 *
 * `TransactionCanceledException.CancellationReasons` has one entry per item. When
 * any item failed its condition, the conflict is genuine and `message` describes it.
 * When the only failures are `TransactionConflict` (another transaction held one of
 * the items, usually a shared counter row), nothing was written and the same request
 * can succeed on retry, so the error carries `TRANSACTION_CONFLICT`. A cancellation
 * without reasons is treated as genuine, as before this distinction existed.
 */
export function cancelledWriteError(err: unknown, message: string): ApiError | undefined {
  if (!(err instanceof Error)) return undefined;
  if (err.name === "ConditionalCheckFailedException") return ApiError.conflict(message);
  if (err.name !== "TransactionCanceledException") return undefined;
  const codes = ((err as { CancellationReasons?: Array<{ Code?: string }> }).CancellationReasons ?? [])
    .map((r) => r.Code)
    .filter((c): c is string => !!c && c !== "None");
  if (codes.length > 0 && codes.every((c) => c === "TransactionConflict")) {
    return ApiError.transactionConflict();
  }
  return ApiError.conflict(message);
}
