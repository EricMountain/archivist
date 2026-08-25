// Opaque pagination cursors. A GSI cursor carries all four attributes DynamoDB needs
// to resume a Query — both index keys *and* both table keys — never just the index
// half. Base64url so it's URL-safe with no padding to strip. See "Pagination" in
// design.md: the key shape is deliberately not exposed, so the sharding escape
// hatch described under "One partition per owner" can change it later without
// breaking clients.

export function encodeCursor(lastEvaluatedKey: Record<string, unknown>): string {
  const json = JSON.stringify(lastEvaluatedKey);
  return Buffer.from(json, "utf8").toString("base64url");
}

export function decodeCursor(cursor: string): Record<string, unknown> {
  let json: string;
  try {
    json = Buffer.from(cursor, "base64url").toString("utf8");
  } catch {
    throw new Error("malformed cursor");
  }
  try {
    const parsed = JSON.parse(json) as unknown;
    if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) {
      throw new Error("malformed cursor");
    }
    return parsed as Record<string, unknown>;
  } catch {
    throw new Error("malformed cursor");
  }
}
