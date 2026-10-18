"use client";
import { useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { api, csrf, setLogoutPending } from "../../shared/api";
import { useAuth } from "../../shared/providers";
export default function Page() {
  const { user } = useAuth();
  const router = useRouter();
  const [password, setPassword] = useState("");
  const [confirmation, setConfirmation] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  async function remove(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    try {
      await csrf();
      await api("/users/me", {
        method: "DELETE",
        body: JSON.stringify({ currentPassword: password, confirmation }),
      });
      setLogoutPending(false);
      router.replace("/login");
    } catch (e) {
      setError((e as Error).message + " 탈퇴 완료 여부는 확인되지 않았습니다.");
    } finally {
      setBusy(false);
    }
  }
  return (
    <main id="content">
      <h1>설정</h1>
      <section className="card">
        <h2>내 계정</h2>
        <p>{user?.email}</p>
        <p>시간대 {user?.timezone}</p>
        <Link href="/settings/categories">카테고리 관리</Link>
      </section>
      <section className="card">
        <h2>계정 탈퇴</h2>
        <p>
          활성 DB의 계정, 거래, 위치가 즉시 삭제됩니다. 복구할 수 없습니다.
          백업은 최대 30일 후 만료됩니다.
        </p>
        <form onSubmit={remove}>
          <label>
            현재 비밀번호
            <input
              type="password"
              autoComplete="current-password"
              required
              value={password}
              onChange={(e) => setPassword(e.target.value)}
            />
          </label>
          <label>
            삭제 확인 · DELETE 입력
            <input
              required
              value={confirmation}
              onChange={(e) => setConfirmation(e.target.value)}
            />
          </label>
          <p role="alert">{error}</p>
          <button disabled={busy || confirmation !== "DELETE"}>
            계정과 데이터 삭제
          </button>
        </form>
      </section>
    </main>
  );
}
