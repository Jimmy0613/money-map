export interface FieldError {
  field: string;
  code: string;
  message: string;
}
export class ApiError extends Error {
  constructor(
    public status: number,
    public code: string,
    message: string,
    public fields: FieldError[] = [],
  ) {
    super(message);
  }
}
let pending = false;
let epoch = 0;
const listeners = new Set<() => void>();
const active = new Set<AbortController>();
let storageFailed = false;
let channel: BroadcastChannel | undefined;
let initialized = false;
export function initializeAuthBarrier() {
  if (typeof window === "undefined" || initialized) return;
  initialized = true;
  try {
    pending = localStorage.getItem("mm.logoutPending") === "true";
  } catch {
    storageFailed = true;
  }
  window.addEventListener("storage", (e) => {
    if (e.key === "mm.logoutPending") applyPending(e.newValue === "true");
  });
  if ("BroadcastChannel" in window) {
    channel = new BroadcastChannel("mm.auth");
    channel.onmessage = (e) => {
      if (e.data === "pending") applyPending(true);
      if (e.data === "cleared") applyPending(false);
    };
  }
}
function applyPending(value: boolean) {
  pending = value;
  epoch++;
  active.forEach((c) => c.abort());
  active.clear();
  listeners.forEach((f) => f());
}
export function isLogoutPending() {
  return pending;
}
export function storageUnavailable() {
  return storageFailed;
}
export function subscribeAuth(fn: () => void) {
  listeners.add(fn);
  return () => {
    listeners.delete(fn);
  };
}
export function setLogoutPending(value: boolean) {
  initializeAuthBarrier();
  applyPending(value);
  try {
    if (value) localStorage.setItem("mm.logoutPending", "true");
    else localStorage.removeItem("mm.logoutPending");
  } catch {
    storageFailed = true;
  }
  channel?.postMessage(value ? "pending" : "cleared");
}
function csrfValue() {
  return document.cookie
    .split("; ")
    .find((c) => c.startsWith("XSRF-TOKEN="))
    ?.slice("XSRF-TOKEN=".length);
}
export async function csrf() {
  await api("/auth/csrf", {}, false);
}
let refreshing: Promise<void> | null = null;
async function refresh() {
  if (!refreshing) {
    const execute = async () => {
      if (pending)
        throw new ApiError(
          401,
          "LOGOUT_PENDING",
          "로그아웃 확인이 필요합니다.",
        );
      await csrf();
      await api("/auth/refresh", { method: "POST", body: "{}" }, false);
      await csrf();
    };
    refreshing = (async () => {
      if ("locks" in navigator)
        await navigator.locks.request("mm.refresh", execute);
      else await execute();
    })().finally(() => {
      refreshing = null;
    });
  }
  await refreshing;
}
export async function api<T>(
  path: string,
  options: RequestInit = {},
  retry = true,
): Promise<T> {
  initializeAuthBarrier();
  if (pending && !["/auth/csrf", "/auth/logout"].includes(path))
    throw new ApiError(401, "LOGOUT_PENDING", "로그아웃 확인이 필요합니다.");
  const generation = epoch;
  const controller = new AbortController();
  active.add(controller);
  const abort = () => controller.abort();
  options.signal?.addEventListener("abort", abort);
  try {
    const headers = new Headers(options.headers);
    if (options.body) headers.set("Content-Type", "application/json");
    if (options.method && !["GET", "HEAD"].includes(options.method)) {
      const value = csrfValue();
      if (value) headers.set("X-XSRF-TOKEN", decodeURIComponent(value));
    }
    const response = await fetch(`/api/v1${path}`, {
      ...options,
      headers,
      credentials: "include",
      cache: "no-store",
      signal: controller.signal,
    });
    if (generation !== epoch)
      throw new DOMException("인증 상태가 변경되었습니다.", "AbortError");
    if (response.status === 204) return undefined as T;
    const body = await response.json();
    if (generation !== epoch)
      throw new DOMException("인증 상태가 변경되었습니다.", "AbortError");
    if (!response.ok) {
      const e = new ApiError(
        response.status,
        body.error?.code || "HTTP_ERROR",
        body.error?.message || "요청에 실패했습니다.",
        Array.isArray(body.error?.fields)
          ? body.error.fields.filter(
              (f: unknown): f is FieldError =>
                typeof f === "object" &&
                f !== null &&
                "field" in f &&
                typeof f.field === "string" &&
                "code" in f &&
                typeof f.code === "string" &&
                "message" in f &&
                typeof f.message === "string",
            )
          : [],
      );
      if (retry && e.code === "ACCESS_EXPIRED" && !path.startsWith("/auth/")) {
        await refresh();
        return api<T>(path, options, false);
      }
      if (
        e.status === 401 &&
        (!path.startsWith("/auth/") || path === "/auth/refresh") &&
        !pending
      ) {
        applyPending(false);
      }
      throw e;
    }
    return body.data as T;
  } finally {
    active.delete(controller);
    options.signal?.removeEventListener("abort", abort);
  }
}
export async function logout() {
  setLogoutPending(true);
  for (let attempt = 0; attempt < 2; attempt++)
    try {
      await csrf();
      await api("/auth/logout", { method: "POST", body: "{}" }, false);
      setLogoutPending(false);
      return;
    } catch (e) {
      if (!(e instanceof ApiError && e.status === 403 && attempt === 0))
        throw e;
    }
}
