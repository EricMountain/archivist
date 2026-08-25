// Enumerates the owner registry — see "Owner registry" in core/keys.ts. The
// purge sweep (plan step 1.13) is the only consumer: it's the one job that has
// to visit every owner in the deployment.
import { QueryCommand } from "@aws-sdk/lib-dynamodb";
import { ddb, tableName } from "../db";
import { ownerRegistryPk } from "../keys";

export async function listAllOwnerIds(): Promise<string[]> {
  const ownerIds: string[] = [];
  let exclusiveStartKey: Record<string, unknown> | undefined;

  do {
    const res = await ddb().send(
      new QueryCommand({
        TableName: tableName(),
        KeyConditionExpression: "pk = :p",
        ExpressionAttributeValues: { ":p": ownerRegistryPk() },
        ExclusiveStartKey: exclusiveStartKey,
      }),
    );
    for (const item of res.Items ?? []) {
      ownerIds.push(item["ownerId"] as string);
    }
    exclusiveStartKey = res.LastEvaluatedKey;
  } while (exclusiveStartKey);

  return ownerIds;
}
