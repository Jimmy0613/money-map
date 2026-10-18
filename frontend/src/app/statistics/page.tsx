"use client";
import { useState } from "react";
import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { api } from "../../shared/api";
import { useAuth } from "../../shared/providers";
import {
  Filters,
  filterQuery,
  validPeriod,
  monthFilter,
  Status,
  SummaryCards,
} from "../../shared/ui";
import {
  money,
  type Summary,
  type Page as Pagination,
} from "../../shared/types";
interface CategoryStat {
  items: {
    categoryId: string;
    name: string;
    amount: string;
    count: number;
    percent: number;
  }[];
  expenseTotal: string;
}
interface PlaceStat {
  items: {
    placeId: string;
    lastTransactionDate: string;
    name: string;
    address: string | null;
    amount: string;
    count: number;
  }[];
  identifiedExpenseTotal: string;
  unidentifiedLocation: { amount: string; count: number };
  locationless: { amount: string; count: number };
  page: Pagination;
}
interface RegionStat {
  items: {
    regionKey: string;
    name: string;
    amount: string;
    count: number;
    percent: number;
  }[];
  locatedExpenseTotal: string;
  unclassifiedCount: number;
  locationlessCount: number;
  page: Pagination;
}
interface ComparisonValue {
  current: string;
  previous: string;
  difference: string;
  growthPercent: number | null;
  status: string;
}
interface Comparison {
  month: string;
  previousMonth: string;
  total: ComparisonValue;
  categories: (ComparisonValue & { key: string; name: string })[];
  regions: (ComparisonValue & { key: string; name: string })[];
}
export default function Page() {
  const { user } = useAuth();
  const [filter, setFilter] = useState(() => monthFilter(user!.today));
  const [month, setMonth] = useState(user!.today.slice(0, 7));
  const [categoryScope, setCategoryScope] = useState<{
    field: "placeId" | "regionKey";
    value: string;
    name: string;
  } | null>(null);
  const [placeSort, setPlaceSort] = useState("amount_desc");
  const [placeCursor, setPlaceCursor] = useState("");
  const [regionCursor, setRegionCursor] = useState("");
  const q = filterQuery(filter, true).toString();
  const dates = new URLSearchParams({
    from: filter.from,
    to: filter.to,
  }).toString();
  const enabled = validPeriod(filter.from, filter.to);
  const summary = useQuery({
    queryKey: ["summary", dates],
    queryFn: () => api<Summary>(`/statistics/summary?${dates}`),
    enabled,
  });
  const categoryParams = new URLSearchParams(dates);
  if (categoryScope)
    categoryParams.set(categoryScope.field, categoryScope.value);
  const categoryQuery = categoryParams.toString();
  const categories = useQuery({
    queryKey: ["stat-categories", categoryQuery],
    queryFn: () => api<CategoryStat>(`/statistics/categories?${categoryQuery}`),
    enabled,
  });
  const places = useQuery({
    queryKey: ["stat-places", q, placeSort, placeCursor],
    queryFn: () =>
      api<PlaceStat>(
        `/statistics/places?${q}&sort=${placeSort}${placeCursor ? "&cursor=" + encodeURIComponent(placeCursor) : ""}`,
      ),
    enabled,
  });
  const regions = useQuery({
    queryKey: ["stat-regions", q, regionCursor],
    queryFn: () =>
      api<RegionStat>(
        `/statistics/regions?${q}${regionCursor ? "&cursor=" + encodeURIComponent(regionCursor) : ""}`,
      ),
    enabled,
  });
  const monthlyQ = new URLSearchParams({ month });
  filter.categoryIds.forEach((c) => monthlyQ.append("categoryIds", c));
  const comparison = useQuery({
    queryKey: ["comparison", monthlyQ.toString()],
    queryFn: () =>
      api<Comparison>(`/statistics/monthly-comparison?${monthlyQ}`),
    enabled: !!month,
  });
  const detail = (field: string, value: string) =>
    `/transactions?${q}&type=EXPENSE&${field}=${encodeURIComponent(value)}`;
  return (
    <main id="content">
      <h1>소비 통계</h1>
      <Filters
        expenses
        value={filter}
        onChange={(v) => {
          setFilter(v);
          setPlaceCursor("");
          setRegionCursor("");
        }}
      />
      <p className="muted">
        요약은 기간 전체 기준입니다. 카테고리 비율은 아래에서 선택한 장소·지역
        기준입니다. 선택 카테고리는 장소·지역·월 비교에 적용합니다.
      </p>
      <Status
        loading={summary.isLoading}
        error={summary.error}
        retry={() => summary.refetch()}
      />
      {summary.data && <SummaryCards data={summary.data} />}
      <section className="card">
        <h2>카테고리별 지출</h2>
        <p>비교 범위: {categoryScope?.name || "전체 장소·지역"}</p>
        {categoryScope && (
          <button
            type="button"
            className="quiet"
            onClick={() => setCategoryScope(null)}
          >
            전체 장소·지역 보기
          </button>
        )}
        <p className="muted">
          아래 장소·지역 목록의 카테고리 비교 버튼으로 범위를 선택하세요.
        </p>
        <Status
          loading={categories.isLoading}
          error={categories.error}
          retry={() => categories.refetch()}
        />
        {categories.data?.items.map((c) => (
          <div className="chart-row" key={c.categoryId}>
            <div className="row">
              <span>
                {c.name} · {c.count}건
              </span>
              <strong>
                {money(c.amount)} · {c.percent}%
              </strong>
            </div>
            <div className="bar" style={{ width: `${c.percent}%` }} />
          </div>
        ))}
        {categories.data?.items.length === 0 && <p>지출 기록이 없습니다.</p>}
      </section>
      <section className="card">
        <h2>자주 소비한 장소</h2>
        <label>
          장소 정렬
          <select
            value={placeSort}
            onChange={(e) => {
              setPlaceSort(e.target.value);
              setPlaceCursor("");
            }}
          >
            <option value="amount_desc">지출 금액순</option>
            <option value="count_desc">거래 건수순</option>
          </select>
        </label>
        <Status
          loading={places.isLoading}
          error={places.error}
          retry={() => places.refetch()}
        />
        {places.data && (
          <>
            <ul className="list">
              {places.data.items.map((p) => (
                <li key={p.placeId}>
                  <div className="row">
                    <Link href={detail("placeId", p.placeId)}>{p.name}</Link>
                    <strong>{money(p.amount)}</strong>
                  </div>
                  <small>
                    {p.address} · {p.count}건 · 최근 거래일{" "}
                    {p.lastTransactionDate}
                  </small>
                  <button
                    type="button"
                    className="quiet"
                    onClick={() =>
                      setCategoryScope({
                        field: "placeId",
                        value: p.placeId,
                        name: p.name,
                      })
                    }
                  >
                    {p.name} 카테고리 비교
                  </button>
                </li>
              ))}
            </ul>
            {!places.data.items.length && (
              <p>검색으로 식별된 장소가 없습니다.</p>
            )}
            <p className="muted">
              직접 선택 위치 {money(places.data.unidentifiedLocation.amount)} ·{" "}
              {places.data.unidentifiedLocation.count}건 · 위치 없음{" "}
              {money(places.data.locationless.amount)} ·{" "}
              {places.data.locationless.count}건
            </p>
            {places.data.page.hasNext && (
              <button
                onClick={() => setPlaceCursor(places.data!.page.nextCursor!)}
              >
                다음 장소
              </button>
            )}
            {placeCursor && (
              <button className="quiet" onClick={() => setPlaceCursor("")}>
                처음부터
              </button>
            )}
          </>
        )}
      </section>
      <section className="card">
        <h2>지역별 지출</h2>
        <Status
          loading={regions.isLoading}
          error={regions.error}
          retry={() => regions.refetch()}
        />
        {regions.data && (
          <>
            <ul className="list">
              {regions.data.items.map((r) => (
                <li key={r.regionKey}>
                  <div className="row">
                    <Link href={detail("regionKey", r.regionKey)}>
                      {r.name}
                    </Link>
                    <strong>
                      {money(r.amount)} · {r.percent}% · {r.count}건
                    </strong>
                  </div>
                  <button
                    type="button"
                    className="quiet"
                    onClick={() =>
                      setCategoryScope({
                        field: "regionKey",
                        value: r.regionKey,
                        name: r.name,
                      })
                    }
                  >
                    {r.name} 카테고리 비교
                  </button>
                </li>
              ))}
            </ul>
            {!regions.data.items.length && <p>위치가 있는 지출이 없습니다.</p>}
            <p className="muted">
              지역 미분류 {regions.data.unclassifiedCount}건 · 위치 없음{" "}
              {regions.data.locationlessCount}건
            </p>
            {regions.data.page.hasNext && (
              <button
                onClick={() => setRegionCursor(regions.data!.page.nextCursor!)}
              >
                다음 지역
              </button>
            )}
            {regionCursor && (
              <button className="quiet" onClick={() => setRegionCursor("")}>
                처음부터
              </button>
            )}
          </>
        )}
      </section>
      <section className="card">
        <h2>지난달과 비교</h2>
        <label>
          기준월
          <input
            type="month"
            value={month}
            onChange={(e) => setMonth(e.target.value)}
          />
        </label>
        <Status
          loading={comparison.isLoading}
          error={comparison.error}
          retry={() => comparison.refetch()}
        />
        {comparison.data && (
          <>
            <p>
              {comparison.data.previousMonth}{" "}
              {money(comparison.data.total.previous)} → {month}{" "}
              {money(comparison.data.total.current)}
            </p>
            <p className="amount">{money(comparison.data.total.difference)}</p>
            <p>
              {comparison.data.total.growthPercent === null
                ? "이전 지출이 없어 증감률을 비교할 수 없습니다."
                : `${comparison.data.total.growthPercent}% 변화`}
            </p>
            <h3>카테고리 변화</h3>
            <ul className="list">
              {comparison.data.categories.map((c) => (
                <li key={c.key}>
                  {c.name} · {money(c.difference)}
                </li>
              ))}
            </ul>
            <h3>지역 변화 상위 10개</h3>
            <ul className="list">
              {comparison.data.regions.map((r) => (
                <li key={r.key}>
                  {r.name} · {money(r.difference)}
                </li>
              ))}
            </ul>
          </>
        )}
      </section>
    </main>
  );
}
