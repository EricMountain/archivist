// Exercises the /settings route handlers directly against DynamoDB Local — same gate
// as the other lambda-level suites. Plan step 1.17.
import { describe, expect, it } from "vitest";
import { newUlid } from "../../src/core/ids";
import { bootstrapUser } from "../../src/core/repo/session";
import { getSettings, patchSettings } from "../../src/lambda/api/routes/settings";
import type { ApiRequest } from "../../src/lambda/api/http";

const RUN =
  !!process.env["DYNAMODB_ENDPOINT"] &&
  !!process.env["MEDIA_TABLE"] &&
  !!process.env["S3_ENDPOINT"] &&
  !!process.env["ORIGINALS_BUCKET"] &&
  !!process.env["DERIVED_BUCKET"];

async function newOwner() {
  const { userId, ownerId } = await bootstrapUser({
    issuer: "cognito",
    subject: newUlid(),
    displayName: "Settings Test",
    homeTz: "UTC",
  });
  return { userId, ownerId };
}

function req(ownerId: string, userId: string, body?: unknown): ApiRequest {
  return {
    method: "GET",
    path: "/settings",
    params: {},
    query: {},
    auth: { userId, ownerId, role: "owner" },
    requestId: newUlid(),
    rawBody: body === undefined ? undefined : JSON.stringify(body),
  };
}

describe.skipIf(!RUN)("GET /settings", () => {
  it("defaults stripLocationOnUpload to false with no attribute ever written", async () => {
    const { userId, ownerId } = await newOwner();
    const res = await getSettings(req(ownerId, userId));
    expect(res.body).toMatchObject({ homeTz: "UTC", stripLocationOnUpload: false });
  });
});

describe.skipIf(!RUN)("PATCH /settings", () => {
  it("persists stripLocationOnUpload and a subsequent GET reflects it", async () => {
    const { userId, ownerId } = await newOwner();

    await patchSettings(req(ownerId, userId, { stripLocationOnUpload: true }));

    const res = await getSettings(req(ownerId, userId));
    expect((res.body as { stripLocationOnUpload: boolean }).stripLocationOnUpload).toBe(true);
  });

  it("rejects an unrecognised field", async () => {
    const { userId, ownerId } = await newOwner();
    await expect(
      patchSettings(req(ownerId, userId, { homeTz: "Europe/Paris" })),
    ).rejects.toThrow(/unsupported field/i);
  });

  it("rejects a non-boolean stripLocationOnUpload", async () => {
    const { userId, ownerId } = await newOwner();
    await expect(
      patchSettings(req(ownerId, userId, { stripLocationOnUpload: "yes" })),
    ).rejects.toThrow(/stripLocationOnUpload/);
  });
});
