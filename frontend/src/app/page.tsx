"use client";
import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { api } from "../shared/api";
import { useAuth } from "../shared/providers";
import { Status, SummaryCards, TransactionList } from "../shared/ui";
import type { Summary, TransactionPage } from "../shared/types";
export default function Home() {
  const { user } = useAuth();
  const summary = useQuery({
    queryKey: ["summary"],
    queryFn: () => api<Summary>("/statistics/summary"),
  });
  const recent = useQuery({
    queryKey: ["transactions", "recent"],
    queryFn: () => api<TransactionPage>("/transactions?size=5"),
  });
  return (
    <main id="content">
      <div className="hero row">
        <div>
          <p className="eyebrow">나의 소비 지도</p>
          <h1>{user?.today.slice(0, 7).replace("-", "년 ")}월의 흐름</h1>
          <p className="muted">작은 기록이 모여, 나의 생활을 보여줍니다.</p>
        </div>
        <Link className="button" href="/transactions/new">
          ＋ 거래 기록
        </Link>
      </div>
      <Status
        loading={summary.isLoading}
        error={summary.error}
        retry={() => summary.refetch()}
      />
      {summary.data && <SummaryCards data={summary.data} />}
      <section className="card">
        <div className="row">
          <h2>최근 기록</h2>
          <Link href="/transactions">전체 내역</Link>
        </div>
        <Status
          loading={recent.isLoading}
          error={recent.error}
          retry={() => recent.refetch()}
        />
        {recent.data && <TransactionList items={recent.data.items} />}
      </section>
      <div className="grid">
        <section className="card">
          <h2>어디에서 썼을까요?</h2>
          <p className="muted">위치가 있는 지출을 지도에서 살펴보세요.</p>
          <Link href="/map">소비 지도 열기 →</Link>
        </section>
        <section className="card">
          <h2>소비 패턴 살펴보기</h2>
          <p className="muted">카테고리, 장소와 지역별로 비교합니다.</p>
          <Link href="/statistics">통계 보기 →</Link>
        </section>
      </div>
    </main>
  );
}
