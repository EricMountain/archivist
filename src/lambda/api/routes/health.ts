import { ok } from "../http";
import type { RouteHandler } from "../http";

export const getHealth: RouteHandler = async () => ok({ status: "ok" });
