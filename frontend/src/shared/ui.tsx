"use client";
import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { api } from "./api";
import { money, type Transaction, type Summary, type Category } from "./types";
export function validPeriod(from: string, to: string) {
  const valid = (value: string) => {
    if (!/^[0-9]{4}-[0-9]{2}-[0-9]{2}$/.test(value)) return false;
    const date = new Date(`${value}T00:00:00Z`);
    return (
      Number.isFinite(date.getTime()) &&
      date.toISOString().slice(0, 10) === value
    );
  };
  return valid(from) && valid(to) && from <= to;
}
export function Status({
  loading,
  error,
  retry,
}: {
  loading: boolean;
  error: Error | null;
  retry: () => void;
}) {
  return (
    <>
      {loading && <p role="status">불러오는 중…</p>}
      {error && (
        <div role="alert">
          <p>{error.message || "불러오지 못했습니다."}</p>
          <button onClick={retry}>다시 시도</button>
        </div>
      )}
    </>
  );
}
export function TransactionList({ items }: { items: Transaction[] }) {
  return items.length ? (
    <ul className="list">
      {items.map((t) => (
        <li key={t.id}>
          <div className="row">
            <Link href={`/transactions/${t.id}`}>
              {t.category.name} · {t.transactionDate}
            </Link>
            <strong>
              {t.type === "INCOME" ? "+" : "−"}
              {money(t.amount)}
            </strong>
          </div>
          <small className="muted">
            {t.location?.placeName ||
              t.location?.roadAddress ||
              (t.location ? "선택한 위치" : "위치 없음")}
            {t.memo ? ` · ${t.memo}` : ""}
          </small>
        </li>
      ))}
    </ul>
  ) : (
    <div className="card">
      <p>이 기간에 기록된 거래가 없습니다.</p>
      <Link href="/transactions/new">첫 거래 기록하기</Link>
    </div>
  );
}
export function SummaryCards({ data }: { data: Summary }) {
  return (
    <div className="grid">
      {[
        ["수입", data.incomeTotal],
        ["지출", data.expenseTotal],
        ["잔액", data.balance],
      ].map(([label, value]) => (
        <section className="card" key={label}>
          <p className="muted">{label}</p>
          <div className="amount">{money(value)}</div>
        </section>
      ))}
    </div>
  );
}
export interface Filter {
  from: string;
  to: string;
  categoryIds: string[];
  type: string;
  hasLocation: string;
}
export function monthFilter(today: string): Filter {
  const start = today.slice(0, 7) + "-01";
  const [y, m] = today.split("-").map(Number);
  const end = `${today.slice(0, 7)}-${new Date(Date.UTC(y, m, 0)).getUTCDate()}`;
  return { from: start, to: end, categoryIds: [], type: "", hasLocation: "" };
}
export function filterQuery(f: Filter, expenses = false) {
  const q = new URLSearchParams({ from: f.from, to: f.to });
  f.categoryIds.forEach((c) => q.append("categoryIds", c));
  if (!expenses) {
    if (f.type) q.set("type", f.type);
    if (f.hasLocation) q.set("hasLocation", f.hasLocation);
  }
  return q;
}
export function Filters({
  value,
  onChange,
  expenses = false,
}: {
  value: Filter;
  onChange: (v: Filter) => void;
  expenses?: boolean;
}) {
  const categories = useQuery({
    queryKey: ["categories", "all"],
    queryFn: () =>
      api<{ items: Category[] }>("/categories?includeArchived=true"),
  });
  return (
    <div className="card">
      <div className="filters">
        <label>
          시작일
          <input
            type="date"
            value={value.from}
            onChange={(e) => onChange({ ...value, from: e.target.value })}
          />
        </label>
        <label>
          종료일
          <input
            type="date"
            value={value.to}
            onChange={(e) => onChange({ ...value, to: e.target.value })}
          />
        </label>
        {!expenses && (
          <>
            <label>
              유형
              <select
                value={value.type}
                onChange={(e) =>
                  onChange({ ...value, type: e.target.value, categoryIds: [] })
                }
              >
                <option value="">전체</option>
                <option value="EXPENSE">지출</option>
                <option value="INCOME">수입</option>
              </select>
            </label>
            <label>
              위치
              <select
                value={value.hasLocation}
                onChange={(e) =>
                  onChange({ ...value, hasLocation: e.target.value })
                }
              >
                <option value="">전체</option>
                <option value="true">위치 있음</option>
                <option value="false">위치 없음</option>
              </select>
            </label>
          </>
        )}
      </div>
      <fieldset>
        <legend>카테고리 · 미선택 시 전체</legend>
        <div className="actions">
          {categories.data?.items
            .filter((c) =>
              expenses
                ? c.type === "EXPENSE"
                : !value.type || c.type === value.type,
            )
            .map((c) => (
              <label key={c.id}>
                <input
                  type="checkbox"
                  checked={value.categoryIds.includes(c.id)}
                  onChange={(e) =>
                    onChange({
                      ...value,
                      categoryIds: e.target.checked
                        ? [...value.categoryIds, c.id]
                        : value.categoryIds.filter((id) => id !== c.id),
                    })
                  }
                />
                {c.name}
                {c.archived ? " (보관)" : ""}
              </label>
            ))}
        </div>
      </fieldset>
      {!validPeriod(value.from, value.to) && (
        <p role="alert">
          시작일과 종료일을 올바르게 입력하고 시작일이 종료일 이후인지
          확인하세요.
        </p>
      )}
    </div>
  );
}
