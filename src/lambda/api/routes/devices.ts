// GET /devices, PATCH /devices/{deviceKey}, DELETE /devices/{deviceKey} — plan step
// 2.14's "Devices: list, edit timezone defaults, remove" settings section. See
// `src/core/repo/devices.ts` for the actual reads/writes; `deviceKey` itself is opaque
// here (`<make>|<model>|<serial>`, lowercased — see design.md) and never validated
// against any format, since it's a path segment the server itself handed back via
// `GET /devices`/`GET /photos`, not something a client constructs.
import { ApiError } from "@archivist/core/errors";
import { deleteDevice, listDevices, updateDevice } from "@archivist/core/repo/devices";
import { noContent, ok, parseJsonBody } from "../http";
import type { ApiRequest, RouteHandler } from "../http";

export const getDevices: RouteHandler = async (req: ApiRequest) => {
  const ownerId = req.auth!.ownerId;
  const devices = await listDevices(ownerId);
  return ok({ devices });
};

interface PatchDeviceBody {
  label?: string;
  /** `null` clears a previously-set default; omitted leaves it untouched. */
  tzOffsetMin?: number | null;
}

export const patchDevice: RouteHandler = async (req: ApiRequest) => {
  const ownerId = req.auth!.ownerId;
  const deviceKey = req.params["deviceKey"];
  if (!deviceKey) throw ApiError.validation("deviceKey is required");
  const body = parseJsonBody<PatchDeviceBody>(req);

  if (body.label !== undefined && typeof body.label !== "string") {
    throw ApiError.validation("label must be a string");
  }
  if (
    body.tzOffsetMin !== undefined &&
    body.tzOffsetMin !== null &&
    (typeof body.tzOffsetMin !== "number" || !Number.isFinite(body.tzOffsetMin))
  ) {
    throw ApiError.validation("tzOffsetMin must be a number or null");
  }

  await updateDevice(ownerId, deviceKey, { label: body.label, tzOffsetMin: body.tzOffsetMin });
  return noContent();
};

export const deleteDeviceRoute: RouteHandler = async (req: ApiRequest) => {
  const ownerId = req.auth!.ownerId;
  const deviceKey = req.params["deviceKey"];
  if (!deviceKey) throw ApiError.validation("deviceKey is required");
  await deleteDevice(ownerId, deviceKey);
  return noContent();
};
