import { api, isLogoutPending } from "../shared/api";
export const mapErrorCodes = [
  "SDK_LOAD",
  "SDK_TIMEOUT",
  "SDK_UNAVAILABLE",
  "SEARCH_FAILED",
  "SEARCH_TIMEOUT",
  "ADDRESS_FAILED",
  "ADDRESS_TIMEOUT",
  "REGION_FAILED",
  "REGION_TIMEOUT",
] as const;
export type MapErrorCode = (typeof mapErrorCodes)[number];
// Only fixed codes leave this module. Never accept an Error, URL or location.
export function reportMapError(code: MapErrorCode) {
  if (!mapErrorCodes.includes(code) || isLogoutPending()) return;
  void api("/diagnostics/map-errors", {
    method: "POST",
    body: JSON.stringify({ code }),
  }).catch(() => {});
}
