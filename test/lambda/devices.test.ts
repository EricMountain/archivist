// Exercises the /devices route handlers directly against DynamoDB Local + MinIO —
// same gate as the other lambda-level suites. Plan step 2.14: devices are
// auto-registered by `postUpload`'s create-asset path (see uploads.ts), then listed/
// edited/removed through these three routes.
import { describe, expect, it } from "vitest";
import { newUlid } from "../../src/core/ids";
import { toIsoUtc } from "../../src/core/time";
import { bootstrapUser } from "../../src/core/repo/session";
import { postUpload } from "../../src/lambda/api/routes/uploads";
import { deleteDeviceRoute, getDevices, patchDevice } from "../../src/lambda/api/routes/devices";
import type { ApiRequest } from "../../src/lambda/api/http";
import type { DeviceItem } from "../../src/core/items";

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
    displayName: "Devices Test",
    homeTz: "UTC",
  });
  return { userId, ownerId };
}

function req(
  ownerId: string,
  userId: string,
  params: Record<string, string> = {},
  body?: unknown,
): ApiRequest {
  return {
    method: "GET",
    path: "/devices",
    params,
    query: {},
    auth: { userId, ownerId, role: "owner" },
    requestId: newUlid(),
    rawBody: body === undefined ? undefined : JSON.stringify(body),
  };
}

function uploadReq(ownerId: string, userId: string, body: Record<string, unknown>): ApiRequest {
  return {
    method: "POST",
    path: "/uploads",
    params: {},
    query: {},
    auth: { userId, ownerId, role: "owner" },
    requestId: newUlid(),
    rawBody: JSON.stringify(body),
  };
}

function baseUploadBody(overrides: Record<string, unknown> = {}) {
  const stem = `2026/devices-test/${newUlid()}`;
  return {
    path: `${stem}.jpg`,
    plainBytes: 100,
    bytes: 116,
    mime: "image/jpeg",
    width: 100,
    height: 100,
    contentHash: `hmac-sha256:${newUlid()}`,
    takenAt: toIsoUtc(new Date()),
    takenAtSrc: "upload",
    tzOffsetMin: 0,
    tzSrc: "assumed-utc",
    encDek: "dek",
    encKeyId: "mk-test",
    encIv: "iv",
    encChunkSize: 0,
    ...overrides,
  };
}

describe.skipIf(!RUN)("GET /devices", () => {
  it("auto-registers a device on first sight, with no offset set", async () => {
    const { userId, ownerId } = await newOwner();
    const deviceKey = `canon|eos r5|${newUlid()}`;
    await postUpload(uploadReq(ownerId, userId, baseUploadBody({ deviceKey })));

    const res = await getDevices(req(ownerId, userId));
    const devices = (res.body as { devices: DeviceItem[] }).devices;
    const device = devices.find((d) => d.deviceKey === deviceKey);
    expect(device).toBeDefined();
    expect(device!.label).toBe(deviceKey);
    expect(device!.tzOffsetMin).toBeUndefined();
    expect(device!.photoCount).toBe(1);
    expect(device!.firstSeenAt).toBeTruthy();
  });

  it("counts one new asset per device, not one per rendition attach", async () => {
    const { userId, ownerId } = await newOwner();
    const deviceKey = `canon|eos r5|${newUlid()}`;
    const stem = `2026/devices-test/${newUlid()}`;
    await postUpload(
      uploadReq(ownerId, userId, baseUploadBody({ deviceKey, path: `${stem}.cr3` })),
    );
    // Same stem, different extension: attaches to the same asset rather than
    // creating a second one.
    await postUpload(
      uploadReq(
        ownerId,
        userId,
        baseUploadBody({ deviceKey, path: `${stem}.jpg`, contentHash: `hmac-sha256:${newUlid()}` }),
      ),
    );

    const res = await getDevices(req(ownerId, userId));
    const devices = (res.body as { devices: DeviceItem[] }).devices;
    expect(devices.find((d) => d.deviceKey === deviceKey)!.photoCount).toBe(1);
  });

  it("never registers a device for an upload with no deviceKey", async () => {
    const { userId, ownerId } = await newOwner();
    await postUpload(uploadReq(ownerId, userId, baseUploadBody({ deviceKey: undefined })));
    const res = await getDevices(req(ownerId, userId));
    expect((res.body as { devices: DeviceItem[] }).devices).toEqual([]);
  });
});

describe.skipIf(!RUN)("PATCH /devices/{deviceKey}", () => {
  it("sets a label and a timezone default", async () => {
    const { userId, ownerId } = await newOwner();
    const deviceKey = `canon|eos r5|${newUlid()}`;
    await postUpload(uploadReq(ownerId, userId, baseUploadBody({ deviceKey })));

    await patchDevice(
      req(ownerId, userId, { deviceKey }, { label: "Dad's R5", tzOffsetMin: 540 }),
    );

    const devices = (await getDevices(req(ownerId, userId))).body as { devices: DeviceItem[] };
    const device = devices.devices.find((d) => d.deviceKey === deviceKey);
    expect(device!.label).toBe("Dad's R5");
    expect(device!.tzOffsetMin).toBe(540);
  });

  it("clears a previously-set default with an explicit null", async () => {
    const { userId, ownerId } = await newOwner();
    const deviceKey = `canon|eos r5|${newUlid()}`;
    await postUpload(uploadReq(ownerId, userId, baseUploadBody({ deviceKey })));
    await patchDevice(req(ownerId, userId, { deviceKey }, { tzOffsetMin: 540 }));

    await patchDevice(req(ownerId, userId, { deviceKey }, { tzOffsetMin: null }));

    const devices = (await getDevices(req(ownerId, userId))).body as { devices: DeviceItem[] };
    expect(devices.devices.find((d) => d.deviceKey === deviceKey)!.tzOffsetMin).toBeUndefined();
  });

  it("404s on a device that was never seen", async () => {
    const { userId, ownerId } = await newOwner();
    await expect(
      patchDevice(req(ownerId, userId, { deviceKey: "nonexistent|device|1" }, { label: "x" })),
    ).rejects.toThrow(/not found/i);
  });

  it("rejects a non-numeric tzOffsetMin", async () => {
    const { userId, ownerId } = await newOwner();
    const deviceKey = `canon|eos r5|${newUlid()}`;
    await postUpload(uploadReq(ownerId, userId, baseUploadBody({ deviceKey })));
    await expect(
      patchDevice(req(ownerId, userId, { deviceKey }, { tzOffsetMin: "540" })),
    ).rejects.toThrow(/tzOffsetMin/);
  });
});

describe.skipIf(!RUN)("DELETE /devices/{deviceKey}", () => {
  it("removes the device row", async () => {
    const { userId, ownerId } = await newOwner();
    const deviceKey = `canon|eos r5|${newUlid()}`;
    await postUpload(uploadReq(ownerId, userId, baseUploadBody({ deviceKey })));

    await deleteDeviceRoute(req(ownerId, userId, { deviceKey }));

    const devices = (await getDevices(req(ownerId, userId))).body as { devices: DeviceItem[] };
    expect(devices.devices.find((d) => d.deviceKey === deviceKey)).toBeUndefined();
  });

  it("is idempotent on a device that never existed", async () => {
    const { userId, ownerId } = await newOwner();
    await expect(
      deleteDeviceRoute(req(ownerId, userId, { deviceKey: "nonexistent|device|1" })),
    ).resolves.not.toThrow();
  });
});
