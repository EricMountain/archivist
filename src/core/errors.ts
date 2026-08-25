// Small error hierarchy shared by the repo layer and every Lambda handler. Handlers
// map these to status codes; SDK exception messages must never reach a client.

export type ApiErrorCode =
  | "NOT_FOUND"
  | "CONFLICT"
  | "VALIDATION"
  | "UNAUTHORIZED"
  | "FORBIDDEN"
  | "INTERNAL";

export class ApiError extends Error {
  readonly code: ApiErrorCode;

  constructor(code: ApiErrorCode, message: string) {
    super(message);
    this.name = "ApiError";
    this.code = code;
  }

  static notFound(message = "not found"): ApiError {
    return new ApiError("NOT_FOUND", message);
  }

  static conflict(message: string): ApiError {
    return new ApiError("CONFLICT", message);
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
