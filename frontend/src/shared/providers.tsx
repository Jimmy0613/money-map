"use client";
import {
  createContext,
  useContext,
  useEffect,
  useState,
  type ReactNode,
} from "react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import {
  api,
  ApiError,
  initializeAuthBarrier,
  isLogoutPending,
  logout,
  storageUnavailable,
  subscribeAuth,
} from "./api";
import type { User } from "./types";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import {
  ServerConnectionNotice,
  type ConnectionState,
} from "./server-connection-notice";
const Auth = createContext<{
  user: User | null;
  reload: () => Promise<void>;
  connection: ConnectionState;
  elapsed: number;
  retryConnection: () => void;
}>({
  user: null,
  reload: async () => {},
  connection: "ready",
  elapsed: 0,
  retryConnection: () => {},
});
export const useAuth = () => useContext(Auth);
export function Providers({ children }: { children: ReactNode }) {
  const [client] = useState(
    () =>
      new QueryClient({
        defaultOptions: {
          queries: {
            retry: false,
            staleTime: 15000,
            refetchOnWindowFocus: false,
          },
        },
      }),
  );
  const [user, setUser] = useState<User | null>(null);
  const [loading, setLoading] = useState(true);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState("");
  const [authError, setAuthError] = useState("");
  const [authAttempt, setAuthAttempt] = useState(0);
  const [elapsed, setElapsed] = useState(0);
  const pathname = usePathname();
  const router = useRouter();
  const publicPage = ["/", "/login", "/signup"].includes(pathname);
  const connection: ConnectionState = loading
    ? "connecting"
    : authError
      ? "failed"
      : "ready";
  function retryConnection() {
    setAuthError("");
    setElapsed(0);
    setLoading(true);
    setAuthAttempt((attempt) => attempt + 1);
  }
  useEffect(() => {
    if (!loading) return;
    const started = Date.now();
    const timer = setInterval(
      () => setElapsed(Math.floor((Date.now() - started) / 1000)),
      1000,
    );
    return () => clearInterval(timer);
  }, [loading, authAttempt]);
  async function reload() {
    const u = await api<User>("/users/me");
    setUser(u);
  }
  useEffect(() => {
    initializeAuthBarrier();
    const update = () => {
      setPending(isLogoutPending());
      setUser(null);
      client.cancelQueries();
      client.clear();
    };
    const unsub = subscribeAuth(update);
    let live = true;
    const controller = new AbortController();
    // One bounded preparation cycle shared across client navigation.
    const budget = setTimeout(() => {
      controller.abort();
      if (live) {
        setAuthError("서버에 연결하지 못했습니다. 잠시 후 다시 연결해 주세요.");
        setLoading(false);
      }
    }, 180000);
    async function connect() {
      while (!controller.signal.aborted) {
        try {
          const u = await api<User>("/users/me", { signal: controller.signal });
          if (live) setUser(u);
          return;
        } catch (e) {
          if (e instanceof ApiError && e.status === 401) return;
          if (e instanceof ApiError && e.status >= 400 && e.status < 500) break;
          if (controller.signal.aborted) break;
          await new Promise<void>((resolve) => {
            function done() {
              clearTimeout(timer);
              controller.signal.removeEventListener("abort", done);
              resolve();
            }
            const timer = setTimeout(done, 5000);
            controller.signal.addEventListener("abort", done, { once: true });
          });
        }
      }
      if (live)
        setAuthError("서버에 연결하지 못했습니다. 잠시 후 다시 연결해 주세요.");
    }
    if (isLogoutPending()) {
      clearTimeout(budget);
      queueMicrotask(() => {
        if (live) {
          setPending(true);
          setLoading(false);
        }
      });
    } else
      connect().finally(() => {
        clearTimeout(budget);
        if (live) setLoading(false);
      });
    return () => {
      live = false;
      controller.abort();
      clearTimeout(budget);
      unsub();
    };
  }, [client, authAttempt]);
  useEffect(() => {
    if (
      !loading &&
      !authError &&
      !user &&
      !pending &&
      !["/", "/login", "/signup"].includes(pathname)
    )
      router.replace("/login");
  }, [loading, authError, user, pending, pathname, router]);
  async function end() {
    setError("");
    try {
      await logout();
      router.replace("/login");
    } catch {
      setError("서버 로그아웃을 확인하지 못했습니다. 연결 후 다시 시도하세요.");
    }
  }
  if (loading && !publicPage)
    return (
      <main id="content">
        <ServerConnectionNotice
          state={connection}
          elapsed={elapsed}
          retry={retryConnection}
        />
        <Link href="/">소개로 돌아가기</Link>
      </main>
    );
  if (pending)
    return (
      <main>
        <h1>로그아웃 확인 중</h1>
        <p>개인 화면과 자동 로그인을 차단했습니다.</p>
        <p role="alert">{error}</p>
        {storageUnavailable() && (
          <p>
            브라우저 저장소에 접근할 수 없어 새로고침 후 차단 유지가 보장되지
            않습니다.
          </p>
        )}
        <button onClick={end}>로그아웃 다시 시도</button>
      </main>
    );
  if (authError && !publicPage)
    return (
      <main>
        <h1>서비스 연결을 확인해 주세요</h1>
        <p role="alert">{authError}</p>
        <button onClick={retryConnection}>다시 연결</button>
      </main>
    );
  if (!user && !["/", "/login", "/signup"].includes(pathname))
    return (
      <main>
        <p role="status">로그인 화면으로 이동 중…</p>
      </main>
    );
  return (
    <QueryClientProvider client={client}>
      <Auth.Provider
        value={{ user, reload, connection, elapsed, retryConnection }}
      >
        <header>
          <Link href="/" className="brand">
            Money Map <span>소비의 발자취</span>
          </Link>
          {user && (
            <div>
              <Link href="/settings">설정</Link>
              <button className="quiet" onClick={end}>
                로그아웃
              </button>
            </div>
          )}
        </header>
        {user?.demo && (
          <aside className="demo-banner" aria-label="공개 체험 안내">
            <strong>테스트 계정으로 로그인했습니다.</strong>
            <span>
              서비스 체험을 위해 일부 기능과 이용량이 제한됩니다. 입력한 기록은
              다른 방문자와 공유되므로 실제 개인 정보나 소비 위치는 입력하지
              마세요.
            </span>
          </aside>
        )}
        {children}
        {user && (
          <nav aria-label="주요 메뉴">
            {[
              ["/", "홈"],
              ["/transactions", "내역"],
              ["/transactions/new", "등록"],
              ["/map", "지도"],
              ["/statistics", "통계"],
            ].map(([url, label]) => (
              <Link
                key={url}
                href={url}
                aria-current={pathname === url ? "page" : undefined}
              >
                {label}
              </Link>
            ))}
          </nav>
        )}
      </Auth.Provider>
    </QueryClientProvider>
  );
}
