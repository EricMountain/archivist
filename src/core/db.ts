// One DynamoDBDocumentClient, one table name, read from MEDIA_TABLE. No handler and
// no repo function outside this module constructs a DynamoDBClient directly.
import { DynamoDBClient } from "@aws-sdk/client-dynamodb";
import { DynamoDBDocumentClient } from "@aws-sdk/lib-dynamodb";

let client: DynamoDBDocumentClient | undefined;

export function ddb(): DynamoDBDocumentClient {
  if (!client) {
    // DYNAMODB_ENDPOINT is unset in every deployed environment; it exists only so
    // repo tests can point at DynamoDB Local instead of a real table.
    const endpoint = process.env["DYNAMODB_ENDPOINT"];
    client = DynamoDBDocumentClient.from(
      new DynamoDBClient(endpoint ? { endpoint } : {}),
      { marshallOptions: { removeUndefinedValues: true } },
    );
  }
  return client;
}

export function tableName(): string {
  const name = process.env["MEDIA_TABLE"];
  if (!name) {
    throw new Error("MEDIA_TABLE environment variable is not set");
  }
  return name;
}

/** Reserved words that appear as real attribute names in this schema and therefore
 * always need ExpressionAttributeNames. See sample-data.md's "By path" query. */
export const RESERVED_ATTRS: Record<string, string> = {
  status: "#status",
  path: "#path",
  role: "#role",
  bytes: "#bytes",
  size: "#size",
};
