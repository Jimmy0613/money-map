"use client";
import { use, useState } from "react";
import Link from "next/link";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { api, ApiError, csrf } from "../../../shared/api";
import { Status } from "../../../shared/ui";
import { money, type Transaction } from "../../../shared/types";
export default function Page({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  const [error, setError] = useState("");
  const [confirm, setConfirm] = useState(false);
  const [busy, setBusy] = useState(false);
  const [gone, setGone] = useState(false);
  const client = useQueryClient();
  const result = useQuery({
    queryKey: ["transaction", id],
    queryFn: () => api<Transaction>(`/transactions/${id}`),
  });
  const t = result.data;
  async function remove() {
    if (!t) return;
    setBusy(true);
    try {
      await csrf();
      await api(`/transactions/${id}`, {
        method: "DELETE",
        headers: { "If-Match": `"${t.version}"` },
      });
      setGone(true);
      await client.invalidateQueries();
    } catch (e) {
      setError((e as Error).message);
      try {
        const current = await api<Transaction>(`/transactions/${id}`);
        client.setQueryData(["transaction", id], current);
        setConfirm(false);
        setError(
          "거래가 현재 존재합니다. 최신 내용을 확인하고 다시 삭제 여부를 결정하세요.",
        );
      } catch (check) {
        if (check instanceof ApiError && check.status === 404) {
          setGone(true);
          await client.invalidateQueries();
        } else setError("삭제 결과가 불명확합니다. 다시 조회하여 확인하세요.");
      }
    } finally {
      setBusy(false);
    }
  }
  if (gone)
    return (
      <main id="content">
        <h1>현재 거래가 존재하지 않습니다</h1>
        <Link href="/transactions">내역으로 돌아가기</Link>
      </main>
    );
  return (
    <main id="content">
      <h1>거래 상세</h1>
      <Status
        loading={result.isLoading}
        error={result.error}
        retry={() => result.refetch()}
      />
      {t && (
        <section className="card">
          <span className="badge">
            {t.type === "EXPENSE" ? "지출" : "수입"}
          </span>
          <p className="amount">{money(t.amount)}</p>
          <p>
            {t.category.name}
            {t.category.archived ? " · 보관된 분류" : ""} · {t.transactionDate}
          </p>
          <p>{t.memo || "메모 없음"}</p>
          <h2>소비 위치</h2>
          {t.location ? (
            <p>
              {t.location.placeName || "선택한 위치"}
              <br />
              {t.location.roadAddress ||
                t.location.parcelAddress ||
                "주소 없음"}
              <br />
              {t.location.latitude}, {t.location.longitude}
              <br />
              출처: {t.location.source}
              {t.location.accuracyM && ` · 오차 ${t.location.accuracyM}m`}
            </p>
          ) : (
            <p>위치 없음</p>
          )}
          <p className="muted">
            기록 {new Date(t.createdAt).toLocaleString("ko-KR")}
            <br />
            수정 {new Date(t.updatedAt).toLocaleString("ko-KR")}
          </p>
          <div className="actions">
            <Link className="button" href={`/transactions/${id}/edit`}>
              수정
            </Link>
            <button className="quiet" onClick={() => setConfirm(true)}>
              삭제
            </button>
            <Link href="/transactions">내역</Link>
          </div>
          {confirm && (
            <div className="notice">
              <p>
                {t.transactionDate}의 {money(t.amount)} 거래를 삭제할까요?
                복구할 수 없습니다.
              </p>
              <button disabled={busy} onClick={remove}>
                삭제 확인
              </button>
              <button className="quiet" onClick={() => setConfirm(false)}>
                취소
              </button>
            </div>
          )}
          <p role="alert">{error}</p>
        </section>
      )}
    </main>
  );
}
