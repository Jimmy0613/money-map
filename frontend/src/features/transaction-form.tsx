"use client";
import { useRef, useState } from "react";
import { useForm, useWatch } from "react-hook-form";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import { api, ApiError, csrf } from "../shared/api";
import { useAuth } from "../shared/providers";
import {
  type Category,
  type Transaction,
  type TransactionWrite,
  toWrite,
  sameWrite,
} from "../shared/types";
import { LocationPicker } from "./location-picker";
import { Status } from "../shared/ui";
export function TransactionForm({ existing }: { existing?: Transaction }) {
  const { user } = useAuth();
  const client = useQueryClient();
  const router = useRouter();
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [version, setVersion] = useState(existing?.version);
  const [location, setLocation] = useState<TransactionWrite["location"]>(
    existing ? toWrite(existing).location : null,
  );
  const [uncertain, setUncertain] = useState(false);
  const submitted = useRef<TransactionWrite | null>(null);
  const key = useRef<string | null>(null);
  const {
    register,
    handleSubmit,
    control,
    setError: fieldError,
    formState: { errors, isSubmitting },
  } = useForm<TransactionWrite>({
    defaultValues: existing
      ? toWrite(existing)
      : {
          type: "EXPENSE",
          amount: "",
          currency: "KRW",
          categoryId: "",
          transactionDate: user!.today,
          memo: null,
          location: null,
        },
  });
  const kind = useWatch({ control, name: "type" });
  const categories = useQuery({
    queryKey: ["categories", "all"],
    queryFn: () =>
      api<{ items: Category[] }>("/categories?includeArchived=true"),
  });
  async function verify() {
    if (!existing || !submitted.current) return;
    try {
      const latest = await api<Transaction>(`/transactions/${existing.id}`);
      setVersion(latest.version);
      setUncertain(false);
      if (sameWrite(toWrite(latest), submitted.current)) {
        setNotice("현재 저장 내용이 요청과 일치합니다.");
        await client.invalidateQueries();
      } else
        setNotice(
          `현재 저장 내용은 ${latest.amount}원 / ${latest.transactionDate} / ${latest.category.name}입니다. 입력 초안을 유지했습니다. 확인 후 저장하면 최신 버전으로 요청합니다.`,
        );
    } catch {
      setUncertain(true);
      setNotice(
        "변경 결과를 확인하지 못했습니다. 입력을 보존했습니다. 다시 확인하세요.",
      );
    }
  }
  async function submit(values: TransactionWrite) {
    setError("");
    setNotice("");
    const body = {
      ...values,
      currency: "KRW" as const,
      memo: values.memo?.trim() || null,
      location,
    };
    if (
      !existing &&
      submitted.current &&
      JSON.stringify(body) !== JSON.stringify(submitted.current) &&
      uncertain
    ) {
      setError(
        "이전 등록 결과가 불명확합니다. 이전 입력으로 재시도하여 결과를 확인하세요.",
      );
      return;
    }
    submitted.current = body;
    if (!key.current) key.current = crypto.randomUUID();
    try {
      await csrf();
      const result = await api<Transaction>(
        existing ? `/transactions/${existing.id}` : "/transactions",
        {
          method: existing ? "PUT" : "POST",
          headers: existing
            ? { "If-Match": `"${version}"` }
            : { "Idempotency-Key": key.current },
          body: JSON.stringify(body),
        },
      );
      setUncertain(false);
      await client.invalidateQueries();
      router.push(`/transactions/${result.id}`);
    } catch (e) {
      setError((e as Error).message);
      if (e instanceof ApiError)
        for (const f of e.fields) {
          if (
            [
              "type",
              "amount",
              "categoryId",
              "transactionDate",
              "memo",
            ].includes(f.field)
          )
            fieldError(f.field as keyof TransactionWrite, {
              type: "server",
              message: f.message,
            });
        }
      const ambiguous = !(
        e instanceof ApiError &&
        e.status >= 400 &&
        e.status < 500
      );
      setUncertain(ambiguous);
      if (existing) await verify();
      else if (!ambiguous) {
        key.current = null;
        submitted.current = null;
      }
    }
  }
  return (
    <form
      onSubmit={(event) => {
        void handleSubmit(submit)(event);
      }}
      noValidate
    >
      <Status
        loading={categories.isLoading}
        error={categories.error}
        retry={() => categories.refetch()}
      />
      <div className="card">
        <label>
          유형
          <select {...register("type")}>
            <option value="EXPENSE">지출</option>
            <option value="INCOME">수입</option>
          </select>
        </label>
        <label>
          금액 · 원
          <input
            inputMode="numeric"
            aria-label="금액 · 원"
            aria-describedby="transaction-amount-error"
            {...register("amount", {
              required: "금액을 입력하세요.",
              pattern: {
                value: /^[1-9][0-9]{0,11}$/,
                message: "1~999,999,999,999원의 정수를 입력하세요.",
              },
            })}
            aria-invalid={!!errors.amount}
          />
          <span id="transaction-amount-error" className="form-error">
            {errors.amount?.message}
          </span>
        </label>
        <label>
          카테고리
          <select
            aria-label="카테고리"
            aria-describedby="transaction-categoryId-error"
            {...register("categoryId", {
              required: "카테고리를 선택하세요.",
              validate: (id) =>
                categories.data?.items.some(
                  (c) => c.id === id && c.type === kind,
                ) || "거래 유형에 맞는 카테고리를 선택하세요.",
            })}
          >
            <option value="">선택하세요</option>
            {categories.data?.items
              .filter(
                (c) =>
                  c.type === kind &&
                  (!c.archived || existing?.category.id === c.id),
              )
              .map((c) => (
                <option key={c.id} value={c.id}>
                  {c.name}
                  {c.archived ? " (보관)" : ""}
                </option>
              ))}
          </select>
          <span id="transaction-categoryId-error" className="form-error">
            {errors.categoryId?.message}
          </span>
        </label>
        <label>
          거래일
          <input
            type="date"
            max={user?.today}
            aria-label="거래일"
            aria-describedby="transaction-transactionDate-error"
            {...register("transactionDate", {
              required: "날짜를 선택하세요.",
              validate: (v) =>
                v <= user!.today || "미래 날짜는 입력할 수 없습니다.",
            })}
          />
          <span id="transaction-transactionDate-error" className="form-error">
            {errors.transactionDate?.message}
          </span>
        </label>
        <label>
          메모 · 선택
          <textarea
            rows={3}
            aria-label="메모 · 선택"
            aria-describedby="transaction-memo-error"
            {...register("memo", {
              maxLength: { value: 500, message: "500자 이내로 입력하세요." },
            })}
          />
          <span id="transaction-memo-error" className="form-error">
            {errors.memo?.message}
          </span>
        </label>
        <LocationPicker value={location} onChange={setLocation} />
        <p role="alert">{error}</p>
        <p role="status">{notice}</p>
        {uncertain && existing && (
          <button type="button" className="quiet" onClick={verify}>
            변경 결과 다시 확인
          </button>
        )}
        <div className="actions">
          <button
            disabled={
              isSubmitting || !categories.data || (!!existing && uncertain)
            }
          >
            {isSubmitting
              ? "저장 중…"
              : uncertain
                ? "같은 요청 다시 확인"
                : "저장"}
          </button>
          <button type="button" className="quiet" onClick={() => router.back()}>
            취소
          </button>
        </div>
      </div>
    </form>
  );
}
