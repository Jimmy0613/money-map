"use client";
import { Suspense, useState } from "react";
import { useSearchParams } from "next/navigation";
import { useInfiniteQuery } from "@tanstack/react-query";
import { api, ApiError, subscribeAuth } from "../../shared/api";
import { useAuth } from "../../shared/providers";
import {
  Filters,
  filterQuery,
  validPeriod,
  monthFilter,
  Status,
  TransactionList,
  SummaryCards,
  type Filter,
} from "../../shared/ui";
import type { TransactionPage } from "../../shared/types";
let saved: Filter | null = null;
subscribeAuth(() => {
  saved = null;
});
function Content() {
  const { user } = useAuth();
  const params = useSearchParams();
  const [filter, setFilter] = useState(() =>
    params.has("from")
      ? {
          ...monthFilter(user!.today),
          from: params.get("from")!,
          to: params.get("to")!,
          type: params.get("type") || "",
          categoryIds: params.getAll("categoryIds"),
        }
      : saved || monthFilter(user!.today),
  );
  const query = filterQuery(filter);
  for (const k of ["placeId", "regionKey"])
    if (params.has(k)) query.set(k, params.get(k)!);
  const queryString = query.toString();
  const result = useInfiniteQuery({
    queryKey: ["transactions", queryString],
    initialPageParam: "",
    queryFn: ({ pageParam, signal }) =>
      api<TransactionPage>(
        `/transactions?${queryString}${pageParam ? "&cursor=" + encodeURIComponent(pageParam) : ""}`,
        { signal },
      ),
    getNextPageParam: (p) => p.page.nextCursor || undefined,
    enabled: validPeriod(filter.from, filter.to),
  });
  function change(f: Filter) {
    saved = f;
    setFilter(f);
  }
  return (
    <main id="content">
      <h1>거래 내역</h1>
      <Filters value={filter} onChange={change} />
      <button
        className="quiet"
        onClick={() => change(monthFilter(user!.today))}
      >
        필터 초기화
      </button>
      <Status
        loading={result.isLoading}
        error={result.error}
        retry={() => result.refetch()}
      />
      {result.error instanceof ApiError &&
        result.error.code === "CURSOR_STALE" && (
          <p>내역이 변경되었습니다. 처음부터 다시 불러오세요.</p>
        )}
      {result.data && (
        <>
          <SummaryCards data={result.data.pages[0].totals} />
          <TransactionList items={result.data.pages.flatMap((p) => p.items)} />
          {result.hasNextPage && (
            <button
              onClick={() => result.fetchNextPage()}
              disabled={result.isFetchingNextPage}
            >
              다음 내역
            </button>
          )}
        </>
      )}
    </main>
  );
}
export default function Page() {
  return (
    <Suspense fallback={<main>불러오는 중…</main>}>
      <Content />
    </Suspense>
  );
}
