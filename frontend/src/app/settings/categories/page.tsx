"use client";
import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { api, csrf } from "../../../shared/api";
import type { Category, Kind } from "../../../shared/types";
import { Status } from "../../../shared/ui";
function CategoryRow({ item }: { item: Category }) {
  const [name, setName] = useState(item.name);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [version, setVersion] = useState(item.version);
  const client = useQueryClient();
  async function change(archive = false) {
    setBusy(true);
    setError("");
    const body = archive
      ? { archived: true }
      : { name, icon: item.icon, color: item.color };
    try {
      await csrf();
      const updated = await api<Category>(`/categories/${item.id}`, {
        method: "PATCH",
        headers: { "If-Match": `"${version}"` },
        body: JSON.stringify(body),
      });
      setVersion(updated.version);
      setName(updated.name);
      await client.invalidateQueries();
    } catch {
      try {
        const data = await api<{ items: Category[] }>(
          "/categories?includeArchived=true",
        );
        client.setQueryData(["categories", "all"], data);
        const now = data.items.find((c) => c.id === item.id);
        if (now) {
          setVersion(now.version);
          setError(
            (archive ? now.archived : now.name === name.trim())
              ? "현재 저장 내용이 요청과 일치합니다."
              : `현재 이름: ${now.name}. 입력 초안을 유지했습니다. 확인 후 다시 저장하세요.`,
          );
        } else setError("현재 항목이 없습니다.");
      } catch {
        setError("변경 결과를 확인하지 못했습니다. 초안을 유지했습니다.");
      }
    } finally {
      setBusy(false);
    }
  }
  return (
    <li>
      {item.isDefault || item.archived ? (
        <p>
          {item.name}{" "}
          <span className="badge">{item.archived ? "보관" : "기본"}</span>
        </p>
      ) : (
        <>
          <label>
            분류 이름
            <input
              value={name}
              disabled={busy}
              maxLength={30}
              onChange={(e) => setName(e.target.value)}
            />
          </label>
          <div className="actions">
            <button disabled={busy || !name.trim()} onClick={() => change()}>
              이름 저장
            </button>
            <button
              className="quiet"
              disabled={busy}
              onClick={() => {
                if (
                  window.confirm(
                    "보관하면 신규 거래에서 선택할 수 없습니다. 기존 거래는 유지됩니다.",
                  )
                )
                  change(true);
              }}
            >
              보관
            </button>
          </div>
        </>
      )}
      <p role="status">{error}</p>
    </li>
  );
}
export default function Page() {
  const client = useQueryClient();
  const [name, setName] = useState("");
  const [type, setType] = useState<Kind>("EXPENSE");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const result = useQuery({
    queryKey: ["categories", "all"],
    queryFn: () =>
      api<{ items: Category[] }>("/categories?includeArchived=true"),
  });
  async function add(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    try {
      await csrf();
      await api("/categories", {
        method: "POST",
        body: JSON.stringify({ type, name, icon: null, color: null }),
      });
      setName("");
      await client.invalidateQueries();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }
  return (
    <main id="content">
      <h1>카테고리 관리</h1>
      <form className="card" onSubmit={add}>
        <label>
          유형
          <select
            value={type}
            onChange={(e) => setType(e.target.value as Kind)}
          >
            <option value="EXPENSE">지출</option>
            <option value="INCOME">수입</option>
          </select>
        </label>
        <label>
          새 카테고리
          <input
            required
            maxLength={30}
            value={name}
            onChange={(e) => setName(e.target.value)}
          />
        </label>
        <p role="alert">{error}</p>
        <button disabled={busy}>추가</button>
      </form>
      <Status
        loading={result.isLoading}
        error={result.error}
        retry={() => result.refetch()}
      />
      <ul className="list">
        {result.data?.items
          .filter((c) => c.type === type)
          .map((c) => (
            <CategoryRow key={c.id} item={c} />
          ))}
      </ul>
    </main>
  );
}
